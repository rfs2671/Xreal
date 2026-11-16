package dev.ultraxr

import dev.ultraxr.core.FactoryCalibration
import dev.ultraxr.core.Q
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/**
 * Uses the tracking-camera section of a real Air 2 Ultra's factory calibration (as reported by
 * the hardware check on the owner's glasses) and XR Gate's independent calibration of a
 * different unit, which ships as the app's reference asset.
 */
class FactoryCalibrationTest {
    private val factory = """{"SLAM_camera":{"device_1":{"camera_model":"fisheye624","cc":[232.02590342647227,324.5942928446298],"fc":[240.86077123058172,240.92024063661967],"imu_p_cam":[-0.04967959960321735,0.00433374155753588,0.011311651098580188],"imu_q_cam":[0.06285692797149893,0.04924957365816318,0.001175119260441107,0.9968059516250889],"kc":[0.001347044168864677,0.09292397210330587,-0.12608944688553617,0.0757612423218236,-0.023502690616296274,0.002989744184794442,-0.0019208937268457163,0.0017076183796845344,0.005894207643521132,0.0004602924841651806,-0.003783511580892559,-0.0003513204828333599],"resolution":[480,640],"rolling_shutter_time":0},"device_2":{"camera_model":"fisheye624","cc":[233.92784790800658,316.4366062235467],"fc":[240.28730376723564,240.24853943907846],"imu_p_cam":[0.08760451646709011,0.004303619448099218,0.0118758365375769],"imu_q_cam":[0.06146633533269075,-0.03541011556855502,0.0029204333288411336,0.9974765583237347],"kc":[0.005502320066261727,0.0897843944656842,-0.12937333860709999,0.08215155564733244,-0.027032739862332553,0.00365380668616319,-0.0024892367183085877,-0.0016342062321606054,0.004216314726284504,0.00018120033256338274,0.005170384286702674,0.00011869369843011571],"resolution":[480,640],"rolling_shutter_time":0},"num_of_cameras":2}}"""
    private val reference: String = listOf(File("src/main/assets/calibration/basalt_calib.json"), File("app/src/main/assets/calibration/basalt_calib.json")).first { it.isFile }.readText()

    @Test fun kb4FitReproducesTheFactoryModelToUnderAPixelOnAverage() {
        for (c in FactoryCalibration.parseCameras(factory)) {
            val fit = FactoryCalibration.fitKb4(c)
            assertTrue("mean ${fit.meanErrorPx}", fit.meanErrorPx < 1.0)
            assertTrue("max ${fit.maxErrorPx}", fit.maxErrorPx < 3.5)
            // The optical axis lands on (almost) the factory principal point.
            val uv = DoubleArray(2); FactoryCalibration.projectFisheye624(c, 0.0, 0.0, 1.0, uv)
            assertEquals(c.cx, uv[0], 1e-9); assertEquals(c.cy, uv[1], 1e-9)
            assertTrue(hypot(fit.cx - c.cx, fit.cy - c.cy) < 1.5)
        }
    }

    @Test fun poseConventionsAgreeWithAnIndependentCalibrationOfAnotherUnit() {
        val ref = JSONObject(reference).getJSONObject("value0").getJSONArray("T_imu_cam")
        val cams = FactoryCalibration.parseCameras(factory)
        for (i in 0..1) {
            val pose = FactoryCalibration.poseInRawImu(cams[i])
            val r = ref.getJSONObject(i)
            val rq = Q.normalize(doubleArrayOf(r.getDouble("qx"), r.getDouble("qy"), r.getDouble("qz"), r.getDouble("qw")))
            val deg = Math.toDegrees(Q.angleBetween(rq, pose.q))
            assertTrue("camera $i rotation differs by $deg degrees", deg < 2.0)
            assertEquals(r.getDouble("px"), pose.p[0], 0.003)   // across the head: within 3 mm
            assertEquals(r.getDouble("py"), pose.p[1], 0.015); assertEquals(r.getDouble("pz"), pose.p[2], 0.015)
        }
        // Reading the quaternion the other way round (Hamilton) is clearly wrong, so the check has teeth.
        val wrong = FactoryCalibration.Camera(cams[0].fx, cams[0].fy, cams[0].cx, cams[0].cy, cams[0].k, cams[0].pImu, Q.conj(cams[0].qImuJpl))
        val r0 = ref.getJSONObject(0)
        val bad = Math.toDegrees(Q.angleBetween(Q.normalize(doubleArrayOf(r0.getDouble("qx"), r0.getDouble("qy"), r0.getDouble("qz"), r0.getDouble("qw"))), FactoryCalibration.poseInRawImu(wrong).q))
        assertTrue("wrong convention is off by $bad degrees", bad > 10.0)
    }

    @Test fun buildsAUsableCalibrationAndTheReferenceUnitIsMeasurablyDifferent() {
        val res = FactoryCalibration.build(factory, reference)
        assertNotNull(res.summary, res.basaltJson)
        assertTrue(res.summary, res.summary.startsWith("this unit's factory calibration"))
        val v = JSONObject(res.basaltJson!!).getJSONObject("value0")
        assertEquals(2, v.getJSONArray("T_imu_cam").length())
        val k = v.getJSONArray("intrinsics").getJSONObject(0)
        assertEquals("kb4", k.getString("camera_type"))
        assertEquals(240.2, k.getJSONObject("intrinsics").getDouble("fx"), 1.0)
        assertEquals(232.7, k.getJSONObject("intrinsics").getDouble("cx"), 1.0)
        // Everything the factory data does not cover is kept from the template.
        val t = JSONObject(reference).getJSONObject("value0")
        assertEquals(t.getLong("cam_time_offset_ns"), v.getLong("cam_time_offset_ns"))
        assertEquals(t.getJSONArray("resolution").toString(), v.getJSONArray("resolution").toString())
        // Why this matters: the reference unit's principal point is about 9 px away from this unit's.
        val refCx = t.getJSONArray("intrinsics").getJSONObject(0).getJSONObject("intrinsics").getDouble("cx")
        assertTrue(Math.abs(refCx - 232.7) > 6.0)
    }

    @Test fun unusableFactoryDataFallsBackWithAReason() {
        assertNull(FactoryCalibration.build("{}", reference).basaltJson)
        assertTrue(FactoryCalibration.build("not json", reference).summary.startsWith("factory calibration not usable"))
        val otherModel = factory.replace("fisheye624", "radial")
        assertTrue(FactoryCalibration.build(otherModel, reference).summary.contains("unsupported camera model"))
        // A pose far from any Air 2 Ultra's (camera turned 90 degrees) is refused rather than fed to tracking.
        val turned = factory.replace("[0.06285692797149893,0.04924957365816318,0.001175119260441107,0.9968059516250889]", "[0.7071,0,0,0.7071]")
        assertTrue(FactoryCalibration.build(turned, reference).summary.contains("conventions not as expected"))
    }
}
