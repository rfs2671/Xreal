// Ultra XR: a video in a window of the desktop, flat or side by side (3D). SPDX-License-Identifier: GPL-3.0-or-later
package dev.ultraxr.desktop

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import dev.ultraxr.tracking.Tracking

/**
 * How a video's picture is laid out, and so what each eye is given.
 *  - [FLAT]: one picture, both eyes the same.
 *  - [SBS_HALF]: side by side, each half squeezed to half its width (a 1920 x 1080 file whose halves are 960 x 1080): the left half is for the left eye, the
 *    right half for the right eye, each stretched back out to the full width of the window.
 *  - [SBS_FULL]: side by side at full width (3840 x 1080): the same, nothing squeezed.
 */
enum class VideoMode(val label: String) {
    FLAT("flat"), SBS_HALF("side by side, half width"), SBS_FULL("side by side, full width");

    val sbs: Boolean get() = this != FLAT
    fun next(): VideoMode = values()[(ordinal + 1) % values().size]

    /** The shape of what one eye sees, width over height, for a file of [vw] x [vh]. */
    fun aspect(vw: Int, vh: Int): Double {
        if (vw <= 0 || vh <= 0) return 16.0 / 9.0
        return when (this) { FLAT -> vw.toDouble() / vh; SBS_HALF -> vw.toDouble() / vh; SBS_FULL -> vw / 2.0 / vh }
    }

    companion object {
        private val SBS_WORDS = Regex("(^|[^a-z0-9])(h?sbs|half[ ._-]?sbs|side[ ._-]?by[ ._-]?side|3d|lr|h-?sbs)([^a-z0-9]|$)", RegexOption.IGNORE_CASE)
        private val FULL_WORDS = Regex("(^|[^a-z0-9])(fsbs|full[ ._-]?sbs|f-?sbs)([^a-z0-9]|$)", RegexOption.IGNORE_CASE)

        /**
         * What a file most likely is, from its name and its size: a picture three times as wide as it is high can only be two pictures side by side at full width;
         * a name that says "SBS" or "3D" is side by side at half width unless it says full; anything else is flat. It can be changed in the window.
         */
        fun detect(name: String, vw: Int, vh: Int): VideoMode {
            if (vw > 0 && vh > 0 && vw.toDouble() / vh >= 3.2) return SBS_FULL
            if (FULL_WORDS.containsMatchIn(name)) return SBS_FULL
            if (SBS_WORDS.containsMatchIn(name)) return SBS_HALF
            return FLAT
        }
    }
}

/**
 * One video playing into a window's surface (the texture the desktop draws). Tap the middle to pause or play, the left or right fifth to go back or on ten seconds
 * ([tapAt]); the corner at the top right changes how the picture is laid out ([VideoMode.next]).
 */
class VideoPlayer(
    private val context: Context, val id: Int, val uri: Uri,
    /** The file's size is known: the window takes its shape. */
    private val onSize: (width: Int, height: Int) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var mp: MediaPlayer? = null
    private var surface: Surface? = null
    @Volatile var prepared = false; private set
    @Volatile var videoWidth = 0; private set
    @Volatile var videoHeight = 0; private set
    private var wantPlaying = true
    private var resumeAt = 0
    @Volatile private var released = false

    val playing: Boolean get() = try { mp?.isPlaying == true } catch (_: Exception) { false }
    val positionMs: Int get() = try { mp?.currentPosition ?: 0 } catch (_: Exception) { 0 }
    val durationMs: Int get() = try { if (prepared) mp?.duration ?: 0 else 0 } catch (_: Exception) { 0 }

    /** The window's texture is there (or came back): the picture goes to it, from where it was. */
    fun attach(s: Surface) {
        if (released) return
        surface = s
        val p = mp
        if (p == null) create() else try { p.setSurface(s) } catch (_: Exception) {}
    }

    fun detachSurface() { surface = null; try { mp?.setSurface(null) } catch (_: Exception) {} }

    private fun create() {
        val s = surface ?: return
        try {
            val p = MediaPlayer()
            mp = p
            p.setDataSource(context, uri)
            p.setSurface(s)
            p.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0 && (w != videoWidth || h != videoHeight)) { videoWidth = w; videoHeight = h; onSize(w, h) } }
            p.setOnPreparedListener { m ->
                prepared = true
                if (videoWidth == 0) { videoWidth = m.videoWidth; videoHeight = m.videoHeight; onSize(videoWidth, videoHeight) }
                if (resumeAt > 0) try { m.seekTo(resumeAt) } catch (_: Exception) {}
                if (wantPlaying) try { m.start() } catch (_: Exception) {}
            }
            p.setOnCompletionListener { wantPlaying = false }
            p.setOnErrorListener { _, what, extra -> Tracking.log("video: error $what/$extra for $uri"); onError("The video could not be played"); true }
            p.prepareAsync()
        } catch (e: Exception) {
            Tracking.log("video: could not open $uri: $e")
            onError("The video could not be opened")
        }
    }

    fun togglePause(): Boolean {
        val p = mp ?: return false
        try { if (p.isPlaying) { p.pause(); wantPlaying = false } else { if (p.currentPosition >= p.duration - 300) p.seekTo(0); p.start(); wantPlaying = true } } catch (_: Exception) {}
        return wantPlaying
    }

    fun seekBy(deltaMs: Int) {
        val p = mp ?: return
        try { p.seekTo((p.currentPosition + deltaMs).coerceIn(0, maxOf(0, p.duration - 100))) } catch (_: Exception) {}
    }

    /** A tap at ([u], [v]) of the picture (0..1, v down): the zone it is in says what it does. Returns what was done, for a short word on the desktop. */
    fun tapAt(u: Double, v: Double): String = when {
        u < 0.2 -> { seekBy(-10_000); "Back 10 s" }
        u > 0.8 -> { seekBy(10_000); "On 10 s" }
        else -> if (togglePause()) "Playing" else "Paused"
    }

    /** The desktop was put away or came back: the video stops with it and goes on from where it was. */
    fun pause() { try { mp?.let { if (it.isPlaying) { it.pause() } } } catch (_: Exception) {} }
    fun resume() { try { mp?.let { if (wantPlaying && prepared && !it.isPlaying) it.start() } } catch (_: Exception) {} }

    fun release() {
        released = true
        try { mp?.setSurface(null); mp?.release() } catch (_: Exception) {}
        mp = null; surface = null
    }

    companion object {
        /** The zone at the top right of the picture that changes the layout. */
        fun inModeCorner(u: Double, v: Double) = u > 0.86 && v < 0.14
    }
}
