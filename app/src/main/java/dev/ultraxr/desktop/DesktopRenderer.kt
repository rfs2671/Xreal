package dev.ultraxr.desktop

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.Surface
import dev.ultraxr.core.Q
import dev.ultraxr.tracking.Tracking
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** What the renderer needs to know about one window for one frame. Immutable. */
class RenderWindow(
    val id: Int, val yaw: Double, val pitch: Double, val distance: Double, val width: Double, val height: Double, val barHeight: Double,
    val focused: Boolean, val cursorU: Double, val cursorV: Double, val showCursor: Boolean, val pressed: Boolean,
    val bar: Bitmap?, val barVersion: Long, val headLocked: Boolean,
)

class RenderFrame(val windows: List<RenderWindow>, val anchor: DoubleArray, val home: Bitmap?, val homeVersion: Long, val homeYaw: Double,
    val usePosition: Boolean, val dim: Float,
    /** The dock under the rail (always shown unless a window is fullscreen), facing [dockYaw]. */
    val dock: Bitmap? = null, val dockVersion: Long = 0, val dockYaw: Double = 0.0,
    /** The stretch of rail under the focused window is lit: centre heading and half-width, radians. NaN for none. */
    val railYaw: Double = Double.NaN, val railHalf: Double = 0.0, val showRail: Boolean = true,
    /** Look-to-press progress ring: world direction, distance, and 0..1 (0 hides it). */
    val dwellYaw: Double = 0.0, val dwellPitch: Double = 0.0, val dwellDistance: Double = 2.0, val dwellProgress: Double = 0.0)

/** The interface is drawn with light on nothing: black is see-through on the glasses. */
object Ink {
    val AMBER = floatArrayOf(1.0f, 0.706f, 0.329f)     // #FFB454 focus
    val WHITE = floatArrayOf(0.949f, 0.957f, 0.941f)   // #F2F4F0 text
    val SLATE = floatArrayOf(0.361f, 0.400f, 0.439f)   // #5C6670 idle lines
    const val AMBER_INT = 0xFFFFB454.toInt(); const val WHITE_INT = 0xFFF2F4F0.toInt(); const val SLATE_INT = 0xFF8A949E.toInt(); const val CORAL_INT = 0xFFFF6B5E.toInt()
}

/**
 * Draws the spatial desktop: each window is a rounded rectangle textured with its page, with a
 * title bar above it, placed in the world and seen from the head pose. Black is transparent on the
 * glasses. In 2D one view fills the picture (both eyes see the same). With [stereo] the scene is
 * drawn twice, from each eye, into the left and right halves of the picture: the glasses' half-width
 * 3D mode stretches each half over one eye, so each half is rendered with that eye's full field of
 * view and the squeeze is undone by the glasses.
 */
class DesktopRenderer(private val preview: Boolean, private val onSurface: (id: Int, surface: Surface?) -> Unit) : GLSurfaceView.Renderer {
    @Volatile var frame = RenderFrame(emptyList(), doubleArrayOf(0.0, 0.0, 0.0), null, 0, 0.0, false, 1f)
    @Volatile var width = 0; private set
    @Volatile var height = 0; private set
    @Volatile var frames = 0L; private set
    @Volatile var lateFrames = 0L; private set
    @Volatile var avgCpuMs = 0.0; private set
    @Volatile var fps = 0.0; private set
    @Volatile var periodNs = 16_666_667L
    @Volatile var glError = ""; private set
    /** Preview only: look direction when there is no head tracking (for use without glasses). */
    @Volatile var previewYaw = 0.0
    @Volatile var previewPitch = 0.0
    /** Draw left and right eye views side by side (glasses in half-width 3D). Ignored by the preview. */
    @Volatile var stereo = false
    /** Left half to the right eye and the reverse, in case a unit shows the halves the other way round. */
    @Volatile var swapEyes = false
    /** Distance between the wearer's eyes, metres. */
    @Volatile var ipdM = 0.064

    private class Tex(val oes: Int, val st: SurfaceTexture, val surface: Surface) { @Volatile var dirty = false; val m = FloatArray(16).also { Matrix.setIdentityM(it, 0) }; var bar = 0; var barVersion = -1L; var hasFrame = false }
    private val tex = HashMap<Int, Tex>()
    private var progOes = 0; private var prog2d = 0; private var progFlat = 0
    private var vao = 0
    private var homeTex = 0; private var homeVersion = -1L
    private var dockTex = 0; private var dockVersion = -1L
    private val uniforms = HashMap<Int, IntArray>(); private var boundProgram = 0
    private val proj = FloatArray(16); private val view = FloatArray(16); private val vp = FloatArray(16); private val model = FloatArray(16); private val mvp = FloatArray(16)
    private val headM = FloatArray(16); private val ident = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val projHead = FloatArray(16); private val shiftM = FloatArray(16); private val eyeP = DoubleArray(3)
    private var stereoNoted = -1
    private var lastNs = 0L; private var fpsWindowStart = 0L; private var fpsFrames = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new GL context: every texture and surface from the old one is gone.
        val old = tex.toMap(); tex.clear()
        for ((id, t) in old) { onSurface(id, null); try { t.surface.release(); t.st.release() } catch (_: Exception) {} }
        homeTex = 0; homeVersion = -1; dockTex = 0; dockVersion = -1; uniforms.clear(); boundProgram = 0
        try {
            val vs = """#version 300 es
                layout(location=0) in vec2 aPos; uniform mat4 uMvp; uniform mat4 uTex; out vec2 vUv; out vec2 vLocal;
                void main(){ vLocal = aPos; vUv = (uTex * vec4(aPos.x + 0.5, aPos.y + 0.5, 0.0, 1.0)).xy; gl_Position = uMvp * vec4(aPos, 0.0, 1.0); }"""
            val shape = """
                uniform vec2 uSize; uniform float uRadius; uniform float uAlpha; uniform vec4 uTint;
                float mask(vec2 l){ vec2 p = abs(l) * uSize - (uSize * 0.5 - vec2(uRadius)); float d = length(max(p, 0.0)) + min(max(p.x, p.y), 0.0) - uRadius; float w = fwidth(d) + 1e-5; return 1.0 - smoothstep(-w, w, d); }"""
            progOes = program(vs, "#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\nprecision mediump float; in vec2 vUv; in vec2 vLocal; out vec4 o; uniform samplerExternalOES uSampler;$shape\nvoid main(){ vec4 c = texture(uSampler, vUv); o = vec4(c.rgb * uTint.rgb, 1.0) * mask(vLocal) * uAlpha; }")
            prog2d = program(vs, "#version 300 es\nprecision mediump float; in vec2 vUv; in vec2 vLocal; out vec4 o; uniform sampler2D uSampler;$shape\nvoid main(){ vec4 c = texture(uSampler, vec2(vUv.x, 1.0 - vUv.y)); o = vec4(c.rgb * uTint.rgb, c.a) * mask(vLocal) * uAlpha; }")
            progFlat = program(vs, "#version 300 es\nprecision mediump float; in vec2 vUv; in vec2 vLocal; out vec4 o;$shape\nuniform float uRing;\nvoid main(){ float m = mask(vLocal); if (uRing > 0.0) { float r = length(vLocal) * 2.0; m = smoothstep(uRing - 0.12, uRing, r) * (1.0 - smoothstep(0.88, 1.0, r)); } o = uTint * m * uAlpha; }")
            val ids = IntArray(1); GLES30.glGenVertexArrays(1, ids, 0); vao = ids[0]; GLES30.glBindVertexArray(vao)
            GLES30.glGenBuffers(1, ids, 0); GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
            val q = floatArrayOf(-0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, q.size * 4, ByteBuffer.allocateDirect(q.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(q); position(0) }, GLES30.GL_STATIC_DRAW)
            GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
            glError = ""
            Tracking.noteGraphics("desktop GL context created (" + (if (preview) "phone preview" else "glasses") + "): ${GLES30.glGetString(GLES30.GL_RENDERER)}")
        } catch (e: Throwable) { glError = e.toString(); Tracking.noteGraphics("desktop GL init FAILED: $e") }
    }

    private fun program(vs: String, fs: String): Int {
        fun sh(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type); GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
            val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw IllegalStateException("shader: " + GLES30.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES30.glCreateProgram(); GLES30.glAttachShader(p, sh(GLES30.GL_VERTEX_SHADER, vs)); GLES30.glAttachShader(p, sh(GLES30.GL_FRAGMENT_SHADER, fs)); GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("link: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { width = w; height = h }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        if (lastNs != 0L && now - lastNs > periodNs * 3 / 2) lateFrames++
        lastNs = now; frames++; fpsFrames++
        if (fpsWindowStart == 0L) fpsWindowStart = now
        if (now - fpsWindowStart >= 1_000_000_000L) { fps = fpsFrames * 1e9 / (now - fpsWindowStart); fpsFrames = 0; fpsWindowStart = now }
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (glError.isNotEmpty() || width == 0) return
        val f = frame
        syncTextures(f)

        // View: the head's orientation always; its position only when position tracking is trusted.
        val head = Tracking.head.predict(now + periodNs * 3 / 2)
        val tracked = head.mode != dev.ultraxr.core.HeadTracker.Mode.NONE
        val q = if (tracked) head.pose.q else Q.mul(Q.axisAngle(0.0, 1.0, 0.0, previewYaw), Q.axisAngle(1.0, 0.0, 0.0, previewPitch))
        val p = if (tracked && f.usePosition) head.pose.p else f.anchor
        val tanV = if (preview) 0.62 else Tracking.displayTanV; val tanH = if (preview) tanV * width / height.coerceAtLeast(1) else Tracking.displayTanH
        val two = stereo && !preview
        if (stereoNoted != (if (two) 1 else 0)) { stereoNoted = if (two) 1 else 0; Tracking.noteGraphics(if (two) "desktop drawing two eye views side by side, eye distance " + Math.round(ipdM * 1000) + " mm" + (if (swapEyes) ", eyes swapped" else "") else "desktop drawing one view") }

        GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST); GLES30.glBindVertexArray(vao); boundProgram = 0

        val right = Q.rotate(q, doubleArrayOf(1.0, 0.0, 0.0))
        for (half in 0 until (if (two) 2 else 1)) {
            val off = if (two) Stereo.eyeOffset(half, ipdM, swapEyes) else 0.0
            val shift = if (two) Stereo.shift(off) else 0.0
            if (two) GLES30.glViewport(half * (width / 2), 0, width / 2, height)
            eyeP[0] = p[0] + right[0] * off; eyeP[1] = p[1] + right[1] * off; eyeP[2] = p[2] + right[2] * off
            Q.toMatrix(q, eyeP, headM); Matrix.invertM(view, 0, headM, 0)
            Matrix.frustumM(proj, 0, ((shift - tanH) * NEAR).toFloat(), ((shift + tanH) * NEAR).toFloat(), (-tanV * NEAR).toFloat(), (tanV * NEAR).toFloat(), NEAR.toFloat(), 80f)
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
            // Things fixed to the head (a fullscreen window) are placed relative to the head's centre.
            Matrix.setIdentityM(shiftM, 0); Matrix.translateM(shiftM, 0, (-off).toFloat(), 0f, 0f)
            Matrix.multiplyMM(projHead, 0, proj, 0, shiftM, 0)
            drawScene(f, q)
        }
        GLES30.glDisable(GLES30.GL_BLEND)
        avgCpuMs = avgCpuMs * 0.95 + (System.nanoTime() - now) / 1e6 * 0.05
    }

    /** Everything in the scene, once, with the current [vp] (world) and [projHead] (head-fixed) matrices. */
    private fun drawScene(f: RenderFrame, q: DoubleArray) {
        if (f.showRail) {
            // The rail: short straight pieces around the user. The piece(s) under the focused window are lit.
            val seg = 2 * Math.PI / Rail.SEGMENTS; val len = 2 * Rail.RADIUS * Math.tan(seg / 2) * 0.92
            val lookYaw = Geometry.yawOf(Q.rotate(q, doubleArrayOf(0.0, 0.0, -1.0)))
            for (i in 0 until Rail.SEGMENTS) {
                val yaw = Geometry.wrap(i * seg)
                if (Math.abs(Geometry.wrap(yaw - lookYaw)) > RAIL_VISIBLE) continue      // behind or beside the view
                val lit = !f.railYaw.isNaN() && Math.abs(Geometry.wrap(yaw - f.railYaw)) <= f.railHalf
                val col = if (lit) Ink.AMBER else Ink.SLATE
                Geometry.modelMatrix(Geometry.centre(f.anchor, yaw, Rail.PITCH, Rail.RADIUS), yaw, 0.0, len, Rail.THICKNESS, model)
                draw(progFlat, 0, 0, vp, ident, len, Rail.THICKNESS, Rail.THICKNESS / 2, col[0], col[1], col[2], if (lit) 0.9f else 0.6f, 1f)
            }
        }
        if (f.dock != null) {
            if (dockTex == 0) dockTex = newTexture2d()
            if (dockVersion != f.dockVersion) { upload(dockTex, f.dock); dockVersion = f.dockVersion }
            Geometry.modelMatrix(Geometry.centre(f.anchor, f.dockYaw, Rail.DOCK_PITCH, Rail.DOCK_DISTANCE), f.dockYaw, Rail.DOCK_PITCH, Rail.DOCK_WIDTH, Rail.DOCK_HEIGHT, model)
            draw(prog2d, GLES30.GL_TEXTURE_2D, dockTex, vp, ident, Rail.DOCK_WIDTH, Rail.DOCK_HEIGHT, 0.0, 1f, 1f, 1f, 1f, 1f)
        }
        if (f.home != null) {
            if (homeTex == 0) homeTex = newTexture2d()
            if (homeVersion != f.homeVersion) { upload(homeTex, f.home); homeVersion = f.homeVersion }
            val hh = HOME_WIDTH * f.home.height / f.home.width
            Geometry.modelMatrix(Geometry.centre(f.anchor, f.homeYaw, 0.0, HOME_DISTANCE), f.homeYaw, 0.0, HOME_WIDTH, hh, model)
            draw(prog2d, GLES30.GL_TEXTURE_2D, homeTex, vp, ident, HOME_WIDTH, hh, 0.0, 1f, 1f, 1f, 1f, 1f)
        }
        // Far windows first, so nearer ones cover them; the focused window last among equals.
        for (w in f.windows.sortedWith(compareByDescending<RenderWindow> { it.distance }.thenBy { it.focused })) {
            val t = tex[w.id] ?: continue
            val matrix = if (w.headLocked) projHead else vp
            val c = if (w.headLocked) doubleArrayOf(0.0, 0.0, -w.distance) else Geometry.centre(f.anchor, w.yaw, w.pitch, w.distance)
            val yaw = if (w.headLocked) 0.0 else w.yaw; val pitch = if (w.headLocked) 0.0 else w.pitch
            val shade = if (w.focused) 1f else f.dim
            if (w.focused && !w.headLocked) {   // a hairline of amber around the focused window
                Geometry.modelMatrix(c, yaw, pitch, w.width + 0.005, w.height + 0.005, model)
                draw(progFlat, 0, 0, matrix, ident, w.width + 0.005, w.height + 0.005, 0.0325, Ink.AMBER[0], Ink.AMBER[1], Ink.AMBER[2], 0.85f, 1f)
            }
            Geometry.modelMatrix(c, yaw, pitch, w.width, w.height, model)
            if (t.hasFrame) draw(progOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t.oes, matrix, t.m, w.width, w.height, 0.03, shade, shade, shade, 1f, 1f)
            else draw(progFlat, 0, 0, matrix, ident, w.width, w.height, 0.03, 0.07f, 0.09f, 0.11f, 1f, 1f)
            if (!w.headLocked && w.bar != null) {
                if (t.bar == 0) t.bar = newTexture2d()
                if (t.barVersion != w.barVersion) { upload(t.bar, w.bar); t.barVersion = w.barVersion }
                val up = w.height / 2 + w.barHeight / 2 + 0.012
                val bc = offset(c, yaw, pitch, 0.0, up)
                Geometry.modelMatrix(bc, yaw, pitch, w.width, w.barHeight, model)
                draw(prog2d, GLES30.GL_TEXTURE_2D, t.bar, matrix, ident, w.width, w.barHeight, 0.0, 1f, 1f, 1f, 1f, 1f)
            }
            if (w.showCursor) {
                val size = (w.distance * 0.018).coerceAtLeast(0.012)
                val cc = offset(c, yaw, pitch, (w.cursorU - 0.5) * w.width, (0.5 - w.cursorV) * w.height)
                Geometry.modelMatrix(cc, yaw, pitch, size, size, model)
                draw(progFlat, 0, 0, matrix, ident, size, size, size / 2, Ink.AMBER[0], Ink.AMBER[1], Ink.AMBER[2], 1f, if (w.pressed) 0.01f else 0.6f)
            }
        }
        if (f.dwellProgress > 0.0) {   // look-to-press: a ring that closes as the hold completes
            val size = f.dwellDistance * 0.05
            Geometry.modelMatrix(Geometry.centre(f.anchor, f.dwellYaw, f.dwellPitch, f.dwellDistance * 0.985), f.dwellYaw, f.dwellPitch, size, size, model)
            draw(progFlat, 0, 0, vp, ident, size, size, size / 2, Ink.AMBER[0], Ink.AMBER[1], Ink.AMBER[2], 1f, (0.92 - 0.9 * f.dwellProgress).toFloat().coerceIn(0.02f, 0.92f))
        }
    }

    /** [c] moved by [x] to the window's right and [y] up, in the window's own plane. */
    private fun offset(c: DoubleArray, yaw: Double, pitch: Double, x: Double, y: Double): DoubleArray {
        val cy = Math.cos(yaw); val sy = Math.sin(yaw); val cp = Math.cos(pitch); val sp = Math.sin(pitch)
        return doubleArrayOf(c[0] + cy * x + sy * sp * y, c[1] + cp * y, c[2] - sy * x + cy * sp * y)
    }

    private fun draw(prog: Int, target: Int, texture: Int, viewProj: FloatArray, texM: FloatArray, w: Double, h: Double, radius: Double, r: Float, g: Float, b: Float, a: Float, ringOrAlpha: Float) {
        // uniform locations are looked up once per program, not on every draw
        val u = uniforms.getOrPut(prog) { IntArray(UNIFORM_NAMES.size) { GLES30.glGetUniformLocation(prog, UNIFORM_NAMES[it]) } }
        if (boundProgram != prog) { GLES30.glUseProgram(prog); boundProgram = prog }
        Matrix.multiplyMM(mvp, 0, viewProj, 0, model, 0)
        GLES30.glUniformMatrix4fv(u[0], 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(u[1], 1, false, texM, 0)
        GLES30.glUniform2f(u[2], w.toFloat(), h.toFloat())
        GLES30.glUniform1f(u[3], radius.coerceAtMost(minOf(w, h) / 2).toFloat())
        GLES30.glUniform4f(u[4], r * a, g * a, b * a, a)
        GLES30.glUniform1f(u[5], 1f)
        if (prog == progFlat) GLES30.glUniform1f(u[6], if (ringOrAlpha < 1f) ringOrAlpha else 0f)
        if (texture != 0) { GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(target, texture); GLES30.glUniform1i(u[7], 0) }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun syncTextures(f: RenderFrame) {
        val wanted = f.windows.map { it.id }.toSet()
        val gone = tex.keys.filter { it !in wanted }
        for (id in gone) { val t = tex.remove(id) ?: continue; onSurface(id, null); t.surface.release(); t.st.release(); GLES30.glDeleteTextures(2, intArrayOf(t.oes, t.bar), 0) }
        for (w in f.windows) {
            var t = tex[w.id]
            if (t == null) {
                val ids = IntArray(1); GLES30.glGenTextures(1, ids, 0)
                GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                val st = SurfaceTexture(ids[0]); st.setDefaultBufferSize(WebWindow.PIXEL_WIDTH, WebWindow.PIXEL_HEIGHT)
                val nt = Tex(ids[0], st, Surface(st))
                st.setOnFrameAvailableListener { nt.dirty = true }
                tex[w.id] = nt; t = nt
                onSurface(w.id, nt.surface)
            }
            if (t.dirty) { t.dirty = false; try { t.st.updateTexImage(); t.st.getTransformMatrix(t.m); t.hasFrame = true } catch (_: Exception) {} }
        }
    }

    private fun newTexture2d(): Int {
        val ids = IntArray(1); GLES30.glGenTextures(1, ids, 0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR); GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE); GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun upload(texture: Int, b: Bitmap) { if (b.isRecycled) return; GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture); GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0) }

    companion object {
        private const val NEAR = 0.08
        private val RAIL_VISIBLE = Math.toRadians(62.0)
        private val UNIFORM_NAMES = arrayOf("uMvp", "uTex", "uSize", "uRadius", "uTint", "uAlpha", "uRing", "uSampler")
        const val HOME_DISTANCE = 2.0
        const val HOME_WIDTH = 0.9
    }
}
