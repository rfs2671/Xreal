// Copyright 2026, Ultra XR contributors.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ultraxr.openxr

import org.freedesktop.monado.ipc.PocketFeed

/**
 * Typed helpers over [PocketFeed], the JNI binding inside monado-ipc.aar (its source is patches/0001 and 0003; a copy
 * for reading is kotlin/reference/PocketFeed.java, which must NOT be added to the app's sources).
 *
 * Conventions, the same for everything fed:
 *  - Clock: System.nanoTime(), which is CLOCK_MONOTONIC, the clock Monado uses (os_monotonic_get_ns). Stamp a sample
 *    with the time it was true (IMU sample time, camera exposure time), not with the time it was processed.
 *  - Space: one right-handed tracking space, metres, +y up, -z forward, +x right, gravity aligned. The head, the
 *    controllers and the hand joints are all in it. The driver puts it into the runtime's STAGE space with the origin
 *    offset (1.6 m up by default, [setOriginOffset], [recenter]).
 *  - Orientations are unit quaternions x, y, z, w that turn the thing's own axes into the tracking space's.
 *  - Velocities are in the tracking space: metres a second, and radians a second about the tracking space's axes.
 */
object UltraXrFeed {
    const val LEFT = 0
    const val RIGHT = 1

    /** XrHandJointEXT order: the index of each joint in the array given to [hand]. */
    object Joint {
        const val PALM = 0
        const val WRIST = 1
        const val THUMB_METACARPAL = 2
        const val THUMB_PROXIMAL = 3
        const val THUMB_DISTAL = 4
        const val THUMB_TIP = 5
        const val INDEX_METACARPAL = 6
        const val INDEX_PROXIMAL = 7
        const val INDEX_INTERMEDIATE = 8
        const val INDEX_DISTAL = 9
        const val INDEX_TIP = 10
        const val MIDDLE_METACARPAL = 11
        const val MIDDLE_PROXIMAL = 12
        const val MIDDLE_INTERMEDIATE = 13
        const val MIDDLE_DISTAL = 14
        const val MIDDLE_TIP = 15
        const val RING_METACARPAL = 16
        const val RING_PROXIMAL = 17
        const val RING_INTERMEDIATE = 18
        const val RING_DISTAL = 19
        const val RING_TIP = 20
        const val LITTLE_METACARPAL = 21
        const val LITTLE_PROXIMAL = 22
        const val LITTLE_INTERMEDIATE = 23
        const val LITTLE_DISTAL = 24
        const val LITTLE_TIP = 25
        const val COUNT = 26
    }

    /** Floats a joint: position x, y, z; orientation x, y, z, w; radius. */
    const val FLOATS_PER_JOINT = 8
    const val HAND_FLOATS = Joint.COUNT * FLOATS_PER_JOINT

    /** Controller buttons for [controller]. On the left hand PRIMARY is X and SECONDARY is Y; on the right A and B. */
    object Button {
        const val PRIMARY = 0x1
        const val SECONDARY = 0x2
        /** Left hand: menu. Right hand: system (most games do not bind it). */
        const val MENU = 0x4
        const val STICK_CLICK = 0x8
        const val PRIMARY_TOUCH = 0x10
        const val SECONDARY_TOUCH = 0x20
        const val TRIGGER_TOUCH = 0x40
        const val STICK_TOUCH = 0x80
        const val THUMBREST_TOUCH = 0x100
    }

    /**
     * Before the runtime starts (before MonadoImpl is made): the glasses' display and one eye's field of view.
     * For a 3840 x 1080 side by side panel pass the whole panel; each eye gets a 1920 x 1080 half.
     */
    fun configureDisplay(widthPx: Int, heightPx: Int, refreshHz: Float, tanHalfFovX: Float, tanHalfFovY: Float) =
        PocketFeed.nativeConfigure(widthPx, heightPx, refreshHz, tanHalfFovX, tanHalfFovY)

    /**
     * Before the runtime starts: the wearer's IPD in metres (0 keeps the application's default, 63 mm), and how far
     * each eye's view centre is turned outward, as a tangent: (IPD / 2) / the distance of the glasses' virtual
     * screen. 0 renders for parallel eyes (infinity is then seen at the virtual screen's distance).
     */
    fun configureEyes(ipdM: Float, virtualScreenDistanceM: Float) =
        PocketFeed.nativeConfigureEyes(ipdM, if (virtualScreenDistanceM > 0f) (ipdM / 2f) / virtualScreenDistanceM else 0f)

    /**
     * Before the runtime starts: true when the glasses show one picture to both eyes (a 2D mode). Both views then
     * cover the whole picture from the head's centre, so the two eye pictures are never shown side by side.
     */
    fun configureSingleView(single: Boolean) = PocketFeed.nativeConfigureSingleView(single)

    /** The display's newest real vblank and measured period; a period of 0 gives Monado's pacer its own clock back. */
    fun vblank(lastVblankNs: Long, periodNs: Long) = PocketFeed.nativeVblank(lastVblankNs, periodNs)

    /** One 6DoF head pose. [positionTracked] false while the position is only held (SLAM lost). */
    @Suppress("LongParameterList")
    fun head(
        timestampNs: Long,
        px: Float, py: Float, pz: Float,
        qx: Float, qy: Float, qz: Float, qw: Float,
        angVelX: Float, angVelY: Float, angVelZ: Float,
        velX: Float, velY: Float, velZ: Float,
        positionTracked: Boolean,
    ) = PocketFeed.nativePose6(timestampNs, px, py, pz, qx, qy, qz, qw, angVelX, angVelY, angVelZ, velX, velY, velZ, positionTracked)

    /**
     * One hand's 26 joints ([HAND_FLOATS] floats, [Joint] order), at the camera frame's time. Joint orientations
     * follow XR_EXT_hand_tracking: -z along the bone toward the finger tip, +y out of the back of the hand, +x to the
     * right of that. Call with [active] false as soon as the hand is lost.
     */
    fun hand(hand: Int, timestampNs: Long, joints: FloatArray?, active: Boolean) {
        require(!active || (joints != null && joints.size >= HAND_FLOATS)) { "a hand needs $HAND_FLOATS floats" }
        PocketFeed.nativeHand(hand, timestampNs, joints, active)
    }

    /** Writes one joint into a [HAND_FLOATS] array. */
    @Suppress("LongParameterList")
    fun putJoint(joints: FloatArray, joint: Int, px: Float, py: Float, pz: Float, qx: Float, qy: Float, qz: Float, qw: Float, radiusM: Float) {
        val i = joint * FLOATS_PER_JOINT
        joints[i] = px; joints[i + 1] = py; joints[i + 2] = pz
        joints[i + 3] = qx; joints[i + 4] = qy; joints[i + 5] = qz; joints[i + 6] = qw
        joints[i + 7] = radiusM
    }

    /** Positions are carried on past the newest sample for at most [maxPredictMs]; a hand silent for [timeoutMs] goes inactive. */
    fun configureHands(maxPredictMs: Float = 30f, timeoutMs: Float = 200f) = PocketFeed.nativeConfigureHands(maxPredictMs, timeoutMs)

    /**
     * One controller state (an Oculus Touch shaped device): the grip pose with its velocities, [Button] bits, trigger
     * and squeeze 0 to 1, the stick -1 to 1 (up and right positive). Feed it at least every 150 ms while the hand is
     * seen: after 150 ms without a state the pose stops being tracked, after 2 s the inputs go inactive.
     */
    @Suppress("LongParameterList")
    fun controller(
        hand: Int, timestampNs: Long,
        px: Float, py: Float, pz: Float,
        qx: Float, qy: Float, qz: Float, qw: Float,
        angVelX: Float = 0f, angVelY: Float = 0f, angVelZ: Float = 0f,
        velX: Float = 0f, velY: Float = 0f, velZ: Float = 0f,
        buttons: Int = 0, trigger: Float = 0f, squeeze: Float = 0f, stickX: Float = 0f, stickY: Float = 0f,
    ) = PocketFeed.nativeController(hand, timestampNs, px, py, pz, qx, qy, qz, qw, angVelX, angVelY, angVelZ, velX, velY, velZ, buttons, trigger, squeeze, stickX, stickY)

    /** The controller's aim pose when it is not the grip pose (a hand's pointing ray). Feed it before [controller]. */
    @Suppress("LongParameterList")
    fun controllerAim(
        hand: Int, timestampNs: Long,
        px: Float, py: Float, pz: Float,
        qx: Float, qy: Float, qz: Float, qw: Float,
        angVelX: Float = 0f, angVelY: Float = 0f, angVelZ: Float = 0f,
        velX: Float = 0f, velY: Float = 0f, velZ: Float = 0f,
    ) = PocketFeed.nativeControllerAim(hand, timestampNs, px, py, pz, qx, qy, qz, qw, angVelX, angVelY, angVelZ, velX, velY, velZ)

    /**
     * A pinch as a trigger, with hysteresis so the click does not chatter: [distanceM] is the distance between the
     * thumb tip and the index tip. Returns the trigger value 0 to 1; games read a click from it at their own threshold.
     */
    fun pinchToTrigger(distanceM: Float, wasPressed: Boolean, pressAtM: Float = 0.015f, releaseAtM: Float = 0.03f, openAtM: Float = 0.08f): Float {
        val pressed = if (wasPressed) distanceM < releaseAtM else distanceM < pressAtM
        if (pressed) return 1f
        // Below 1 while closing, so a game's analogue trigger moves with the fingers.
        return (0.9f * (1f - (distanceM - releaseAtM) / (openAtM - releaseAtM))).coerceIn(0f, 0.9f)
    }

    /** Where the tracking space sits in STAGE: its origin's position in STAGE and its orientation. Default (0, 1.6, 0). */
    fun setOriginOffset(px: Float, py: Float, pz: Float, qx: Float = 0f, qy: Float = 0f, qz: Float = 0f, qw: Float = 1f) =
        PocketFeed.nativeSetOriginOffset(px, py, pz, qx, qy, qz, qw)

    /** The head's newest pose becomes the STAGE origin facing forward (yaw only, height kept). False before any head pose. */
    fun recenter(): Boolean = PocketFeed.nativeRecenter()
}
