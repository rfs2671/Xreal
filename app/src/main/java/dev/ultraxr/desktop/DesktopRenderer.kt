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

/** A picture drawn once with the 2D canvas (a mark, a piece of text) and shown as it is. [key] names what it shows: the same key is the same picture. */
class Sprite(val key: String, val bitmap: Bitmap)

/** What the renderer needs to know about one window for one frame. Immutable. */
class RenderWindow(
    val id: Int, val yaw: Double, val pitch: Double, val distance: Double, val width: Double, val height: Double,
    val focused: Boolean, val cursorU: Double, val cursorV: Double, val showCursor: Boolean, val pressed: Boolean, val headLocked: Boolean,
    /** How far the brackets are lit, 0..1: 1 when the focus is on the window itself (not on its page or its handle). It rises and falls as the focus moves. */
    val glow: Float = 0f,
    /** The focus is somewhere on this window: its brackets are Chalk, not Slate. */
    val near: Boolean = false,
    /** How much of the brackets is drawn, 0..1 (they draw themselves last when a window opens), and how long their arms are, metres. */
    val bracket: Float = 1f, val arm: Double = 0.05,
    /** The title above the top-left bracket and the handle's words under the window, for the window in focus; null for the others. */
    val title: Sprite? = null, val handle: Sprite? = null, val handleWidth: Double = 0.0, val handleHeight: Double = 0.0, val handleGap: Double = 0.0,
    /** The window is on its way out of its mark or back into it. */
    val growing: Boolean = false,
    /** The size of the page's picture, which follows the window's shape. */
    val pixelW: Int = WebWindow.PIXEL_WIDTH, val pixelH: Int = WebWindow.PIXEL_HEIGHT,
    /** Its corners are drawn as brackets: it is being sized. */
    val corners: Boolean = false,
)

/** One mark of the shelf: which way it lies from the wearer, how large (1 at rest, [Shelf.FOCUS_SCALE] in focus), and how strongly it is drawn. */
class RenderMark(val yaw: Double, val scale: Double, val sprite: Sprite, val alpha: Float = 1f)

/**
 * The shelf and the status line, on their arc ([Shelf]). [yaw] is the heading the shelf faces; [name] stands under the
 * mark at [nameYaw]; [moreLeft] and [moreRight] are where a small dot says that the row goes on (NaN for none).
 */
class RenderShelf(val marks: List<RenderMark>, val name: Sprite?, val nameYaw: Double, val status: Sprite?, val yaw: Double,
    val moreLeft: Double = Double.NaN, val moreRight: Double = Double.NaN,
    /** The home panel ([Home]), shown in the shelf's place when no window is open, and the light round its tile in focus: where its middle is on the panel (metres right and up) and how large. */
    val panel: Sprite? = null, val ring: Sprite? = null, val ringX: Double = 0.0, val ringY: Double = 0.0, val ringW: Double = 0.0, val ringH: Double = 0.0, val panelAlpha: Float = 1f)

class RenderFrame(val windows: List<RenderWindow>, val anchor: DoubleArray, val shelf: RenderShelf?, val usePosition: Boolean, val dim: Float,
    /** Everything the glasses are shown is multiplied by this (the picture brightness of the settings). */
    val brightness: Float = 1f,
    /** Look-to-press progress ring: world direction, distance, 0..1 (0 hides it), and how large it is, metres (0: as large as suits the distance). */
    val dwellYaw: Double = 0.0, val dwellPitch: Double = 0.0, val dwellDistance: Double = 2.0, val dwellProgress: Double = 0.0, val dwellSize: Double = 0.0,
    /** The point each window is arranged around, by its id: its own place in the room. A window not named is arranged around [anchor]. */
    val anchors: Map<Int, DoubleArray> = emptyMap(),
    /** The hand that points, or null. */
    val hand: RenderHand? = null,
    /** The point the look-to-press ring's direction is from; [anchor] when null. */
    val dwellAnchor: DoubleArray? = null)

/**
 * The pointing hand as the wearer is shown it: the tips of thumb and index finger, the ray ([from], [dir]) and how far
 * along it the thing it is on lies ([reach], 0 for nothing), which faces [yaw], [pitch]. [head] is where the wearer's
 * head is, so that the dots face it.
 */
class RenderHand(val thumb: DoubleArray, val index: DoubleArray, val from: DoubleArray, val dir: DoubleArray, val head: DoubleArray,
    val reach: Double, val yaw: Double, val pitch: Double, val pinching: Boolean)

/**
 * The interface is drawn with light on nothing: black is see-through on the glasses. These six are all its colours
 * (docs/INTERFACE.md, "Colour"); the only other colour is an app's own, on the ring of its mark.
 */
object Ink {
    val WHITE = floatArrayOf(0.949f, 0.957f, 0.941f)   // Chalk #F2F4F0: text, resting lines
    val AMBER = floatArrayOf(0.608f, 0.549f, 1.0f)     // the one thing in focus: violet #9B8CFF (Glass.VIOLET; it was Lamp, an amber)
    val TIDE = floatArrayOf(0.435f, 0.839f, 0.816f)    // Tide #6FD6D0: pinned to the room; things that are set and steady
    val CORAL = floatArrayOf(1.0f, 0.420f, 0.369f)     // Ember #FF6B5E: close, delete, something wrong
    val DUSK = floatArrayOf(0.541f, 0.580f, 0.620f)    // Dusk #8A949E: labels of things not in focus, hints
    val SLATE = floatArrayOf(0.361f, 0.400f, 0.439f)   // Slate #5C6670: idle lines far from focus
    const val CHALK_INT = 0xFFF2F4F0.toInt(); const val LAMP_INT = 0xFF9B8CFF.toInt(); const val TIDE_INT = 0xFF6FD6D0.toInt()
    const val EMBER_INT = 0xFFFF6B5E.toInt(); const val DUSK_INT = 0xFF8A949E.toInt(); const val SLATE_INT = 0xFF5C6670.toInt()
    // the names the colours had before they were given their own
    const val WHITE_INT = CHALK_INT; const val AMBER_INT = LAMP_INT; const val CORAL_INT = EMBER_INT
}

/**
 * Draws the spatial desktop: each window is its page on a rectangle, with a bracket at each corner and
 * no frame, placed in the world and seen from the head pose; the shelf and the status line stand on
 * their arc round the wearer. Black is transparent on the
 * glasses. In 2D one view fills the picture (both eyes see the same). With [stereo] the scene is
 * drawn twice, from each eye, into the left and right halves of the picture: the glasses' half-width
 * 3D mode stretches each half over one eye, so each half is rendered with that eye's full field of
 * view and the squeeze is undone by the glasses.
 */
class DesktopRenderer(private val preview: Boolean, private val onSurface: (id: Int, surface: Surface?) -> Unit) : GLSurfaceView.Renderer {
    @Volatile var frame = RenderFrame(emptyList(), doubleArrayOf(0.0, 0.0, 0.0), null, false, 1f)
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
    /**
     * For checking the look without the glasses on (debuggable builds only; set by the activity): when a file
     * "eye_capture.req" appears beside this one, the picture one eye of the glasses would get, with the glasses' own
     * field of view at 1920 x 1080, is written here once.
     */
    @Volatile var eyeCapture: java.io.File? = null

    private class Tex(val oes: Int, val st: SurfaceTexture, val surface: Surface) { @Volatile var dirty = false; val m = FloatArray(16).also { Matrix.setIdentityM(it, 0) }; var hasFrame = false; var pw = 0; var ph = 0 }
    private val tex = HashMap<Int, Tex>()
    private var progOes = 0; private var prog2d = 0; private var progFlat = 0
    private var vao = 0
    /** The textures of the sprites last drawn, by key; the ones not drawn for longest are dropped. */
    private val sprites = LinkedHashMap<String, Int>(64, 0.75f, true)
    private var bright = 1f
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
        sprites.clear(); uniforms.clear(); boundProgram = 0
        try {
            val vs = """#version 300 es
                layout(location=0) in vec2 aPos; uniform mat4 uMvp; uniform mat4 uTex; out vec2 vUv; out vec2 vLocal;
                void main(){ vLocal = aPos; vUv = (uTex * vec4(aPos.x + 0.5, aPos.y + 0.5, 0.0, 1.0)).xy; gl_Position = uMvp * vec4(aPos, 0.0, 1.0); }"""
            // sdf: how far a place of the quad is outside the rounded rectangle it stands for (negative inside), metres
            val shape = """
                uniform vec2 uSize; uniform float uRadius; uniform vec4 uTint;
                float sdf(vec2 l){ vec2 p = abs(l) * uSize - (uSize * 0.5 - vec2(uRadius)); return length(max(p, 0.0)) + min(max(p.x, p.y), 0.0) - uRadius; }
                float mask(vec2 l){ float d = sdf(l); float w = fwidth(d) + 1e-5; return 1.0 - smoothstep(-w, w, d); }"""
            progOes = program(vs, "#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\nprecision highp float; in vec2 vUv; in vec2 vLocal; out vec4 o; uniform samplerExternalOES uSampler;$shape\nvoid main(){ vec4 c = texture(uSampler, vUv); o = vec4(c.rgb * uTint.rgb, uTint.a) * mask(vLocal); }")
            prog2d = program(vs, "#version 300 es\nprecision highp float; in vec2 vUv; in vec2 vLocal; out vec4 o; uniform sampler2D uSampler;$shape\nvoid main(){ vec4 c = texture(uSampler, vec2(vUv.x, 1.0 - vUv.y)); o = vec4(c.rgb * uTint.rgb, c.a * uTint.a) * mask(vLocal); }")
            // uRing: a ring instead of the shape (the cursor, the look-to-press ring). uGlow: a halo instead of the shape, nothing
            // at the quad's edge and full that far inside it: the light a lit line spills.
            progFlat = program(vs, "#version 300 es\nprecision highp float; in vec2 vUv; in vec2 vLocal; out vec4 o;$shape\nuniform float uRing; uniform float uGlow; uniform float uEdge;\nvoid main(){ float m = mask(vLocal); if (uGlow > 0.0) { float g = clamp(-sdf(vLocal) / uGlow, 0.0, 1.0); m = g * g; } if (uEdge > 0.0) { float d = sdf(vLocal); float w = fwidth(d) + 1e-6; m = (1.0 - smoothstep(-w, w, d)) * smoothstep(-uEdge - w, -uEdge + w, d); } if (uRing > 0.0) { float r = length(vLocal) * 2.0; m = smoothstep(uRing - 0.12, uRing, r) * (1.0 - smoothstep(0.88, 1.0, r)); } o = uTint * m; }")
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
        // A picture three times as wide as it is high (3840x1080) is the glasses' full-width 3D: the left half goes to
        // the left eye and the right half to the right, so one view across it would give each eye half a scene.
        val two = (stereo || width >= height * 3) && !preview
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
        eyeCapture?.let { out -> if (frames % 15 == 0L) { val req = java.io.File(out.parentFile, "eye_capture.req"); if (req.exists()) { req.delete(); captureEye(f, q, p, out) } } }
        GLES30.glDisable(GLES30.GL_BLEND)
        avgCpuMs = avgCpuMs * 0.95 + (System.nanoTime() - now) / 1e6 * 0.05
    }

    /** The scene once more, as one eye of the glasses gets it, into a picture of its own and from there into [out]. */
    private fun captureEye(f: RenderFrame, q: DoubleArray, p: DoubleArray, out: java.io.File) {
        val w = 1920; val h = 1080; val ids = IntArray(1)
        try {
            GLES30.glGenTextures(1, ids, 0); val t = ids[0]; GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR); GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glGenFramebuffers(1, ids, 0); val fb = ids[0]; GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fb)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, t, 0)
            GLES30.glViewport(0, 0, w, h); GLES30.glClearColor(0f, 0f, 0f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            val tanH = Tracking.displayTanH; val tanV = Tracking.displayTanV
            Q.toMatrix(q, p, headM); Matrix.invertM(view, 0, headM, 0)
            Matrix.frustumM(proj, 0, (-tanH * NEAR).toFloat(), (tanH * NEAR).toFloat(), (-tanV * NEAR).toFloat(), (tanV * NEAR).toFloat(), NEAR.toFloat(), 80f)
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0); System.arraycopy(proj, 0, projHead, 0, 16)
            boundProgram = 0; drawScene(f, q)
            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0); GLES30.glDeleteFramebuffers(1, intArrayOf(fb), 0); GLES30.glDeleteTextures(1, intArrayOf(t), 0)
            val up = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); buf.position(0); up.copyPixelsFromBuffer(buf)
            val m = android.graphics.Matrix().apply { postScale(1f, -1f) }      // GL's first row is the bottom one
            val b = Bitmap.createBitmap(up, 0, 0, w, h, m, false)
            val tmp = java.io.File(out.parentFile, out.name + ".tmp")
            tmp.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; tmp.renameTo(out); up.recycle(); b.recycle()
        } catch (e: Throwable) { Tracking.noteGraphics("eye capture failed: $e"); GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0) }
    }

    /** Everything in the scene, once, with the current [vp] (world) and [projHead] (head-fixed) matrices. */
    private fun drawScene(f: RenderFrame, q: DoubleArray) {
        bright = if (preview) 1f else f.brightness
        // Far things first, so nearer ones cover them; the focused window last among equals. The shelf has its place
        // in that order: it is nearer than a window at the usual distance.
        var shelfDrawn = f.shelf == null
        for (w in f.windows.sortedWith(compareByDescending<RenderWindow> { it.distance }.thenBy { it.focused })) {
            if (!shelfDrawn && !w.headLocked && w.distance < Shelf.DISTANCE) { drawShelf(f); shelfDrawn = true }
            drawWindow(f, w)
        }
        if (!shelfDrawn) drawShelf(f)
        if (f.dwellProgress > 0.0) {   // look-to-press: a ring that closes as the hold completes
            val size = if (f.dwellSize > 0.0) f.dwellSize else f.dwellDistance * 0.05
            Geometry.modelMatrix(Geometry.centre(f.dwellAnchor ?: f.anchor, f.dwellYaw, f.dwellPitch, f.dwellDistance * 0.985), f.dwellYaw, f.dwellPitch, size, size, model)
            draw(progFlat, 0, 0, vp, ident, size, size, size / 2, Ink.AMBER, 1f, ring = (0.92 - 0.9 * f.dwellProgress).toFloat().coerceIn(0.02f, 0.92f))
        }
        f.hand?.let { drawHand(it) }
    }

    /**
     * The pointing hand: a dot of light on the tip of the thumb and of the index finger (they lie on the real fingers,
     * and say that the hand is seen), a short ray from the palm that fades, and a ring where the ray lands. Pinching,
     * the dots and the ring turn violet and the ring fills.
     */
    private fun drawHand(h: RenderHand) {
        val rgb = if (h.pinching) Ink.AMBER else Ink.WHITE
        for (tip in arrayOf(h.thumb, h.index)) {
            val d = doubleArrayOf(tip[0] - h.head[0], tip[1] - h.head[1], tip[2] - h.head[2]); val yaw = Geometry.yawOf(d); val pitch = Geometry.pitchOf(d)
            val halo = 0.036; Geometry.modelMatrix(tip, yaw, pitch, halo, halo, model); draw(progFlat, 0, 0, vp, ident, halo, halo, halo / 2, rgb, 0.5f, glow = (halo / 2).toFloat())
            val dot = 0.010; Geometry.modelMatrix(tip, yaw, pitch, dot, dot, model); draw(progFlat, 0, 0, vp, ident, dot, dot, dot / 2, rgb, 0.95f)
        }
        val end = if (h.reach > RAY_FROM) minOf(h.reach, RAY_TO) else RAY_TO
        val parts = 12; val thick = 0.0022
        for (i in 0 until parts) {
            val a = RAY_FROM + (end - RAY_FROM) * i / parts; val b = RAY_FROM + (end - RAY_FROM) * (i + 1) / parts; val m = (a + b) / 2; val len = b - a
            val c = doubleArrayOf(h.from[0] + h.dir[0] * m, h.from[1] + h.dir[1] * m, h.from[2] + h.dir[2] * m)
            // a strip along the ray, turned to face the head
            val v = doubleArrayOf(c[0] - h.head[0], c[1] - h.head[1], c[2] - h.head[2])
            var sx = h.dir[1] * v[2] - h.dir[2] * v[1]; var sy = h.dir[2] * v[0] - h.dir[0] * v[2]; var sz = h.dir[0] * v[1] - h.dir[1] * v[0]
            val sn = Math.sqrt(sx * sx + sy * sy + sz * sz); if (sn < 1e-6) continue
            sx /= sn; sy /= sn; sz /= sn
            val nx = h.dir[1] * sz - h.dir[2] * sy; val ny = h.dir[2] * sx - h.dir[0] * sz; val nz = h.dir[0] * sy - h.dir[1] * sx
            model[0] = (h.dir[0] * len).toFloat(); model[1] = (h.dir[1] * len).toFloat(); model[2] = (h.dir[2] * len).toFloat(); model[3] = 0f
            model[4] = (sx * thick).toFloat(); model[5] = (sy * thick).toFloat(); model[6] = (sz * thick).toFloat(); model[7] = 0f
            model[8] = nx.toFloat(); model[9] = ny.toFloat(); model[10] = nz.toFloat(); model[11] = 0f
            model[12] = c[0].toFloat(); model[13] = c[1].toFloat(); model[14] = c[2].toFloat(); model[15] = 1f
            draw(progFlat, 0, 0, vp, ident, len, thick, 0.0, rgb, 0.7f * (1f - i / parts.toFloat()) * (1f - i / parts.toFloat()))
        }
        if (h.reach > 0.0) {
            val t = h.reach * 0.985
            val c = doubleArrayOf(h.from[0] + h.dir[0] * t, h.from[1] + h.dir[1] * t, h.from[2] + h.dir[2] * t)
            val far = Math.sqrt((c[0] - h.head[0]) * (c[0] - h.head[0]) + (c[1] - h.head[1]) * (c[1] - h.head[1]) + (c[2] - h.head[2]) * (c[2] - h.head[2]))
            val size = (far * 0.02).coerceAtLeast(0.012)
            Geometry.modelMatrix(c, h.yaw, h.pitch, size, size, model)
            draw(progFlat, 0, 0, vp, ident, size, size, size / 2, Ink.AMBER, 1f, ring = if (h.pinching) 0.01f else 0.55f)
        }
    }

    /** The status line at the top of the view, the row of marks, and the name under the one in focus: each faces the wearer from its place on the arc. */
    private fun drawShelf(f: RenderFrame) {
        val s = f.shelf ?: return
        if (s.panel != null) {      // the home panel, a little larger while it arrives, and the light round its tile in focus
            val c = Geometry.centre(f.anchor, s.yaw, Home.PITCH, Home.DISTANCE)
            sprite(s.panel, c, s.yaw, Home.PITCH, Home.HEIGHT * (1.04 - 0.04 * s.panelAlpha), s.panelAlpha, 0, vp)
            s.ring?.let { r -> if (!r.bitmap.isRecycled) { Geometry.modelMatrix(offset(c, s.yaw, Home.PITCH, s.ringX, s.ringY), s.yaw, Home.PITCH, s.ringW, s.ringH, model); draw(prog2d, GLES30.GL_TEXTURE_2D, texture(r), vp, ident, s.ringW, s.ringH, 0.0, WHITE, s.panelAlpha) } }
            s.status?.let { sprite(it, Geometry.centre(f.anchor, s.yaw, Shelf.STATUS_PITCH, Shelf.DISTANCE), s.yaw, Shelf.STATUS_PITCH, Sight.STRIP * Sight.metres(Sight.BODY, Shelf.DISTANCE), 1f, 0, vp) }
            return
        }
        // the dock: a pane of glass behind the row of marks
        if (s.marks.isNotEmpty()) {
            val dw = 2 * Shelf.DISTANCE * Math.tan(Shelf.SPACING * (s.marks.size - 1) / 2 + Shelf.MARK_ANGLE * 2.2); val dh = 2 * Shelf.DISTANCE * Math.tan(Shelf.MARK_ANGLE * Shelf.DOCK)
            Geometry.modelMatrix(Geometry.centre(f.anchor, s.yaw, Shelf.PITCH, Shelf.DISTANCE * 1.02), s.yaw, Shelf.PITCH, dw, dh, model)
            draw(progFlat, 0, 0, vp, ident, dw, dh, dh / 2, PANE, 0.30f)
            draw(progFlat, 0, 0, vp, ident, dw, dh, dh / 2, WHITE, 0.30f, edge = Sight.metres(Math.toRadians(0.045), Shelf.DISTANCE).toFloat())
        }
        s.status?.let { sprite(it, Geometry.centre(f.anchor, s.yaw, Shelf.STATUS_PITCH, Shelf.DISTANCE), s.yaw, Shelf.STATUS_PITCH, Sight.STRIP * Sight.metres(Sight.BODY, Shelf.DISTANCE), 1f, 0, vp) }
        for (m in s.marks) {
            // a mark's picture has room round the mark for its halo: it is half as large again as the mark
            sprite(m.sprite, Geometry.centre(f.anchor, m.yaw, Shelf.PITCH, Shelf.DISTANCE), m.yaw, Shelf.PITCH, 3 * Shelf.MARK_RADIUS * m.scale, m.alpha, 0, vp)
        }
        s.name?.let { sprite(it, Geometry.centre(f.anchor, s.nameYaw, Shelf.NAME_PITCH, Shelf.DISTANCE), s.nameYaw, Shelf.NAME_PITCH, Sight.STRIP * Sight.metres(Sight.NAME, Shelf.DISTANCE), 1f, 0, vp) }
        val dot = Sight.metres(Math.toRadians(0.3), Shelf.DISTANCE)
        for (yaw in doubleArrayOf(s.moreLeft, s.moreRight)) if (!yaw.isNaN()) {      // the row goes on this way
            Geometry.modelMatrix(Geometry.centre(f.anchor, yaw, Shelf.PITCH, Shelf.DISTANCE), yaw, Shelf.PITCH, dot, dot, model)
            draw(progFlat, 0, 0, vp, ident, dot, dot, dot / 2, Ink.SLATE, 1f)
        }
    }

    /**
     * A sprite [height] metres high (as wide as its picture makes it) in a window-like plane at [c]. [align] says what
     * of it is at [c]: 0 its middle, -1 the middle of its left edge.
     */
    private fun sprite(s: Sprite, c: DoubleArray, yaw: Double, pitch: Double, height: Double, alpha: Float, align: Int, matrix: FloatArray) {
        if (s.bitmap.isRecycled || alpha <= 0f) return
        val w = height * s.bitmap.width / s.bitmap.height
        Geometry.modelMatrix(if (align < 0) offset(c, yaw, pitch, w / 2, 0.0) else c, yaw, pitch, w, height, model)
        draw(prog2d, GLES30.GL_TEXTURE_2D, texture(s), matrix, ident, w, height, 0.0, WHITE, alpha)
    }

    /** The texture a sprite's picture is in: made when the sprite is first drawn, kept while it goes on being drawn. */
    private fun texture(s: Sprite): Int {
        sprites[s.key]?.let { return it }
        val t = newTexture2d(); upload(t, s.bitmap)
        if (s.key.startsWith("panel|")) { val old = sprites.entries.iterator(); while (old.hasNext()) { val e = old.next(); if (e.key.startsWith("panel|")) { GLES30.glDeleteTextures(1, intArrayOf(e.value), 0); old.remove() } } }
        sprites[s.key] = t
        if (sprites.size > MAX_SPRITES) { val e = sprites.entries.iterator(); val old = e.next(); e.remove(); GLES30.glDeleteTextures(1, intArrayOf(old.value), 0) }
        return t
    }

    private fun drawWindow(f: RenderFrame, w: RenderWindow) {
        val t = tex[w.id] ?: return
        val matrix = if (w.headLocked) projHead else vp
        val c = if (w.headLocked) doubleArrayOf(0.0, 0.0, -w.distance) else Geometry.centre(f.anchors[w.id] ?: f.anchor, w.yaw, w.pitch, w.distance)
        val yaw = if (w.headLocked) 0.0 else w.yaw; val pitch = if (w.headLocked) 0.0 else w.pitch
        val shade = if (w.focused) 1f else f.dim
        val hair = Sight.metres(Sight.LINE, w.distance)
        // The page is the only filled thing. Before it has drawn itself there is nothing there, only its brackets; on
        // its way out of its mark it is a hairline that grows, so that there is something to see growing.
        val round = if (w.headLocked) 0.0 else Sight.metres(Sight.CORNER, w.distance)
        if (!w.headLocked && !w.growing && w.glow > 0.02f) {      // the window in focus stands in its own light
            val halo = Sight.metres(Sight.HALO, w.distance)
            Geometry.modelMatrix(c, yaw, pitch, w.width + 2 * halo, w.height + 2 * halo, model)
            draw(progFlat, 0, 0, matrix, ident, w.width + 2 * halo, w.height + 2 * halo, round + halo, Ink.AMBER, 0.55f * w.glow, glow = halo.toFloat())
        }
        if (t.hasFrame) { Geometry.modelMatrix(c, yaw, pitch, w.width, w.height, model); shadeRgb[0] = shade; shadeRgb[1] = shade; shadeRgb[2] = shade; draw(progOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t.oes, matrix, t.m, w.width, w.height, round, shadeRgb, 1f) }
        else if (w.growing) for (side in 0 until 4) {
            val across = side < 2; val sign = if (side % 2 == 0) 1.0 else -1.0
            line(c, yaw, pitch, if (across) 0.0 else sign * w.width / 2, if (across) sign * w.height / 2 else 0.0, if (across) w.width else hair, if (across) hair else w.height, Ink.SLATE, 1f, 0.0, matrix)
        }
        if (w.headLocked) { cursor(w, c, yaw, pitch, matrix); return }
        if (w.bracket > 0f && !w.growing) {      // the rim of the pane: faint at rest, white while the focus is on the window, violet when it is on the window itself
            val g = w.glow.coerceIn(0f, 1f); val rim = Sight.metres(Sight.LINE + (Sight.LINE_LIT - Sight.LINE) * g, w.distance)
            val base = if (w.near) Ink.WHITE else Ink.SLATE
            for (i in 0 until 3) lineRgb[i] = base[i] + (Ink.AMBER[i] - base[i]) * g
            Geometry.modelMatrix(c, yaw, pitch, w.width + 2 * rim, w.height + 2 * rim, model)
            draw(progFlat, 0, 0, matrix, ident, w.width + 2 * rim, w.height + 2 * rim, round + rim, lineRgb, (if (w.near) 0.9f else 0.6f) * w.bracket, edge = rim.toFloat())
        }
        if (w.corners) brackets(w, c, yaw, pitch, matrix)      // while it is being sized: its corners
        val gap = Sight.metres(Sight.BRACKET_GAP, w.distance); val lit = Sight.metres(Sight.LINE_LIT, w.distance)
        w.title?.let {      // above the top-left bracket, beginning where the bracket begins
            val h = Sight.STRIP * Sight.metres(Sight.BODY, w.distance)
            sprite(it, offset(c, yaw, pitch, -(w.width / 2 + gap + lit), w.height / 2 + gap + lit + h * Sight.TITLE_RISE), yaw, pitch, h, 1f, -1, matrix)
        }
        w.handle?.let {      // the handle: move, size, pin, close, under the window in focus
            Geometry.modelMatrix(offset(c, yaw, pitch, 0.0, -(w.height / 2 + w.handleGap + w.handleHeight / 2)), yaw, pitch, w.handleWidth, w.handleHeight, model)
            draw(prog2d, GLES30.GL_TEXTURE_2D, texture(it), matrix, ident, w.handleWidth, w.handleHeight, 0.0, WHITE, 1f)
        }
        cursor(w, c, yaw, pitch, matrix)
    }

    private fun cursor(w: RenderWindow, c: DoubleArray, yaw: Double, pitch: Double, matrix: FloatArray) {
        if (!w.showCursor) return
        val size = (w.distance * 0.018).coerceAtLeast(0.012)
        Geometry.modelMatrix(offset(c, yaw, pitch, (w.cursorU - 0.5) * w.width, (0.5 - w.cursorV) * w.height), yaw, pitch, size, size, model)
        draw(progFlat, 0, 0, matrix, ident, size, size, size / 2, Ink.AMBER, 1f, ring = if (w.pressed) 0.01f else 0.6f)
    }

    /**
     * A window has no frame: a bracket at each corner, just outside the page. Slate at rest, Chalk while the focus is
     * somewhere on the window, and when it is on the window itself Lamp, thicker, with a soft halo: the one thing
     * that glows. The arms grow out of the corner as the brackets draw themselves.
     */
    private fun brackets(w: RenderWindow, c: DoubleArray, yaw: Double, pitch: Double, matrix: FloatArray) {
        val g = w.glow.coerceIn(0f, 1f)
        val t = Sight.metres(Sight.LINE + (Sight.LINE_LIT - Sight.LINE) * g, w.distance)
        val gap = Sight.metres(Sight.BRACKET_GAP, w.distance)
        val base = if (w.near) Ink.WHITE else Ink.SLATE
        for (i in 0 until 3) lineRgb[i] = base[i] + (Ink.AMBER[i] - base[i]) * g
        val len = (w.arm * w.bracket).coerceAtLeast(t)
        val ex = w.width / 2 + gap + t; val ey = w.height / 2 + gap + t      // the bracket's outer corner
        val halo = t * 2.5
        for (pass in 0 until 2) {
            if (pass == 0 && g < 0.02f) continue
            for (sx in SIGNS) for (sy in SIGNS) {
                if (pass == 0) {
                    line(c, yaw, pitch, sx * (ex - len / 2), sy * (ey - t / 2), len + 2 * halo, t + 2 * halo, Ink.AMBER, 0.4f * g, halo, matrix)
                    line(c, yaw, pitch, sx * (ex - t / 2), sy * (ey - len / 2), t + 2 * halo, len + 2 * halo, Ink.AMBER, 0.4f * g, halo, matrix)
                } else {
                    line(c, yaw, pitch, sx * (ex - len / 2), sy * (ey - t / 2), len, t, lineRgb, 1f, 0.0, matrix)
                    line(c, yaw, pitch, sx * (ex - t / 2), sy * (ey - len / 2), t, len, lineRgb, 1f, 0.0, matrix)
                }
            }
        }
    }

    /** A line with round ends, [lw] by [lh] metres, its middle [x] to the right of and [y] above [c] in the window's plane. With [glow] it is a halo that fades over that width. */
    private fun line(c: DoubleArray, yaw: Double, pitch: Double, x: Double, y: Double, lw: Double, lh: Double, rgb: FloatArray, a: Float, glow: Double, matrix: FloatArray) {
        Geometry.modelMatrix(offset(c, yaw, pitch, x, y), yaw, pitch, lw, lh, model)
        draw(progFlat, 0, 0, matrix, ident, lw, lh, minOf(lw, lh) / 2, rgb, a, glow = glow.toFloat())
    }

    /** [c] moved by [x] to the window's right and [y] up, in the window's own plane. */
    private fun offset(c: DoubleArray, yaw: Double, pitch: Double, x: Double, y: Double): DoubleArray {
        val cy = Math.cos(yaw); val sy = Math.sin(yaw); val cp = Math.cos(pitch); val sp = Math.sin(pitch)
        return doubleArrayOf(c[0] + cy * x + sy * sp * y, c[1] + cp * y, c[2] - sy * x + cy * sp * y)
    }

    private fun draw(prog: Int, target: Int, texture: Int, viewProj: FloatArray, texM: FloatArray, w: Double, h: Double, radius: Double, rgb: FloatArray, a: Float, ring: Float = 0f, glow: Float = 0f, edge: Float = 0f) {
        // uniform locations are looked up once per program, not on every draw
        val u = uniforms.getOrPut(prog) { IntArray(UNIFORM_NAMES.size) { GLES30.glGetUniformLocation(prog, UNIFORM_NAMES[it]) } }
        if (boundProgram != prog) { GLES30.glUseProgram(prog); boundProgram = prog }
        Matrix.multiplyMM(mvp, 0, viewProj, 0, model, 0)
        GLES30.glUniformMatrix4fv(u[0], 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(u[1], 1, false, texM, 0)
        GLES30.glUniform2f(u[2], w.toFloat(), h.toFloat())
        GLES30.glUniform1f(u[3], radius.coerceAtMost(minOf(w, h) / 2).toFloat())
        // light is what is drawn, so the picture's brightness scales the colour and leaves alone how much it covers
        val k = a * bright
        GLES30.glUniform4f(u[4], rgb[0] * k, rgb[1] * k, rgb[2] * k, a)
        if (prog == progFlat) { GLES30.glUniform1f(u[5], ring); GLES30.glUniform1f(u[6], glow); GLES30.glUniform1f(u[8], edge) }
        if (texture != 0) { GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(target, texture); GLES30.glUniform1i(u[7], 0) }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun syncTextures(f: RenderFrame) {
        val wanted = f.windows.map { it.id }.toSet()
        val gone = tex.keys.filter { it !in wanted }
        for (id in gone) { val t = tex.remove(id) ?: continue; onSurface(id, null); t.surface.release(); t.st.release(); GLES30.glDeleteTextures(1, intArrayOf(t.oes), 0) }
        for (w in f.windows) {
            var t = tex[w.id]
            if (t == null) {
                val ids = IntArray(1); GLES30.glGenTextures(1, ids, 0)
                GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                val st = SurfaceTexture(ids[0])
                val nt = Tex(ids[0], st, Surface(st))
                st.setOnFrameAvailableListener { nt.dirty = true }
                tex[w.id] = nt; t = nt
                onSurface(w.id, nt.surface)
            }
            // the picture's size follows the window's shape: 1280 x 720 lying, 720 x 1280 upright, or what a free sizing gave it
            if (t.pw != w.pixelW || t.ph != w.pixelH) { t.pw = w.pixelW; t.ph = w.pixelH; t.st.setDefaultBufferSize(w.pixelW, w.pixelH) }
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

    private val shadeRgb = FloatArray(3); private val lineRgb = FloatArray(3)

    companion object {
        private const val NEAR = 0.08
        /** The hand's ray is drawn from this far in front of the palm to this far, metres: it says which way the hand points, and the ring says where it lands. */
        private const val RAY_FROM = 0.10; private const val RAY_TO = 0.55
        private val UNIFORM_NAMES = arrayOf("uMvp", "uTex", "uSize", "uRadius", "uTint", "uRing", "uGlow", "uSampler", "uEdge")
        /** The cool veil a pane of glass is ([Glass.PANE]), as light. */
        private val PANE = floatArrayOf(0.55f, 0.59f, 0.78f)
        private val WHITE = floatArrayOf(1f, 1f, 1f)
        private val SIGNS = doubleArrayOf(-1.0, 1.0)
        private const val MAX_SPRITES = 96
    }
}
