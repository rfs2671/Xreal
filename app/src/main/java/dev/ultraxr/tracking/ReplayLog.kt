package dev.ultraxr.tracking

import dev.ultraxr.core.Hand
import java.io.File
import java.io.PrintWriter
import java.util.Locale
import kotlin.math.sqrt

/**
 * What the trackers reported while a recording was replayed, as CSV files a script can read:
 *
 *   TAG-vio.csv    one line per position state: recording time, position, orientation, velocity, features,
 *                  landmarks, points seen by both cameras, processing time, reset count, whether position was trusted
 *   TAG-hands.csv  one line per hand tracking result: recording time, which hands were found, and for each hand the
 *                  thumb-tip to index-tip distance, the wrist's distance from the camera and its place in the view
 *   TAG-app.csv    one line per position state: what the application was given (head position in its world), and
 *                  whether the glasses were judged to be lying still and the estimator's position was used
 *   TAG-room.csv   one line per look at the room: points placed, and the floor, table tops and walls found so far
 *   TAG-notes.txt  resets and the closing summary
 *
 * Times are seconds from the start of the recording, so runs with different settings line up.
 */
class ReplayLog(dir: File, tag: String) {
    private val vio = PrintWriter(File(dir.apply { mkdirs() }, "$tag-vio.csv"))
    private val hands = PrintWriter(File(dir, "$tag-hands.csv"))
    private val notes = PrintWriter(File(dir, "$tag-notes.txt"))
    /** Every found hand's 26 joint poses as the tracker gave them (its camera frame): what steadiness is measured on. */
    private val joints = PrintWriter(File(dir, "$tag-joints.csv")).also { it.println("t,side,26 x (px,py,pz,qx,qy,qz,qw)") }
    private val app = PrintWriter(File(dir, "$tag-app.csv")).also { it.println("t,hx,hy,hz,lying,used") }
    /** The room model as it grew: one line per analysis. */
    private val room = PrintWriter(File(dir, "$tag-room.csv")).also { it.println("t,points_in_picture,find_ms,boxes,confirmed,head_y,floor_y,floor_below_head,floor_support,tables,walls") }
    /** The head as the renderer is given it, asked for 60 times a second: what steadiness of the picture is measured on. */
    private val render = PrintWriter(File(dir, "$tag-render.csv")).also { it.println("t,x,y,z,qx,qy,qz,qw,six_dof") }
    /** Every state the head tracker used: its own time on the same clock as the renderer's rows, and where it has the head. */
    private val states = PrintWriter(File(dir, "$tag-states.csv")).also { it.println("t,x,y,z") }
    @Synchronized fun state(t: Double, p: DoubleArray) { states.println(String.format(Locale.US, "%.4f,%.5f,%.5f,%.5f", t, p[0], p[1], p[2])) }
    @Synchronized fun render(t: Double, p: DoubleArray, q: DoubleArray, sixDof: Boolean) {
        render.println(String.format(Locale.US, "%.4f,%.5f,%.5f,%.5f,%.6f,%.6f,%.6f,%.6f,%d", t, p[0], p[1], p[2], q[0], q[1], q[2], q[3], if (sixDof) 1 else 0))
    }
    var handResults = 0; private set
    val handFound = intArrayOf(0, 0)

    init {
        vio.println("t,px,py,pz,qx,qy,qz,qw,vx,vy,vz,features,landmarks,stereo,process_ms,resets,auto_resets,trusted")
        hands.println("t,mask,process_ms,l_gap,l_dist,l_x,l_y,r_gap,r_dist,r_x,r_y")
    }

    @Synchronized fun vio(t: Double, s: DoubleArray, trusted: Boolean) {
        vio.println(String.format(Locale.US, "%.3f,%.4f,%.4f,%.4f,%.5f,%.5f,%.5f,%.5f,%.3f,%.3f,%.3f,%d,%d,%d,%.1f,%d,%d,%d",
            t, s[1], s[2], s[3], s[4], s[5], s[6], s[7], s[8], s[9], s[10], s[14].toInt(), s[23].toInt(), s[22].toInt(), s[18], s[20].toInt(), s[21].toInt(), if (trusted) 1 else 0))
    }

    /** [out] is Mercury's result: two hands of [Hand.JOINTS] points each, in the left camera's frame (x right, y up, -z forward). */
    @Synchronized fun hands(t: Double, mask: Int, out: FloatArray, processMs: Double) {
        handResults++
        val sb = StringBuilder(String.format(Locale.US, "%.3f,%d,%.1f", t, mask, processMs))
        for (side in 0..1) {
            if (mask < 0 || mask and (1 shl side) == 0) { sb.append(",,,,"); continue }
            handFound[side]++
            fun p(j: Int, k: Int) = out[(side * Hand.JOINTS + j) * 3 + k]
            val dx = p(Hand.THUMB_TIP, 0) - p(Hand.INDEX_TIP, 0); val dy = p(Hand.THUMB_TIP, 1) - p(Hand.INDEX_TIP, 1); val dz = p(Hand.THUMB_TIP, 2) - p(Hand.INDEX_TIP, 2)
            val wx = p(Hand.WRIST, 0); val wy = p(Hand.WRIST, 1); val wz = p(Hand.WRIST, 2)
            sb.append(String.format(Locale.US, ",%.4f,%.3f,%.3f,%.3f", sqrt(dx * dx + dy * dy + dz * dz), sqrt(wx * wx + wy * wy + wz * wz), wx, wy))
        }
        hands.println(sb)
        if (handResults % 60 == 0) { hands.flush(); vio.flush() }
    }

    @Synchronized fun joints(t: Double, mask: Int, out26: FloatArray) {
        for (side in 0..1) {
            if (mask < 0 || mask and (1 shl side) == 0) continue
            val sb = StringBuilder(String.format(Locale.US, "%.3f,%d", t, side))
            for (k in side * 182 until side * 182 + 182) sb.append(',').append(String.format(Locale.US, "%.5f", out26[k]))
            joints.println(sb)
        }
    }

    /** What the application is given: the head's position in the app's world, whether the glasses were judged to be lying still, and whether the estimator's position was used. */
    @Synchronized fun app(t: Double, head: DoubleArray, lying: Boolean, used: Boolean) {
        app.println(String.format(Locale.US, "%.3f,%.4f,%.4f,%.4f,%d,%d", t, head[0], head[1], head[2], if (lying) 1 else 0, if (used) 1 else 0))
    }

    @Synchronized fun room(t: Double, points: Int, findMs: Double, headY: Double, f: dev.ultraxr.core.RoomFindings) {
        room.println(String.format(Locale.US, "%.3f,%d,%.1f,%d,%d,%.3f,%.3f,%.3f,%d,%d,%d", t, points, findMs, f.voxels, f.confirmed, headY, f.floorY, headY - f.floorY, f.floorSupport,
            f.surfaces.count { it.kind == dev.ultraxr.core.RoomSurface.Kind.TABLE }, f.surfaces.count { it.kind == dev.ultraxr.core.RoomSurface.Kind.WALL }))
    }

    @Synchronized fun note(t: Double, text: String) { notes.println(String.format(Locale.US, "%.3f %s", t, text)); notes.flush() }

    @Synchronized fun close(summary: String) {
        notes.println(summary)
        notes.println("hand results $handResults, left found ${handFound[0]}, right found ${handFound[1]}")
        vio.close(); hands.close(); notes.close(); app.close(); joints.close(); room.close(); render.close(); states.close()
    }
}
