package dev.ultraxr.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Small immediate-style 3D drawing layer over GLES 3: spheres, rings, tubes, lines and text. */
class Draw {
    private class Mesh(val vao: Int, val count: Int, val mode: Int)

    private var program = 0
    private var uMvp = 0; private var uModel = 0; private var uColor = 0; private var uEye = 0; private var uMode = 0; private var uTex = 0
    private lateinit var sphere: Mesh
    private lateinit var torus: Mesh
    private lateinit var tube: Mesh
    private lateinit var quad: Mesh
    private var lineVao = 0; private var lineVbo = 0
    private val lineBuf: FloatBuffer = ByteBuffer.allocateDirect(4096 * 6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var lineCount = 0
    private val texts = LinkedHashMap<String, IntArray>(32, 0.75f, true) // text -> tex, w, h

    /** 0 = left eye, 1 = right eye, -1 = mono preview. */
    var eyeIndex = -1

    private val vp = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val eye = FloatArray(3)
    private val headRot = FloatArray(16)

    fun init() {
        val vs = """#version 300 es
            layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNrm; layout(location=2) in vec2 aUv;
            uniform mat4 uMvp; uniform mat4 uModel;
            out vec3 vN; out vec3 vW; out vec2 vUv;
            void main(){ vec4 w = uModel * vec4(aPos,1.0); vW = w.xyz; vN = mat3(uModel) * aNrm; vUv = aUv; gl_Position = uMvp * vec4(aPos,1.0); }"""
        val fs = """#version 300 es
            precision mediump float;
            in vec3 vN; in vec3 vW; in vec2 vUv;
            uniform vec4 uColor; uniform vec3 uEye; uniform int uMode; uniform sampler2D uTex;
            out vec4 o;
            void main(){
              if (uMode == 0) { o = uColor; }
              else if (uMode == 1) {
                vec3 n = normalize(vN); vec3 v = normalize(uEye - vW);
                float f = 1.0 - abs(dot(n, v));
                float light = 0.45 + 0.55 * max(dot(n, normalize(vec3(0.3, 1.0, 0.4))), 0.0);
                o = vec4(uColor.rgb * light + uColor.rgb * 1.4 * f * f + vec3(0.9) * pow(f, 5.0), uColor.a);
              } else { vec4 t = texture(uTex, vUv); o = vec4(uColor.rgb, uColor.a * t.a); }
            }"""
        program = GLES30.glCreateProgram()
        for ((type, src) in listOf(GLES30.GL_VERTEX_SHADER to vs, GLES30.GL_FRAGMENT_SHADER to fs)) {
            val s = GLES30.glCreateShader(type); GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
            val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw RuntimeException("shader: " + GLES30.glGetShaderInfoLog(s))
            GLES30.glAttachShader(program, s)
        }
        GLES30.glLinkProgram(program)
        uMvp = GLES30.glGetUniformLocation(program, "uMvp"); uModel = GLES30.glGetUniformLocation(program, "uModel")
        uColor = GLES30.glGetUniformLocation(program, "uColor"); uEye = GLES30.glGetUniformLocation(program, "uEye")
        uMode = GLES30.glGetUniformLocation(program, "uMode"); uTex = GLES30.glGetUniformLocation(program, "uTex")
        sphere = buildSphere(14, 20); torus = buildTorus(40, 10, 0.07f); tube = buildTube(8)
        quad = mesh(floatArrayOf(-0.5f, -0.5f, 0f, 0f, 0f, 1f, 0f, 1f, 0.5f, -0.5f, 0f, 0f, 0f, 1f, 1f, 1f, -0.5f, 0.5f, 0f, 0f, 0f, 1f, 0f, 0f, 0.5f, 0.5f, 0f, 0f, 0f, 1f, 1f, 0f), GLES30.GL_TRIANGLE_STRIP)
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0); lineVao = ids[0]
        GLES30.glGenBuffers(1, ids, 0); lineVbo = ids[0]
        GLES30.glBindVertexArray(lineVao); GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, lineBuf.capacity() * 4, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glBindVertexArray(0)
        texts.clear()
    }

    private fun mesh(v: FloatArray, mode: Int): Mesh {
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0); val vao = ids[0]
        GLES30.glGenBuffers(1, ids, 0)
        GLES30.glBindVertexArray(vao); GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
        val fb = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, v.size * 4, fb, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 32, 0)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 32, 12)
        GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, 32, 24)
        GLES30.glBindVertexArray(0)
        return Mesh(vao, v.size / 8, mode)
    }

    private fun buildSphere(stacks: Int, slices: Int): Mesh {
        val v = ArrayList<Float>()
        fun p(i: Int, j: Int) {
            val th = Math.PI * i / stacks; val ph = 2 * Math.PI * j / slices
            val x = (sin(th) * cos(ph)).toFloat(); val y = cos(th).toFloat(); val z = (sin(th) * sin(ph)).toFloat()
            v += listOf(x, y, z, x, y, z, 0f, 0f)
        }
        for (i in 0 until stacks) for (j in 0 until slices) { p(i, j); p(i + 1, j); p(i + 1, j + 1); p(i, j); p(i + 1, j + 1); p(i, j + 1) }
        return mesh(v.toFloatArray(), GLES30.GL_TRIANGLES)
    }

    /** Torus in the XY plane, major radius 1, facing +Z. */
    private fun buildTorus(seg: Int, sides: Int, minor: Float): Mesh {
        val v = ArrayList<Float>()
        fun p(i: Int, j: Int) {
            val a = 2 * Math.PI * i / seg; val b = 2 * Math.PI * j / sides
            val nx = (cos(a) * cos(b)).toFloat(); val ny = (sin(a) * cos(b)).toFloat(); val nz = sin(b).toFloat()
            v += listOf(cos(a).toFloat() + minor * nx, sin(a).toFloat() + minor * ny, minor * nz, nx, ny, nz, 0f, 0f)
        }
        for (i in 0 until seg) for (j in 0 until sides) { p(i, j); p(i + 1, j); p(i + 1, j + 1); p(i, j); p(i + 1, j + 1); p(i, j + 1) }
        return mesh(v.toFloatArray(), GLES30.GL_TRIANGLES)
    }

    /** Open cylinder of radius 1 along +Y from 0 to 1. */
    private fun buildTube(sides: Int): Mesh {
        val v = ArrayList<Float>()
        fun p(j: Int, y: Float) { val a = 2 * Math.PI * j / sides; val x = cos(a).toFloat(); val z = sin(a).toFloat(); v += listOf(x, y, z, x, 0f, z, 0f, 0f) }
        for (j in 0 until sides) { p(j, 0f); p(j, 1f); p(j + 1, 1f); p(j, 0f); p(j + 1, 1f); p(j + 1, 0f) }
        return mesh(v.toFloatArray(), GLES30.GL_TRIANGLES)
    }

    /** Sets the camera for subsequent draws. [headRotation] is the head's rotation matrix for billboards. */
    fun begin(viewProj: FloatArray, eyePos: FloatArray, headRotation: FloatArray) {
        System.arraycopy(viewProj, 0, vp, 0, 16); System.arraycopy(eyePos, 0, eye, 0, 3); System.arraycopy(headRotation, 0, headRot, 0, 16)
        GLES30.glUseProgram(program)
        GLES30.glUniform3f(uEye, eye[0], eye[1], eye[2])
        GLES30.glEnable(GLES30.GL_DEPTH_TEST); GLES30.glDisable(GLES30.GL_BLEND); GLES30.glDepthMask(true)
    }

    private fun submit(m: Mesh, mode: Int, r: Float, g: Float, b: Float, a: Float) {
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0); GLES30.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES30.glUniform4f(uColor, r, g, b, a); GLES30.glUniform1i(uMode, mode)
        GLES30.glBindVertexArray(m.vao); GLES30.glDrawArrays(m.mode, 0, m.count)
    }

    fun sphere(x: Double, y: Double, z: Double, radius: Double, r: Float, g: Float, b: Float, lit: Boolean = true) {
        Matrix.setIdentityM(model, 0); Matrix.translateM(model, 0, x.toFloat(), y.toFloat(), z.toFloat())
        Matrix.scaleM(model, 0, radius.toFloat(), radius.toFloat(), radius.toFloat())
        submit(sphere, if (lit) 1 else 0, r, g, b, 1f)
    }

    /** Soft additive halo: a second, larger sphere drawn without depth writes. */
    fun halo(x: Double, y: Double, z: Double, radius: Double, r: Float, g: Float, b: Float, strength: Float) {
        GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE); GLES30.glDepthMask(false)
        Matrix.setIdentityM(model, 0); Matrix.translateM(model, 0, x.toFloat(), y.toFloat(), z.toFloat())
        Matrix.scaleM(model, 0, radius.toFloat(), radius.toFloat(), radius.toFloat())
        submit(sphere, 0, r * strength, g * strength, b * strength, 1f)
        GLES30.glDisable(GLES30.GL_BLEND); GLES30.glDepthMask(true)
    }

    /** Ring centred at p whose axis is [n] (unit). */
    fun ring(p: DoubleArray, n: DoubleArray, radius: Double, r: Float, g: Float, b: Float) {
        basisFromZ(n, p)
        Matrix.scaleM(model, 0, radius.toFloat(), radius.toFloat(), radius.toFloat())
        submit(torus, 1, r, g, b, 1f)
    }

    /** Tube from a to b. */
    fun tube(a: DoubleArray, b: DoubleArray, radius: Double, r: Float, g: Float, bl: Float) {
        val d = doubleArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2])
        val len = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
        if (len < 1e-5) return
        basisFromY(doubleArrayOf(d[0] / len, d[1] / len, d[2] / len), a)
        Matrix.scaleM(model, 0, radius.toFloat(), len.toFloat(), radius.toFloat())
        submit(tube, 1, r, g, bl, 1f)
    }

    private fun basisFromZ(z: DoubleArray, p: DoubleArray) {
        val up = if (Math.abs(z[1]) > 0.95) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(0.0, 1.0, 0.0)
        val x = norm(cross(up, z)); val y = cross(z, x)
        setBasis(x, y, z, p)
    }

    private fun basisFromY(y: DoubleArray, p: DoubleArray) {
        val ref = if (Math.abs(y[0]) > 0.9) doubleArrayOf(0.0, 0.0, 1.0) else doubleArrayOf(1.0, 0.0, 0.0)
        val z = norm(cross(ref, y)); val x = cross(y, z)
        setBasis(x, y, z, p)
    }

    private fun setBasis(x: DoubleArray, y: DoubleArray, z: DoubleArray, p: DoubleArray) {
        model[0] = x[0].toFloat(); model[1] = x[1].toFloat(); model[2] = x[2].toFloat(); model[3] = 0f
        model[4] = y[0].toFloat(); model[5] = y[1].toFloat(); model[6] = y[2].toFloat(); model[7] = 0f
        model[8] = z[0].toFloat(); model[9] = z[1].toFloat(); model[10] = z[2].toFloat(); model[11] = 0f
        model[12] = p[0].toFloat(); model[13] = p[1].toFloat(); model[14] = p[2].toFloat(); model[15] = 1f
    }

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun norm(v: DoubleArray): DoubleArray { val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).coerceAtLeast(1e-9); return doubleArrayOf(v[0] / n, v[1] / n, v[2] / n) }

    fun line(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double) {
        if (lineCount >= 4096) return
        lineBuf.put(ax.toFloat()).put(ay.toFloat()).put(az.toFloat()).put(bx.toFloat()).put(by.toFloat()).put(bz.toFloat())
        lineCount++
    }

    /** Draws and clears the queued lines. */
    fun flushLines(r: Float, g: Float, b: Float) {
        if (lineCount == 0) return
        lineBuf.position(0)
        GLES30.glBindVertexArray(lineVao); GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineVbo)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, lineCount * 24, lineBuf)
        Matrix.setIdentityM(model, 0)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, vp, 0); GLES30.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES30.glUniform4f(uColor, r, g, b, 1f); GLES30.glUniform1i(uMode, 0)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, lineCount * 2)
        lineBuf.clear(); lineCount = 0
    }

    /** Text facing the viewer, centred at p, [height] metres tall. */
    fun text(s: String, x: Double, y: Double, z: Double, height: Double, r: Float, g: Float, b: Float, alpha: Float = 1f) {
        if (s.isEmpty()) return
        val t = texts[s] ?: makeText(s).also { texts[s] = it; trimTexts() }
        System.arraycopy(headRot, 0, model, 0, 16)
        model[12] = x.toFloat(); model[13] = y.toFloat(); model[14] = z.toFloat()
        val w = height * t[1] / t[2]
        Matrix.scaleM(model, 0, w.toFloat(), height.toFloat(), 1f)
        GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA); GLES30.glDepthMask(false)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]); GLES30.glUniform1i(uTex, 0)
        submit(quad, 2, r, g, b, alpha)
        GLES30.glDisable(GLES30.GL_BLEND); GLES30.glDepthMask(true)
    }

    private fun makeText(s: String): IntArray {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 72f; color = -1; typeface = dev.ultraxr.desktop.Type.bold }      // the one family everything in the glasses is set in
        val w = (paint.measureText(s) + 16).toInt().coerceAtLeast(8); val h = 96
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(s, 8f, 70f, paint)
        val ids = IntArray(1); GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        return intArrayOf(ids[0], w, h)
    }

    private fun trimTexts() {
        while (texts.size > 48) {
            val k = texts.keys.first(); val t = texts.remove(k)!!
            GLES30.glDeleteTextures(1, intArrayOf(t[0]), 0)
        }
    }

    companion object {
        /** Hue 0..1 to RGB at full saturation and the given value. */
        fun hue(h: Float, v: Float = 1f): FloatArray {
            val x = (h - Math.floor(h.toDouble()).toFloat()) * 6f
            val i = x.toInt(); val f = x - i
            val q = 1 - f
            return when (i % 6) {
                0 -> floatArrayOf(v, v * f, 0f); 1 -> floatArrayOf(v * q, v, 0f); 2 -> floatArrayOf(0f, v, v * f)
                3 -> floatArrayOf(0f, v * q, v); 4 -> floatArrayOf(v * f, 0f, v); else -> floatArrayOf(v, 0f, v * q)
            }
        }
    }
}
