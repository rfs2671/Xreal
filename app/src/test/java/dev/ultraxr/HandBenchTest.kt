package dev.ultraxr

import dev.ultraxr.core.ArmRay
import dev.ultraxr.core.PinchState
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * A benchmark, not a test: runs the shipped pinch rule and ray over recorded joints (`*-joints.csv` of a replay, and
 * its `*-vio.csv`), the files listed one to a line in `C:\uvio\handup\bench_files.txt`, and writes what it measured to
 * `C:\uvio\handup\bench_out.txt`. Skipped when the list is not there.
 */
class HandBenchTest {
    /** The ray as it was until 24 November 2026 (kept here to measure against): three tenths a call, frozen 0.3 s from the pinch. */
    private class OldArmRay(private val right: Boolean) {
        private val k = DoubleArray(3); private var atNs = 0L; private var pinchNs = 0L; private var wasPressed = false
        fun aim(headP: DoubleArray, headQ: DoubleArray, origin: DoubleArray, pinching: Boolean, nowNs: Long): DoubleArray? {
            var f = dev.ultraxr.core.Q.rotate(headQ, doubleArrayOf(0.0, 0.0, -1.0)); var fl = sqrt(f[0] * f[0] + f[2] * f[2])
            if (fl < 0.2) { f = dev.ultraxr.core.Q.rotate(headQ, doubleArrayOf(0.0, 1.0, 0.0)); fl = sqrt(f[0] * f[0] + f[2] * f[2]) }
            if (fl < 1e-3) return null
            val fx = f[0] / fl; val fz = f[2] / fl; val sd = if (right) 0.17 else -0.17
            val sx = headP[0] + -fz * sd - fx * 0.06; val sy = headP[1] - 0.22; val sz = headP[2] + fx * sd - fz * 0.06
            var dx = origin[0] - sx; var dy = origin[1] - sy; var dz = origin[2] - sz
            val dl = sqrt(dx * dx + dy * dy + dz * dz); if (dl < 0.05) return null
            dx /= dl; dy /= dl; dz /= dl
            if (pinching && !wasPressed) pinchNs = nowNs
            wasPressed = pinching
            val fresh = nowNs - atNs < 500_000_000L
            val hold = pinching && fresh && nowNs - pinchNs < 300_000_000L
            if (!hold) {
                if (fresh) { dx = k[0] * 0.7 + dx * 0.3; dy = k[1] * 0.7 + dy * 0.3; dz = k[2] * 0.7 + dz * 0.3; val n = sqrt(dx * dx + dy * dy + dz * dz); dx /= n; dy /= n; dz /= n }
                k[0] = dx; k[1] = dy; k[2] = dz
            }
            atNs = nowNs
            return doubleArrayOf(k[0], k[1], k[2])
        }
    }
    private class Rec(val t: DoubleArray, val gap: DoubleArray, val fin: DoubleArray, val org: Array<DoubleArray>)

    private fun load(path: String, side: Int): Rec? {
        val t = ArrayList<Double>(); val gap = ArrayList<Double>(); val fin = ArrayList<Double>(); val org = ArrayList<DoubleArray>()
        File(path).forEachLine { line ->
            val c = line.split(','); if (c.size < 10 || c[0] == "t" || c[0].isEmpty()) return@forEachLine
            if (c[1].toDouble().toInt() != side) return@forEachLine
            fun j(i: Int) = doubleArrayOf(c[2 + i * 7].toDouble(), c[3 + i * 7].toDouble(), c[4 + i * 7].toDouble())
            fun d(a: DoubleArray, b: DoubleArray) = sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))
            val th = j(5); val ix = j(10)
            t += c[0].toDouble(); gap += d(th, ix); fin += d(j(7), j(8)) + d(j(8), j(9)) + d(j(9), ix)
            org += doubleArrayOf((th[0] + ix[0]) / 2, (th[1] + ix[1]) / 2, (th[2] + ix[2]) / 2)
        }
        return if (t.size < 300) null else Rec(t.toDoubleArray(), gap.toDoubleArray(), fin.toDoubleArray(), org.toTypedArray())
    }

    private class Head(val t: DoubleArray, val p: Array<DoubleArray>, val q: Array<DoubleArray>)
    private fun loadHead(path: String): Head {
        val t = ArrayList<Double>(); val p = ArrayList<DoubleArray>(); val q = ArrayList<DoubleArray>()
        File(path).forEachLine { line -> val c = line.split(','); if (c.size >= 8 && c[0] != "t") { t += c[0].toDouble(); p += doubleArrayOf(c[1].toDouble(), c[2].toDouble(), c[3].toDouble()); q += doubleArrayOf(c[4].toDouble(), c[5].toDouble(), c[6].toDouble(), c[7].toDouble()) } }
        return Head(t.toDoubleArray(), p.toTypedArray(), q.toTypedArray())
    }

    private fun angle(a: DoubleArray, b: DoubleArray) = Math.toDegrees(acos((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1.0, 1.0)))
    private fun pct(l: List<Double>, p: Double) = if (l.isEmpty()) Double.NaN else l.sorted()[((l.size - 1) * p).toInt()]

    @Test fun bench() {
        val list = File("C:/uvio/handup/bench_files.txt"); assumeTrue(list.isFile)
        val files = list.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val out = StringBuilder()
        for (anticipate in listOf(false, true)) {
            var onsets = 0; var flick = 0; val lat = ArrayList<Double>(); var certain = 0; val drift = ArrayList<Double>(); val driftOld = ArrayList<Double>(); val driftFixed = ArrayList<Double>(); val driftShort = ArrayList<Double>(); val sweepAd = ArrayList<Double>(); val sweepFx = ArrayList<Double>()
            for (f in files) {
                val head = loadHead(f.replace("-joints", "-vio"))
                for (side in 0..1) {
                    val r = load(f, side) ?: continue
                    val s = PinchState(anticipate = anticipate); val st = BooleanArray(r.t.size); val began = BooleanArray(r.t.size)
                    for (i in r.t.indices) {
                        if (i > 0 && r.t[i] - r.t[i - 1] > 0.25) s.lost()
                        began[i] = s.update(r.gap[i].toFloat(), r.fin[i].toFloat(), (r.t[i] * 1e9).toLong(), 40_000_000L); st[i] = s.pinching
                    }
                    // onsets, brief ones
                    var i = 0
                    while (i < st.size) { if (began[i]) { onsets++; var e = i; while (e + 1 < st.size && st[e + 1]) e++; if (r.t[e] - r.t[i] < 0.12) flick++; i = e + 1 } else i++ }
                    // certain pinches: gap under 22 mm for three results in a row; onset against the 40 mm crossing
                    i = 0
                    while (i < st.size) {
                        if (r.gap[i] < 0.022) {
                            var j = i; while (j + 1 < st.size && r.gap[j + 1] < 0.022 && r.t[j + 1] - r.t[j] < 0.1) j++
                            if (j - i >= 2) {
                                certain++
                                var cross = r.t[i]; var q = i; while (q > maxOf(0, i - 8)) { if (r.gap[q] >= 0.040) { cross = r.t[q]; break }; q-- }
                                for (k in 0 until st.size) if (began[k] && r.t[k] >= r.t[i] - 0.15 && r.t[k] <= r.t[j] + 0.05) { lat += (r.t[k] - cross) * 1000; break }
                            }
                            i = j + 1
                        } else i++
                    }
                    // click drift of the ray at 60 calls a second
                    val ray = ArmRay(side == 1); val hz = 60.0; val n = ((r.t.last() - r.t.first()) * hz).toInt()
                    val fixed = ArmRay(side == 1, 0.12, false); val dirsFixed = arrayOfNulls<DoubleArray>(n); val short = ArmRay(side == 1, 0.06, false); val dirsShort = arrayOfNulls<DoubleArray>(n); val oldRay = OldArmRay(side == 1); val dirsOld = arrayOfNulls<DoubleArray>(n); val dirs = arrayOfNulls<DoubleArray>(n); val pin = BooleanArray(n)
                    for (k in 0 until n) {
                        val tq = r.t.first() + k / hz; var a = java.util.Arrays.binarySearch(r.t, tq); if (a < 0) a = -a - 2; a = a.coerceIn(0, r.t.size - 1)
                        if (tq - r.t[a] > 0.4) continue
                        var h = java.util.Arrays.binarySearch(head.t, tq); if (h < 0) h = -h - 1; h = h.coerceIn(0, head.t.size - 1)
                        dirs[k] = ray.aim(head.p[h], head.q[h], r.org[a], st[a], (tq * 1e9).toLong()); dirsFixed[k] = fixed.aim(head.p[h], head.q[h], r.org[a], st[a], (tq * 1e9).toLong()); dirsShort[k] = short.aim(head.p[h], head.q[h], r.org[a], st[a], (tq * 1e9).toLong()); dirsOld[k] = oldRay.aim(head.p[h], head.q[h], r.org[a], st[a], (tq * 1e9).toLong()); pin[k] = st[a]
                    }
                    var k = 0
                    while (k < n) {
                        if (pin[k] && (k == 0 || !pin[k - 1])) {
                            var e = k; while (e + 1 < n && pin[e + 1]) e++
                            val dur = (e - k) / hz; val b = maxOf(0, k - (0.12 * hz).toInt())
                            if (dur in 0.05..0.5 && dirs[b] != null && dirs[e] != null && dirsOld[b] != null && dirsOld[e] != null) { drift += angle(dirs[b]!!, dirs[e]!!); driftOld += angle(dirsOld[b]!!, dirsOld[e]!!)
                                if (dirsFixed[b] != null && dirsFixed[e] != null) driftFixed += angle(dirsFixed[b]!!, dirsFixed[e]!!)
                                if (dirsShort[b] != null && dirsShort[e] != null) driftShort += angle(dirsShort[b]!!, dirsShort[e]!!)
                                // a click made while the ray is sweeping (over 30 degrees a second): how far the ray is set back from the unsmoothed, un-latched one as the pinch begins
                                val k4 = maxOf(0, k - 4)
                                if (dirs[k] != null && dirs[k4] != null && angle(dirs[k]!!, dirs[k4]!!) / (4 / hz) > 30.0 && dirsFixed[k] != null && dirsOld[k] != null) { sweepAd += angle(dirs[k]!!, dirsOld[k]!!); sweepFx += angle(dirsFixed[k]!!, dirsOld[k]!!) }
                            }
                            k = e + 1
                        } else k++
                    }
                }
            }
            out.append("anticipate=$anticipate: certain pinches $certain, onsets $onsets, brief (<120 ms) $flick, onset vs 40 mm crossing median ${"%.0f".format(pct(lat, 0.5))} ms P90 ${"%.0f".format(pct(lat, 0.9))} ms (n=${lat.size}); short-click ray drift n=${drift.size}: new median ${"%.2f".format(pct(drift, 0.5))} deg P90 ${"%.2f".format(pct(drift, 0.9))} deg, old median ${"%.2f".format(pct(driftOld, 0.5))} deg P90 ${"%.2f".format(pct(driftOld, 0.9))} deg; fixed 120 ms look-back ${"%.2f".format(pct(driftFixed, 0.5))} / ${"%.2f".format(pct(driftFixed, 0.9))} / P99 ${"%.2f".format(pct(driftFixed, 0.99))}; 60 ms ${"%.2f".format(pct(driftShort, 0.5))} / ${"%.2f".format(pct(driftShort, 0.9))} / P99 ${"%.2f".format(pct(driftShort, 0.99))}; adaptive P99 ${"%.2f".format(pct(drift, 0.99))}; in sweeping clicks (n=${sweepAd.size}) the ray at the pinch is ${"%.2f".format(pct(sweepAd, 0.5))} deg (P90 ${"%.2f".format(pct(sweepAd, 0.9))}) from the old live ray with the adaptive look-back, ${"%.2f".format(pct(sweepFx, 0.5))} (P90 ${"%.2f".format(pct(sweepFx, 0.9))}) with the fixed one\n")
        }
        File("C:/uvio/handup/bench_out.txt").writeText(out.toString())
    }
}
