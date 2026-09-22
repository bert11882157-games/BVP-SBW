package com.atsuishio.superbwarfare.api.vehicle.aim

/** Immutable per-seat/per-selected-weapon servo policy. Angles use VehicleEntity's native fields. */
class VehicleAimProfile private constructor(builder: Builder) {
    val channel: VehicleAimChannel = builder.channel
    val directionFrame: VehicleAimDirectionFrame = builder.directionFrame
    val yawRateDegreesPerSecond: Float = builder.yawRateDegreesPerSecond
    val pitchRateDegreesPerSecond: Float = builder.pitchRateDegreesPerSecond
    val minYaw: Float = builder.minYaw
    val maxYaw: Float = builder.maxYaw
    val minPitch: Float = builder.minPitch
    val maxPitch: Float = builder.maxPitch
    val softYawLimitDegrees: Float = builder.softYawLimitDegrees
    val softPitchLimitDegrees: Float = builder.softPitchLimitDegrees
    val lockToleranceDegrees: Float = builder.lockToleranceDegrees
    val defaultMode: VehicleAimMode = builder.defaultMode
    val snapToNeutralWhenInactive: Boolean = builder.snapToNeutralWhenInactive
    val neutralYaw: Float = builder.neutralYaw
    val neutralPitch: Float = builder.neutralPitch
    /** Camera/muzzle geometric zero used by eligible ground TURRET profiles. */
    val geometricZeroDistanceBlocks: Int = builder.geometricZeroDistanceBlocks

    init {
        require(yawRateDegreesPerSecond.isFinite() && yawRateDegreesPerSecond >= 0F)
        require(pitchRateDegreesPerSecond.isFinite() && pitchRateDegreesPerSecond >= 0F)
        require(minYaw.isFinite() && maxYaw.isFinite() && minYaw <= maxYaw)
        require(minPitch.isFinite() && maxPitch.isFinite() && minPitch <= maxPitch)
        require(softYawLimitDegrees.isFinite() && softYawLimitDegrees >= 0F)
        require(softPitchLimitDegrees.isFinite() && softPitchLimitDegrees >= 0F)
        require(lockToleranceDegrees.isFinite() && lockToleranceDegrees >= 0F)
        require(neutralYaw.isFinite() && neutralPitch.isFinite())
        require(isSupportedGeometricZeroDistance(geometricZeroDistanceBlocks)) {
            "geometric zero must be 50, 100, or 200 blocks"
        }
        require(directionFrame != VehicleAimDirectionFrame.PASSENGER_STATION_LOCAL ||
                channel == VehicleAimChannel.PASSENGER_WEAPON)
    }

    class Builder internal constructor(internal val channel: VehicleAimChannel) {
        internal var directionFrame = VehicleAimDirectionFrame.LEGACY_WORLD
        internal var yawRateDegreesPerSecond = 0F
        internal var pitchRateDegreesPerSecond = 0F
        internal var minYaw = -180F
        internal var maxYaw = 180F
        internal var minPitch = -90F
        internal var maxPitch = 90F
        internal var softYawLimitDegrees = 0F
        internal var softPitchLimitDegrees = 0F
        internal var lockToleranceDegrees = 0.35F
        internal var defaultMode = VehicleAimMode.PLAYER_LOOK_AIM
        internal var snapToNeutralWhenInactive = false
        internal var neutralYaw = 0F
        internal var neutralPitch = 0F
        internal var geometricZeroDistanceBlocks = DEFAULT_GEOMETRIC_ZERO_DISTANCE_BLOCKS

        fun rates(yawDegreesPerSecond: Float, pitchDegreesPerSecond: Float) = apply {
            yawRateDegreesPerSecond = yawDegreesPerSecond
            pitchRateDegreesPerSecond = pitchDegreesPerSecond
        }

        fun yawRange(minimum: Float, maximum: Float) = apply {
            minYaw = minimum
            maxYaw = maximum
        }

        fun pitchRange(minimum: Float, maximum: Float) = apply {
            minPitch = minimum
            maxPitch = maximum
        }

        fun softLimits(yawDegrees: Float, pitchDegrees: Float) = apply {
            softYawLimitDegrees = yawDegrees
            softPitchLimitDegrees = pitchDegrees
        }

        fun lockTolerance(degrees: Float) = apply { lockToleranceDegrees = degrees }

        fun defaultMode(mode: VehicleAimMode) = apply { defaultMode = mode }

        fun directionFrame(frame: VehicleAimDirectionFrame) = apply { directionFrame = frame }

        fun snapToNeutralWhenInactive(yaw: Float, pitch: Float) = apply {
            snapToNeutralWhenInactive = true
            neutralYaw = yaw
            neutralPitch = pitch
        }

        /** Select one of the supported camera/muzzle convergence distances. */
        fun geometricZeroDistanceBlocks(distanceBlocks: Int) = apply {
            require(isSupportedGeometricZeroDistance(distanceBlocks)) {
                "geometric zero must be 50, 100, or 200 blocks"
            }
            geometricZeroDistanceBlocks = distanceBlocks
        }

        fun build(): VehicleAimProfile = VehicleAimProfile(this)
    }

    companion object {
        const val DEFAULT_GEOMETRIC_ZERO_DISTANCE_BLOCKS: Int = 100

        @JvmStatic
        fun isSupportedGeometricZeroDistance(distanceBlocks: Int): Boolean =
            distanceBlocks == 50 || distanceBlocks == DEFAULT_GEOMETRIC_ZERO_DISTANCE_BLOCKS ||
                    distanceBlocks == 200

        @JvmStatic
        fun builder(channel: VehicleAimChannel): Builder = Builder(channel)
    }
}
