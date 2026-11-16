package dev.ultraxr.render

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import dev.ultraxr.core.HeadTracker
import dev.ultraxr.core.Pose
import dev.ultraxr.core.Q
import dev.ultraxr.tracking.Tracking
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Something to show in the glasses. Updated once per frame, drawn once per eye. */
interface Scene {
    fun update(dt: Double, head: HeadTracker.State)
    fun draw(d: Draw, head: HeadTracker.State)
}

/**
 * Renders a [Scene] in side-by-side stereo from the predicted head pose.
 * In preview mode (no glasses display) it renders a single wider view instead.
 */
class XrRenderer(@Volatile var scene: Scene, private val stereo: Boolean, private val preview: Boolean = !stereo) : GLSurfaceView.Renderer {
    @Volatile var width = 0; private set
    @Volatile var height = 0; private set
    @Volatile var frames = 0L; private set
    @Volatile var lateFrames = 0L; private set
    @Volatile var maxIntervalNs = 0L; private set
    @Volatile var firstFrameNs = 0L; private set
    @Volatile var lastFrameNs = 0L; private set
    @Volatile var periodNs = 16_666_667L
    @Volatile var avgCpuMs = 0.0; private set
    @Volatile var glError = ""; private set
    @Volatile var glInfo = ""; private set
    private var firstFrameNoted = false
    private var lastGlErr = 0

    private val draw = Draw()
    private val proj = FloatArray(16); private val view = FloatArray(16); private val vp = FloatArray(16)
    private val headM = FloatArray(16); private val headRot = FloatArray(16); private val eyeM = FloatArray(16)
    private val eyePos = FloatArray(3)

    fun resetStats() { frames = 0; lateFrames = 0; maxIntervalNs = 0; firstFrameNs = 0 }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            draw.init()
            val err = GLES30.glGetError()
            glInfo = "${GLES30.glGetString(GLES30.GL_RENDERER)} / ${GLES30.glGetString(GLES30.GL_VERSION)} / ${GLES30.glGetString(GLES30.GL_VENDOR)}"
            Tracking.noteGraphics("GL context created (" + (if (preview) "phone preview" else if (stereo) "glasses, two eyes" else "glasses, one view") + "): $glInfo" + if (err != GLES30.GL_NO_ERROR) ", glGetError=0x" + Integer.toHexString(err) else "")
        } catch (e: Throwable) { glError = e.toString(); Tracking.noteGraphics("GL init FAILED: " + dev.ultraxr.core.ReportExport.stackTrace(e).take(600)) }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { width = w; height = h; Tracking.noteGraphics("GL surface is ${w}x$h") }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (frames == 0L) { firstFrameNs = now; 0.0 } else {
            val d = now - lastFrameNs
            if (d > maxIntervalNs) maxIntervalNs = d
            if (d > periodNs * 3 / 2) lateFrames++
            (d * 1e-9).coerceAtMost(0.1)
        }
        lastFrameNs = now; frames++
        if (glError.isNotEmpty()) { GLES30.glClearColor(0.2f, 0f, 0f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT); return }

        // Predict to the middle of the frame that this render will be scanned out in.
        val head = Tracking.head.predict(now + periodNs * 3 / 2)
        val sc = scene
        sc.update(dt, head)

        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 1f) // black is transparent on see-through glasses
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        Q.toMatrix(head.pose.q, head.pose.p, headM)
        Q.toMatrix(head.pose.q, ZERO, headRot)

        val s = Tracking.settings
        val scale = s.fovScale.toDouble()
        if (stereo) {
            val half = width / 2
            val ipd = s.ipdMm / 1000.0
            // The glasses' optics converge at their virtual screen distance: each panel is seen turned
            // towards the nose by this tangent, so each eye's frustum turns the same way (as Pocket XR's
            // Monado driver does) and a point at infinity is seen straight ahead.
            val shift = (ipd / 2) / VIRTUAL_SCREEN_M
            for (e in 0..1) {
                frustum(Tracking.displayTanH * scale, Tracking.displayTanV * scale, if (e == 0) shift else -shift)
                GLES30.glViewport(e * half, 0, half, height)
                draw.eyeIndex = e
                renderEye(sc, head, if (e == 0) -ipd / 2 else ipd / 2)
            }
        } else if (!preview) {
            // Glasses in a 2D mode (both eyes get the same picture): one view at the display's real FOV.
            frustum(Tracking.displayTanH * scale, Tracking.displayTanV * scale)
            draw.eyeIndex = -1
            renderEye(sc, head, 0.0)
        } else {
            // Phone preview: one view, widened so the scene is visible without turning.
            val tv = 0.6; val th = tv * width / height.coerceAtLeast(1)
            frustum(th, tv)
            draw.eyeIndex = -1
            renderEye(sc, head, 0.0)
        }
        if (!firstFrameNoted) { firstFrameNoted = true; Tracking.noteGraphics("first frame drawn to a ${width}x$height surface") }
        if (frames % 300 == 5L) {
            val e = GLES30.glGetError()
            if (e != GLES30.GL_NO_ERROR && e != lastGlErr) { lastGlErr = e; Tracking.noteGraphics("glGetError=0x" + Integer.toHexString(e) + " while drawing") }
        }
        avgCpuMs = avgCpuMs * 0.95 + (System.nanoTime() - now) / 1e6 * 0.05
    }

    private fun frustum(tanH: Double, tanV: Double, shift: Double = 0.0) {
        Matrix.frustumM(proj, 0, ((shift - tanH) * NEAR).toFloat(), ((shift + tanH) * NEAR).toFloat(), (-tanV * NEAR).toFloat(), (tanV * NEAR).toFloat(), NEAR.toFloat(), 60f)
    }

    private fun renderEye(sc: Scene, head: HeadTracker.State, offsetX: Double) {
        val eye = head.pose * Pose(doubleArrayOf(offsetX, 0.0, 0.0))
        Q.toMatrix(eye.q, eye.p, eyeM)
        Matrix.invertM(view, 0, eyeM, 0)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        eyePos[0] = eye.p[0].toFloat(); eyePos[1] = eye.p[1].toFloat(); eyePos[2] = eye.p[2].toFloat()
        draw.begin(vp, eyePos, headRot)
        sc.draw(draw, head)
    }

    companion object {
        private const val NEAR = 0.08
        /** Distance at which the glasses show a flat picture in 3D mode (Pocket XR's value for XREAL glasses). */
        const val VIRTUAL_SCREEN_M = 4.0
        private val ZERO = doubleArrayOf(0.0, 0.0, 0.0)
    }
}
