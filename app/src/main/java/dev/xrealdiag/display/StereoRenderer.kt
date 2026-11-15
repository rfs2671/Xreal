package dev.xrealdiag.display

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Side-by-side stereo test scene. Left half = left eye (red corner marker), right half =
 * right eye (blue corner marker). Squares float at 1-8 m with per-eye parallax, so with a
 * working 3840x1080 SBS mode they appear at different depths and each eye sees one marker.
 */
class StereoRenderer : GLSurfaceView.Renderer {
    @Volatile var width = 0; private set
    @Volatile var height = 0; private set
    @Volatile var frames = 0L; private set
    @Volatile var lateFrames = 0L; private set
    @Volatile var maxIntervalNs = 0L; private set
    @Volatile var firstFrameNs = 0L; private set
    @Volatile var lastFrameNs = 0L; private set
    /** Expected frame period; frames taking over 1.5x this count as late. */
    @Volatile var periodNs = 16_666_667L

    private var program = 0
    private var aPos = 0
    private var uMvp = 0
    private var uColor = 0
    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f)); position(0) }
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val rot = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val tmp = FloatArray(16)

    fun resetStats() { frames = 0; lateFrames = 0; maxIntervalNs = 0; firstFrameNs = 0; lastFrameNs = 0 }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val vs = shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 aPos; uniform mat4 uMvp; void main(){ gl_Position = uMvp * vec4(aPos, 0.0, 1.0); }")
        val fs = shader(GLES20.GL_FRAGMENT_SHADER, "precision mediump float; uniform vec4 uColor; void main(){ gl_FragColor = uColor; }")
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs); GLES20.glAttachShader(program, fs); GLES20.glLinkProgram(program)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type); GLES20.glShaderSource(s, src); GLES20.glCompileShader(s); return s
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { width = w; height = h }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        if (frames == 0L) firstFrameNs = now else {
            val dt = now - lastFrameNs
            if (dt > maxIntervalNs) maxIntervalNs = dt
            if (dt > periodNs * 3 / 2) lateFrames++
        }
        lastFrameNs = now; frames++

        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0.02f, 0.02f, 0.03f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)

        val half = width / 2
        HeadPose.viewRotation(rot)
        // ~46 degree diagonal FOV class display: use 25 degrees vertical.
        Matrix.perspectiveM(proj, 0, 25f, half.toFloat() / height.toFloat(), 0.1f, 100f)
        val t = (now % 8_000_000_000L) / 8e9f
        for (eye in 0..1) {
            GLES20.glViewport(eye * half, 0, half, height)
            val ipdHalf = if (eye == 0) 0.032f else -0.032f
            Matrix.setIdentityM(tmp, 0)
            Matrix.translateM(tmp, 0, ipdHalf, 0f, 0f)
            Matrix.multiplyMM(view, 0, tmp, 0, rot, 0)
            // Ring of squares all around, three depth shells.
            for (shell in 0..2) {
                val dist = 1.5f + shell * 3f
                for (i in 0 until 12) {
                    val ang = i * 30f + shell * 10f + t * 360f * (if (shell == 1) 1 else 0)
                    Matrix.setIdentityM(model, 0)
                    Matrix.rotateM(model, 0, ang, 0f, 1f, 0f)
                    Matrix.translateM(model, 0, 0f, (shell - 1) * 0.35f, -dist)
                    Matrix.scaleM(model, 0, 0.12f * dist, 0.12f * dist, 1f)
                    draw(0.25f + 0.25f * shell, 0.9f - 0.3f * shell, 0.35f + 0.05f * (i % 4), 1f)
                }
            }
            // Eye marker in screen space: left eye red top-left, right eye blue top-right.
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            Matrix.setIdentityM(mvp, 0)
            Matrix.translateM(mvp, 0, if (eye == 0) -0.8f else 0.8f, 0.7f, 0f)
            Matrix.scaleM(mvp, 0, 0.2f, 0.3f, 1f)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            if (eye == 0) GLES20.glUniform4f(uColor, 1f, 0.1f, 0.1f, 1f) else GLES20.glUniform4f(uColor, 0.1f, 0.3f, 1f, 1f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        }
    }

    private fun draw(r: Float, g: Float, b: Float, a: Float) {
        Matrix.multiplyMM(tmp, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform4f(uColor, r, g, b, a)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
}
