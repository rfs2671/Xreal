package dev.ultraxr.render

import dev.ultraxr.core.Fmt
import dev.ultraxr.core.Hand
import dev.ultraxr.core.GestureLab
import dev.ultraxr.core.HeadTracker
import dev.ultraxr.tracking.Tracking
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * The hand lab in the glasses: cues for about nine minutes of pinches, holds, hands held still and hands waved about
 * ([GestureLab]), scored at the end and written to files/lab/lab-<time>.json. The tracker's joints (as the pipeline
 * saw them, with the head's place) are written beside it, lab-<time>-joints.csv and lab-<time>-head.csv, so that the
 * pinch rule, the ray and the prediction can be run again over exactly this session, with its labels.
 *
 * Begin by pinching with both hands. All of the cues are within the picture, a metre and a bit ahead.
 */
class LabScene(private val coverage: Boolean = false) : BaseScene() {
    private var lab = GestureLab(coverage)
    private var startNs = 0L
    private var state = 0           // 0 waiting to begin, 1 running, 2 done
    private val prev = BooleanArray(2)
    private var resultLines: List<String> = emptyList()
    private var joints: BufferedWriter? = null; private var headCsv: BufferedWriter? = null; private var coverCsv: BufferedWriter? = null
    private val lastLogged = LongArray(2); private var lastFlushNs = 0L
    private var toned = -1; private var tonedRelease = -1
    private var tone: android.media.ToneGenerator? = null
    private var stamp = ""
    private var doneNs = 0L
    private var anchorP = DoubleArray(3); private var anchorQ = doubleArrayOf(0.0, 0.0, 0.0, 1.0)
    private val targetTmp = DoubleArray(3)

    fun restart() { lab = GestureLab(coverage); state = 0; resultLines = emptyList(); prev[0] = false; prev[1] = false; toned = -1; tonedRelease = -1; closeFiles() }

    private fun beep(ms: Int = 90) {
        try { if (tone == null) tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 80); tone?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, ms) } catch (_: Throwable) {}
    }

    /** The target of [c] in the world: 2 m from where the head was when the lab began, at the cue's angles from the heading then. */
    private fun targetPoint(c: GestureLab.Cue): DoubleArray {
        val a = lab.targetOf(c); val az = Math.toRadians(a[0]); val el = Math.toRadians(a[1])
        val loc = doubleArrayOf(Math.sin(az) * Math.cos(el) * 2.0, Math.sin(el) * 2.0, -Math.cos(az) * Math.cos(el) * 2.0)
        val r = dev.ultraxr.core.Q.rotate(anchorQ, loc)
        targetTmp[0] = anchorP[0] + r[0]; targetTmp[1] = anchorP[1] + r[1]; targetTmp[2] = anchorP[2] + r[2]
        return doubleArrayOf(targetTmp[0], targetTmp[1], targetTmp[2])
    }

    private fun rayErrorDeg(o: DoubleArray, dir: DoubleArray, p: DoubleArray): Double {
        var x = p[0] - o[0]; var y = p[1] - o[1]; var z = p[2] - o[2]; val n = Math.sqrt(x * x + y * y + z * z); if (n < 1e-6) return -1.0
        x /= n; y /= n; z /= n
        return Math.toDegrees(Math.acos((x * dir[0] + y * dir[1] + z * dir[2]).coerceIn(-1.0, 1.0)))
    }

    private fun closeFiles() { try { joints?.close(); headCsv?.close(); coverCsv?.close() } catch (_: Throwable) {}; joints = null; headCsv = null; coverCsv = null }

    private lateinit var head0: HeadTracker.State
    private fun begin(now: Long) {
        lab.clear(); startNs = now; state = 1; toned = -1; tonedRelease = -1
        anchorP = head0.pose.p.copyOf(); anchorQ = head0.pose.q.copyOf()
        stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        try {
            val dir = File(Tracking.appContextOrNull()?.getExternalFilesDir("lab") ?: return, "").also { it.mkdirs() }
            joints = BufferedWriter(FileWriter(File(dir, "lab-$stamp-joints.csv")), 1 shl 16).also { it.write("t,side," + (0 until 21).joinToString(",") { "x$it,y$it,z$it" } + "\n") }
            headCsv = BufferedWriter(FileWriter(File(dir, "lab-$stamp-head.csv")), 1 shl 16).also { it.write("t,x,y,z,qx,qy,qz,qw,six_dof\n") }
            if (coverage) {
                coverCsv = BufferedWriter(FileWriter(File(dir, "lab-$stamp-cover.csv")), 1 shl 16).also { it.write("t,cue,left_seen,right_seen,left_x_cm,left_y_cm,left_z_cm,right_x_cm,right_y_cm,right_z_cm,left_age_ms,right_age_ms\n") }
                // every picture of the cameras and every motion report, so that the session can be run through the tracker again and looked at: about 9 MB a second
                Tracking.link?.let { l -> if (!l.recording) l.record(File(Tracking.appContextOrNull()?.getExternalFilesDir("recordings") ?: dir, "cover-$stamp.uxrec"), lab.durationS.toInt() + 5) }
            }
        } catch (e: Throwable) { Tracking.log("hand lab: could not open its files: $e") }
        Tracking.log("hand lab: begun, " + lab.cues.size + " cues over " + Fmt.f(lab.durationS, 0) + " s")
    }

    private fun finish(now: Long) {
        state = 2; doneNs = now
        val sc = lab.score()
        resultLines = sc.text.trim().lines()
        for (l in resultLines) Tracking.log(l)
        try {
            val dir = File(Tracking.appContextOrNull()?.getExternalFilesDir("lab") ?: return, "")
            val meta = JSONObject().put("stamp", stamp).put("duration_s", lab.durationS).put("hand_options", Tracking.handOptions.toString())
            sc.json.put("meta", meta)
            File(dir, "lab-$stamp.json").writeText(sc.json.toString(1))
            val ev = StringBuilder("t,side,onset,age_ms\n"); for (e in lab.allEvents()) ev.append(e.tS).append(',').append(e.side).append(',').append(if (e.onset) 1 else 0).append(',').append(e.ageMs).append('\n')
            File(dir, "lab-$stamp-events.csv").writeText(ev.toString())
            val cu = StringBuilder("id,section,kind,side,at_s,window_s,text\n"); for (c in lab.cues) cu.append(c.id).append(',').append(c.section).append(',').append(c.kind).append(',').append(c.side).append(',').append(c.atS).append(',').append(c.windowS).append(',').append(c.text.replace(',', ' ')).append('\n')
            File(dir, "lab-$stamp-cues.csv").writeText(cu.toString())
        } catch (e: Throwable) { Tracking.log("hand lab: could not write its result: $e") }
        closeFiles()
    }

    override fun update(dt: Double, head: HeadTracker.State) {
        time += dt
        readHands(head)
        val now = System.nanoTime()
        val pin = BooleanArray(2); val age = DoubleArray(2)
        for (p in probes) { val s = if (p.isRight) 1 else 0; pin[s] = p.pinching; age[s] = (now - p.tNs) / 1e6 }
        head0 = head
        if (state == 0) { if (pin[0] && pin[1] && !prev[0]) { begin(now) }; prev[0] = pin[0]; prev[1] = pin[1]; return }
        if (state == 2) { val again = pin[0] && pin[1] && !(prev[0] && prev[1]); prev[0] = pin[0]; prev[1] = pin[1]; if (again && (now - doneNs) > 3_000_000_000L) restart(); return }
        val tS = (now - startNs) / 1e9
        val tc = lab.cues.lastOrNull { tS >= it.atS && tS <= it.atS + it.windowS + 1.0 && it.kind == GestureLab.Kind.TARGET }
        val tp = tc?.let { targetPoint(it) }
        for (s in 0..1) {
            val probe = probes.firstOrNull { (if (it.isRight) 1 else 0) == s }
            val err = if (tp != null && probe?.rayDir != null) rayErrorDeg(probe.rayOrigin, probe.rayDir!!, tp) else -1.0
            if (pin[s] && !prev[s]) lab.onset(tS, s, age[s], err)
            if (!pin[s] && prev[s]) lab.release(tS, s, err)
            prev[s] = pin[s]
        }
        // what the pipeline saw, for running it again
        joints?.let { w ->
            for (h in Tracking.handJoints) {
                val side = if (h.isRight) 1 else 0
                if (h.timestampNs == lastLogged[side]) continue
                lastLogged[side] = h.timestampNs
                val sb = StringBuilder(); sb.append((h.timestampNs - startNs) / 1e9).append(',').append(side)
                for (v in h.joints) sb.append(',').append(v)
                w.write(sb.append('\n').toString())
            }
        }
        headCsv?.write((tS).toString() + "," + head.pose.p.joinToString(",") + "," + head.pose.q.joinToString(",") + "," + (if (head.mode == HeadTracker.Mode.SIX_DOF) 1 else 0) + "\n")
        coverCsv?.let { w ->
            val cc = lab.cues.lastOrNull { tS >= it.atS - 0.5 && tS <= it.atS + it.windowS }
            val inv = head.pose.inverse(); val sb = StringBuilder(); sb.append(tS).append(',').append(cc?.id ?: -1)
            val seen = BooleanArray(2); val pos = Array(2) { doubleArrayOf(Double.NaN, Double.NaN, Double.NaN) }; val ages = DoubleArray(2) { -1.0 }
            for (h in Tracking.handJoints) {
                val side = if (h.isRight) 1 else 0; val a = (now - h.timestampNs) / 1e6
                if (a > 400.0) continue
                seen[side] = true; ages[side] = a
                val v = inv.apply(h.joint(Hand.WRIST)); pos[side] = doubleArrayOf(v[0] * 100, v[1] * 100, -v[2] * 100)      // cm: right, up, forward
            }
            sb.append(',').append(if (seen[0]) 1 else 0).append(',').append(if (seen[1]) 1 else 0)
            for (s2 in 0..1) for (c in 0..2) sb.append(',').append(if (seen[s2]) "%.1f".format(java.util.Locale.US, pos[s2][c]) else "")
            sb.append(',').append("%.0f".format(java.util.Locale.US, ages[0])).append(',').append("%.0f".format(java.util.Locale.US, ages[1]))
            w.write(sb.append('\n').toString())
        }
        if (now - lastFlushNs > 3_000_000_000L) { lastFlushNs = now; try { joints?.flush(); headCsv?.flush(); coverCsv?.flush() } catch (_: Throwable) {} }
        val c = lab.cues.lastOrNull { tS >= it.atS }
        if (c != null && c.id != toned) { toned = c.id; beep(120) }
        if (c != null && c.kind == GestureLab.Kind.HOLD && tS >= c.releaseS && c.id != tonedRelease) { tonedRelease = c.id; beep(240) }
        if (tS > lab.durationS) finish(now)
    }

    override fun draw(d: Draw, head: HeadTracker.State) {
        drawHands(d); drawRays(d); drawEdgeMarks(d, head); drawStatus(d, head)
        fun line(s: String, up: Double, h: Double, r: Float, g: Float, b: Float, a: Float = 1f) { val p = inFront(head, 0.0, up, 1.25); d.text(s, p[0], p[1], p[2], h, r, g, b, a) }
        when (state) {
            0 -> { line(if (coverage) "COVERAGE LAB" else "HAND LAB", 0.16, 0.07, 0.6f, 0.9f, 1f); line("Pinch with both hands to begin (about " + Fmt.f(lab.durationS / 60.0, 0) + " minutes)", 0.05, 0.04, 1f, 1f, 1f); line("Hold your hands in front of your face, as in a game", -0.03, 0.03, 0.7f, 0.7f, 0.7f) }
            1 -> {
                val tS = (System.nanoTime() - startNs) / 1e9
                val now = lab.cues.lastOrNull { tS >= it.atS - 0.0 }; val next = lab.cues.firstOrNull { it.atS > tS }
                val showing = if (now != null && tS <= now.atS + minOf(now.windowS, 2.5)) now else next
                if (showing != null) {
                    val until = showing.atS - tS
                    val live = until <= 0.0
                    line(showing.text, 0.10, 0.045, if (live) 0.5f else 1f, 1f, if (live) 0.5f else 1f)
                    line(if (live) "NOW" else "in " + Fmt.f(until.coerceAtLeast(0.0), 1) + " s", 0.02, 0.06, if (live) 0.4f else 0.8f, 1f, 0.5f, 1f)
                    if (live) { val p = inFront(head, 0.0, -0.10, 1.25); d.halo(p[0], p[1], p[2], 0.08, 0.4f, 1f, 0.4f, 0.8f) }
                    if (showing.kind == GestureLab.Kind.TARGET) { val tp = targetPoint(showing); d.halo(tp[0], tp[1], tp[2], 0.07, 0.4f, 1f, 0.4f, if (live) 0.9f else 0.35f); d.sphere(tp[0], tp[1], tp[2], 0.012, 1f, 1f, 0.6f, lit = false) }
                } else line("Last ones done: hold still for a moment", 0.05, 0.04, 1f, 1f, 1f)
                line(Fmt.f(tS / lab.durationS * 100.0, 0) + " %", -0.12, 0.03, 0.6f, 0.6f, 0.6f)
            }
            else -> {
                line("DONE", 0.18, 0.07, 0.6f, 1f, 0.6f)
                var y = 0.09; for (l in resultLines.take(9)) { line(l.take(70), y, 0.026, 1f, 1f, 1f); y -= 0.045 }
                line("Pinch with both hands to run it again", -0.28, 0.03, 0.7f, 0.9f, 1f)
            }
        }
    }
}
