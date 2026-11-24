package dev.ultraxr

import dev.ultraxr.core.GestureLab
import dev.ultraxr.core.Hand
import dev.ultraxr.core.HandReader
import dev.ultraxr.core.Q
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.acos

/**
 * A benchmark over a hand lab session (render/LabScene): the wearer pointed at targets of known direction and pinched.
 * From the session's files (`lab-<stamp>-joints.csv`, `-head.csv`, `-events.csv`, `-cues.csv`, copied to
 * `C:\uvio\handup\lab\`, the stamp of the one to use on the first line of `C:\uvio\handup\lab\use.txt`) it runs the ray
 * again, with each of several places for it to start, and says how far from the target each would have been at every
 * pinch the live pipeline made. Writes `C:\uvio\handup\lab_eval_out.txt`. Skipped when there is no session.
 */
class HandLabEvalTest {
    private fun angle(a: DoubleArray, b: DoubleArray) = Math.toDegrees(acos((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1.0, 1.0)))
    private fun pct(l: List<Double>, p: Double) = if (l.isEmpty()) Double.NaN else l.sorted()[((l.size - 1) * p).toInt()]

    @Test fun eval() {
        val use = File("C:/uvio/handup/lab/use.txt"); assumeTrue(use.isFile)
        val stamp = use.readLines().first().trim(); val dir = "C:/uvio/handup/lab/lab-$stamp"
        assumeTrue(File("$dir-joints.csv").isFile)
        // hands, by result
        class Res(val t: Double, val side: Int, val joints: FloatArray)
        val results = ArrayList<Res>()
        File("$dir-joints.csv").forEachLine { l -> val c = l.split(','); if (c.size >= 65 && c[0] != "t") results += Res(c[0].toDouble(), c[1].toInt(), FloatArray(63) { c[2 + it].toFloat() }) }
        results.sortBy { it.t }
        val ht = ArrayList<Double>(); val hp = ArrayList<DoubleArray>(); val hq = ArrayList<DoubleArray>()
        File("$dir-head.csv").forEachLine { l -> val c = l.split(','); if (c.size >= 8 && c[0] != "t") { ht += c[0].toDouble(); hp += doubleArrayOf(c[1].toDouble(), c[2].toDouble(), c[3].toDouble()); hq += doubleArrayOf(c[4].toDouble(), c[5].toDouble(), c[6].toDouble(), c[7].toDouble()) } }
        val cues = File("$dir-cues.csv").readLines().drop(1).map { it.split(',') }.filter { it[2] == "TARGET" }.map { Triple(it[0].toInt(), it[4].toDouble(), it[5].toDouble()) }
        val onsets = File("$dir-events.csv").readLines().drop(1).map { it.split(',') }.filter { it[2] == "1" }.map { doubleArrayOf(it[0].toDouble(), it[1].toDouble()) }
        val lab = GestureLab(); val anchorP = hp.first(); val anchorQ = hq.first()
        fun targetPoint(id: Int): DoubleArray {
            val a = lab.targetOf(lab.cues[id]); val az = Math.toRadians(a[0]); val el = Math.toRadians(a[1])
            val r = Q.rotate(anchorQ, doubleArrayOf(Math.sin(az) * Math.cos(el) * 2.0, Math.sin(el) * 2.0, -Math.cos(az) * Math.cos(el) * 2.0))
            return doubleArrayOf(anchorP[0] + r[0], anchorP[1] + r[1], anchorP[2] + r[2])
        }
        // the click of each target cue: the first onset in its window
        val clicks = cues.mapNotNull { (id, at, win) -> onsets.firstOrNull { it[0] in (at + 0.1)..(at + win) }?.let { Triple(id, it[0], it[1].toInt()) } }
        val out = StringBuilder("session $stamp: ${results.size} tracker results, ${clicks.size} of ${cues.size} target cues had a pinch\n")
        for (eye in listOf(0.0, 0.5, 1.0)) for (from in 0..3) {
            val reader = HandReader(rayFrom = from, eye = eye); val errs = ArrayList<Double>()
            var ri = 0; var t = 0.0; val tEnd = ht.last()
            // run the reader at 60 calls a second in the lab's own clock (seconds from its start; the hands' stamps are in the same)
            val hands = arrayOfNulls<Hand>(2)
            var ci = 0; val sorted = clicks.sortedBy { it.second }
            while (t <= tEnd && ci < sorted.size) {
                while (ri < results.size && results[ri].t <= t) { val r = results[ri]; hands[r.side] = Hand(r.side == 1, r.joints.copyOf(21 * 3).let { f -> FloatArray(63) { f[it] } }, (r.t * 1e9).toLong() + 1_000_000_000_000L); ri++ }
                var h = java.util.Collections.binarySearch(ht, t); if (h < 0) h = -h - 1; h = h.coerceIn(0, ht.size - 1)
                val now = (t * 1e9).toLong() + 1_000_000_000_000L + 40_000_000L       // a result is used 40 ms after its picture
                val probes = reader.read(hands.filterNotNull(), now, hp[h], hq[h])
                while (ci < sorted.size && sorted[ci].second <= t) {
                    val (id, _, side) = sorted[ci]; val p = probes.firstOrNull { (if (it.isRight) 1 else 0) == side }
                    if (p?.rayDir != null) { val tp = targetPoint(id); val d = doubleArrayOf(tp[0] - p.rayOrigin[0], tp[1] - p.rayOrigin[1], tp[2] - p.rayOrigin[2]); val n = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]); errs += angle(p.rayDir, doubleArrayOf(d[0] / n, d[1] / n, d[2] / n)) }
                    ci++
                }
                t += 1.0 / 60
            }
            out.append("eye ").append(eye).append(" ray from ").append(listOf("fingertips' middle", "index tip", "knuckles", "half way")[from]).append(": n=").append(errs.size).append(" median ").append("%.2f".format(pct(errs, 0.5))).append(" deg, P90 ").append("%.2f".format(pct(errs, 0.9))).append(", P99 ").append("%.2f".format(pct(errs, 0.99))).append(", within 3 deg ").append("%.0f".format(100.0 * errs.count { it <= 3.0 } / maxOf(1, errs.size))).append(" %\n")
        }
        // the hold-still cues: how much each joint shakes along the line of sight and across it, with a hand that was asked to be still
        run {
            val stills = File("$dir-cues.csv").readLines().drop(1).map { it.split(',') }.filter { it[2] == "STILL" }.map { it[4].toDouble() to it[5].toDouble() }
            val names = listOf("palm (middle knuckle)" to 9, "wrist" to 0, "thumb tip" to 4, "index tip" to 8, "middle tip" to 12)
            for ((nm, j) in names) for (side in 0..1) {
                var sumAlong = 0.0; var sumAcross = 0.0; var n = 0; var windows = 0
                for ((at, win) in stills) {
                    val seg = results.filter { it.side == side && it.t in (at + 2.0)..(at + win - 0.5) }
                    if (seg.size < 12) continue
                    // a detrended deviation of each coordinate, in the head's frame at the middle of the window
                    var h = java.util.Collections.binarySearch(ht, at + win / 2); if (h < 0) h = -h - 1; h = h.coerceIn(0, ht.size - 1)
                    val wr = seg.map { doubleArrayOf(it.joints[3 * 0].toDouble(), it.joints[1].toDouble(), it.joints[2].toDouble()) }
                    val mean = DoubleArray(3) { c -> wr.map { it[c] }.average() }
                    val ax = doubleArrayOf(mean[0] - hp[h][0], mean[1] - hp[h][1], mean[2] - hp[h][2]); val dist = Math.sqrt(ax[0] * ax[0] + ax[1] * ax[1] + ax[2] * ax[2])
                    if (dist < 0.2 || dist > 0.8) continue
                    for (c in 0..2) ax[c] /= dist
                    val pts = seg.map { r -> doubleArrayOf(r.joints[3 * j].toDouble(), r.joints[3 * j + 1].toDouble(), r.joints[3 * j + 2].toDouble()) }
                    val m = DoubleArray(3) { c -> pts.map { it[c] }.average() }
                    var along = 0.0; var total = 0.0
                    for (q in pts) { val d = doubleArrayOf(q[0] - m[0], q[1] - m[1], q[2] - m[2]); val a = d[0] * ax[0] + d[1] * ax[1] + d[2] * ax[2]; along += a * a; total += d[0] * d[0] + d[1] * d[1] + d[2] * d[2] }
                    sumAlong += along / pts.size; sumAcross += (total - along) / pts.size; n++; windows++
                }
                if (n > 0) out.append("still hand, ").append(if (side == 1) "right " else "left ").append(nm).append(": ").append(windows).append(" windows, jitter RMS ").append("%.2f".format(Math.sqrt(sumAlong / n) * 1000)).append(" mm along the line of sight, ").append("%.2f".format(Math.sqrt(sumAcross / n / 2) * 1000)).append(" mm across (each way)\n")
            }
        }
        File("C:/uvio/handup/lab_eval_out.txt").writeText(out.toString())
    }
}
