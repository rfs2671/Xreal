// Ultra XR: what the cameras saw before position was lost. SPDX-License-Identifier: GPL-3.0-or-later
package dev.ultraxr.core

import java.io.File

/**
 * The cameras' last seconds, kept in memory, and written out when position is lost.
 *
 * 22 November 2026: of the losses in the phone's log only two happened on the head (the others within seconds of the
 * glasses' own sensor saying they had been taken off). In both the estimator found none of one picture's corners
 * again in the next for about a second, with the pictures arriving on time and the head turning slowly in one of
 * them. What was in those pictures nobody can say: nothing kept them. This keeps every [every]th pair (both
 * pictures whole, as the trackers get them) for the last [pairs] of them and changes nothing about tracking. The
 * files show the wearer's room: they stay in the app's own folder on the phone, the newest [KEEP_FILES] of them.
 */
class LossPictures(val pairs: Int = 40, val every: Int = 3, private val size: Int = 480 * 640) {
    private var left: Array<ByteArray>? = null; private var right: Array<ByteArray>? = null
    private val times = LongArray(pairs)
    private var seen = 0L; private var kept = 0L; private var off = false

    /** One pair as the estimator gets it. Cheap: two copies of a picture for every [every]th pair. */
    @Synchronized fun onStereo(tNs: Long, l: ByteArray, r: ByteArray) {
        if (off || l.size < size || r.size < size || seen++ % every != 0L) return
        if (left == null) try { left = Array(pairs) { ByteArray(size) }; right = Array(pairs) { ByteArray(size) } } catch (_: OutOfMemoryError) { left = null; right = null; off = true; return }
        val i = (kept % pairs).toInt()
        System.arraycopy(l, 0, left!![i], 0, size); System.arraycopy(r, 0, right!![i], 0, size); times[i] = tNs; kept++
    }

    /** How many pairs are held now. */
    @Synchronized fun held(): Int = minOf(kept, pairs.toLong()).toInt()

    /**
     * Writes what is held, oldest first: a line "UXLOSS1", a line of [note], then for each pair its time (8 bytes,
     * big-endian nanoseconds of the cameras' clock) and the left and the right picture (upright, 480 x 640 bytes each).
     * Returns how many pairs were written.
     */
    @Synchronized fun write(file: File, note: String): Int {
        val n = held(); val l = left ?: return 0; val r = right ?: return 0
        if (n == 0) return 0
        file.parentFile?.mkdirs()
        file.outputStream().buffered(1 shl 16).use { o ->
            o.write("UXLOSS1\n".toByteArray()); o.write((note.replace('\n', ' ') + "\n").toByteArray())
            val head = java.nio.ByteBuffer.allocate(8)
            for (k in 0 until n) {
                val i = ((kept - n + k) % pairs).toInt()
                head.clear(); head.putLong(times[i]); o.write(head.array()); o.write(l[i]); o.write(r[i])
            }
        }
        return n
    }

    companion object {
        const val KEEP_FILES = 10
        /** Removes all but the newest [KEEP_FILES] sets (a set: the pictures and the estimator's states beside them). */
        fun prune(dir: File) {
            val sets = dir.listFiles { f -> f.name.endsWith(".uxloss") }?.sortedByDescending { it.name } ?: return
            for (f in sets.drop(KEEP_FILES)) { File(dir, f.name.removeSuffix(".uxloss") + ".csv").delete(); f.delete() }
        }
    }
}
