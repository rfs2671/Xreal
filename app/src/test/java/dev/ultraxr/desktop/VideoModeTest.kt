package dev.ultraxr.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoModeTest {
    @Test fun aPictureThreeTimesAsWideAsHighIsFullWidthSideBySide() {
        assertEquals(VideoMode.SBS_FULL, VideoMode.detect("movie.mp4", 3840, 1080))
        assertEquals(VideoMode.SBS_FULL, VideoMode.detect("movie.mp4", 7680, 2160))
    }
    @Test fun aNameThatSaysSbsOrThreeDIsHalfWidthUnlessItSaysFull() {
        assertEquals(VideoMode.SBS_HALF, VideoMode.detect("Film.SBS.1080p.mp4", 1920, 1080))
        assertEquals(VideoMode.SBS_HALF, VideoMode.detect("film_HSBS.mkv", 1920, 1080))
        assertEquals(VideoMode.SBS_HALF, VideoMode.detect("film 3D.mp4", 1920, 1080))
        assertEquals(VideoMode.SBS_HALF, VideoMode.detect("film-side-by-side.mp4", 1920, 1080))
        assertEquals(VideoMode.SBS_FULL, VideoMode.detect("film.FSBS.mp4", 1920, 1080))
    }
    @Test fun anythingElseIsFlat() {
        assertEquals(VideoMode.FLAT, VideoMode.detect("holiday.mp4", 1920, 1080))
        assertEquals(VideoMode.FLAT, VideoMode.detect("kids3dprint.mp4", 1920, 1080))     // "3d" inside a word is not a mark
        assertEquals(VideoMode.FLAT, VideoMode.detect("sbsfoo.mp4", 1920, 1080))
        assertEquals(VideoMode.FLAT, VideoMode.detect("x.mp4", 0, 0))
    }
    @Test fun eachEyesPictureHasTheRightShape() {
        assertEquals(16.0 / 9.0, VideoMode.FLAT.aspect(1920, 1080), 1e-9)
        assertEquals(16.0 / 9.0, VideoMode.SBS_HALF.aspect(1920, 1080), 1e-9)       // each half 960 x 1080, stretched back to 16:9
        assertEquals(16.0 / 9.0, VideoMode.SBS_FULL.aspect(3840, 1080), 1e-9)       // each half 1920 x 1080
        assertEquals(16.0 / 9.0, VideoMode.FLAT.aspect(0, 0), 1e-9)
    }
    @Test fun theLayoutCyclesThroughAllThree() {
        assertEquals(VideoMode.SBS_HALF, VideoMode.FLAT.next()); assertEquals(VideoMode.SBS_FULL, VideoMode.SBS_HALF.next()); assertEquals(VideoMode.FLAT, VideoMode.SBS_FULL.next())
        assertTrue(VideoMode.SBS_HALF.sbs && VideoMode.SBS_FULL.sbs && !VideoMode.FLAT.sbs)
    }
}
