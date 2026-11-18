package dev.ultraxr

import dev.ultraxr.core.StillnessDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** "Lying still" from the motion sensor, with noise levels measured on the glasses (see StillnessDetector). */
class StillnessTest {
    private val ms = 1_000_000L
    private fun deg(d: Double) = d * Math.PI / 180

    /** Feeds [seconds] of 1 kHz samples: a constant gyro bias plus Gaussian scatter of the given size, gravity plus scatter. */
    private fun feed(d: StillnessDetector, startMs: Long, seconds: Double, gyroStdDegS: Double, accelStd: Double, rnd: Random, turnDegS: Double = 0.0): Long {
        var t = startMs
        val n = (seconds * 1000).toInt()
        for (i in 0 until n) {
            val g = FloatArray(3) { (0.013 + deg(gyroStdDegS) / Math.sqrt(3.0) * rnd.nextGaussian()).toFloat() }
            g[1] += deg(turnDegS).toFloat()
            val a = floatArrayOf((accelStd / Math.sqrt(3.0) * rnd.nextGaussian()).toFloat(), (9.81 + accelStd / Math.sqrt(3.0) * rnd.nextGaussian()).toFloat(), (accelStd / Math.sqrt(3.0) * rnd.nextGaussian()).toFloat())
            d.onImu(t * ms, g, a); t++
        }
        return t
    }

    @Test fun lyingOnATableIsStillAfterASecondDespiteTheGyroBias() {
        val d = StillnessDetector(); val r = Random(1)
        var t = feed(d, 1000, 0.9, 0.105, 0.033, r)
        assertFalse("not within the first second", d.still)
        t = feed(d, t, 1.0, 0.105, 0.033, r)
        assertTrue(d.still)
        feed(d, t, 60.0, 0.105, 0.065, r)
        assertTrue("and stays so for a minute at the noisiest level measured", d.still)
    }

    @Test fun theStillestHeadMeasuredIsNotStill() {
        val d = StillnessDetector(); val r = Random(2)
        feed(d, 1000, 30.0, 1.26, 0.097, r)       // the 1st percentile of the worn recording
        assertFalse(d.still)
    }

    @Test fun pickingTheGlassesUpEndsStillnessAtOnce() {
        val d = StillnessDetector(); val r = Random(3)
        var t = feed(d, 1000, 3.0, 0.105, 0.033, r)
        assertTrue(d.still)
        t = feed(d, t, 0.02, 0.105, 0.033, r, turnDegS = 15.0)      // 20 ms of being lifted
        assertFalse("within 20 ms, not at the end of the window", d.still)
        feed(d, t, 0.6, 5.0, 0.3, r)
        assertFalse(d.still)
    }

    @Test fun tooFewSamplesCannotSayStill() {
        // The sensor normally reports 1000 times a second. At 10 a second (a starved USB link) a quiet-looking
        // half second holds five samples, which is no evidence of anything.
        val d = StillnessDetector()
        for (i in 0 until 50) d.onImu((1000L + i * 100) * ms, floatArrayOf(0.013f, 0.013f, 0.013f), floatArrayOf(0f, 9.81f, 0f))
        assertFalse(d.still)
    }
}
