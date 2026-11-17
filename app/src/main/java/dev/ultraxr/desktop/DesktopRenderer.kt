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
    val usePosition: Boolean, val dim: Float)

/**
 * Draws the spatial desktop: each window is a rounded rectangle textured with its page, with a
 * title bar above it, placed in the world and seen from the head pose. One view (the glasses show
 * the same picture to both eyes on this phone). Black is transparent on the glasses.
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

    private class Tex(val oes: Int, val st: SurfaceTexture, val surface: Surface) { @Volatile var dirty = false; val m = FloatArray(16).also { Matrix.setIdentityM(it, 0) }; var bar = 0; var barVersion = -1L; var hasFrame = false }
    private val tex = HashMap<Int, Tex>()
    private var progOes = 0; private var prog2d = 0; private var progFlat = 0
    private var vao = 0
    private var homeTex = 0; private var homeVersion = -1L
    private val proj = FloatArray(16); private val view = FloatArray(16); private val vp = FloatArray(16); private val model = FloatArray(16); private val mvp = FloatArray(16)
    private val headM = FloatArray(16); private val ident = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private var lastNs = 0L; private var fpsWindowStart = 0L; private var fpsFrames = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new GL context: every texture and surface from the old one is gone.
        val old = tex.toMap(); tex.clear()
        for ((id, t) in old) { onSurface(id, null); try { t.surface.release(); t.st.release() } catch (_: Exception) {} }
        homeTex = 0; homeVersion = -1
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
        Q.toMatrix(q, p, headM); Matrix.invertM(view, 0, headM, 0)
        val tanV = if (preview) 0.62 else Tracking.displayTanV; val tanH = if (preview) tanV * width / height.coerceAtLeast(1) else Tracking.displayTanH
        Matrix.frustumM(proj, 0, (-tanH * NEAR).toFloat(), (tanH * NEAR).toFloat(), (-tanV * NEAR).toFloat(), (tanV * NEAR).toFloat(), NEAR.toFloat(), 80f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST); GLES30.glBindVertexArray(vao)

        if (f.home != null) {
            if (homeTex == 0) homeTex = newTexture2d()
            if (homeVersion != f.homeVersion) { upload(homeTex, f.home); homeVersion = f.homeVersion }
            val c = Geometry.centre(f.anchor, f.homeYaw, 0.0, HOME_DISTANCE)
            Geometry.modelMatrix(c, f.homeYaw, 0.0, HOME_WIDTH, HOME_WIDTH * f.home.height / f.home.width, model)
            draw(prog2d, GLES30.GL_TEXTURE_2D, homeTex, vp, ident, HOME_WIDTH, HOME_WIDTH * f.home.height / f.home.width, 0.05, 1f, 1f, 1f, 1f, 1f)
        }
        // Far windows first, so nearer ones cover them; the focused window last among equals.
        for (w in f.windows.sortedWith(compareByDescending<RenderWindow> { it.distance }.thenBy { it.focused })) {
            val t = tex[w.id] ?: continue
            val matrix = if (w.headLocked) proj else vp
            val c = if (w.headLocked) doubleArrayOf(0.0, 0.0, -w.distance) else Geometry.centre(f.anchor, w.yaw, w.pitch, w.distance)
            val yaw = if (w.headLocked) 0.0 else w.yaw; val pitch = if (w.headLocked) 0.0 else w.pitch
            val shade = if (w.focused) 1f else f.dim
            if (w.focused && !w.headLocked) {   // soft outline behind the focused window
                Geometry.modelMatrix(c, yaw, pitch, w.width + 0.03, w.height + 0.03, model)
                draw(progFlat, 0, 0, matrix, ident, w.width + 0.03, w.height + 0.03, 0.045, 0.35f, 0.75f, 1f, 0.55f, 1f)
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
                draw(prog2d, GLES30.GL_TEXTURE_2D, t.bar, matrix, ident, w.width, w.barHeight, w.barHeight * 0.45, shade, shade, shade, 1f, 1f)
            }
            if (w.showCursor) {
                val size = (w.distance * 0.018).coerceAtLeast(0.012)
                val cc = offset(c, yaw, pitch, (w.cursorU - 0.5) * w.width, (0.5 - w.cursorV) * w.height)
                Geometry.modelMatrix(cc, yaw, pitch, size, size, model)
                draw(progFlat, 0, 0, matrix, ident, size, size, size / 2, 1f, 1f, 1f, 0.95f, if (w.pressed) 0.01f else 0.55f)
            }
        }
        GLES30.glDisable(GLES30.GL_BLEND)
        avgCpuMs = avgCpuMs * 0.95 + (System.nanoTime() - now) / 1e6 * 0.05
    }

    /** [c] moved by [x] to the window's right and [y] up, in the window's own plane. */
    private fun offset(c: DoubleArray, yaw: Double, pitch: Double, x: Double, y: Double): DoubleArray {
        val cy = Math.cos(yaw); val sy = Math.sin(yaw); val cp = Math.cos(pitch); val sp = Math.sin(pitch)
        return doubleArrayOf(c[0] + cy * x + sy * sp * y, c[1] + cp * y, c[2] - sy * x + cy * sp * y)
    }

    private fun draw(prog: Int, target: Int, texture: Int, viewProj: FloatArray, texM: FloatArray, w: Double, h: Double, radius: Double, r: Float, g: Float, b: Float, a: Float, ringOrAlpha: Float) {
        GLES30.glUseProgram(prog)
        Matrix.multiplyMM(mvp, 0, viewProj, 0, model, 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(prog, "uMvp"), 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(prog, "uTex"), 1, false, texM, 0)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(prog, "uSize"), w.toFloat(), h.toFloat())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uRadius"), radius.coerceAtMost(minOf(w, h) / 2).toFloat())
        GLES30.glUniform4f(GLES30.glGetUniformLocation(prog, "uTint"), r * a, g * a, b * a, a)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uAlpha"), 1f)
        if (prog == progFlat) GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uRing"), if (ringOrAlpha < 1f) ringOrAlpha else 0f)
        if (texture != 0) { GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(target, texture); GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uSampler"), 0) }
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
        const val HOME_DISTANCE = 1.9
        const val HOME_WIDTH = 1.5
    }
}
