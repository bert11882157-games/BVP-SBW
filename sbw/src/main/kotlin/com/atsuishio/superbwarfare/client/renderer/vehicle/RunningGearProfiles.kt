package com.atsuishio.superbwarfare.client.renderer.vehicle

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.data.CustomData
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.resource.vehicle.RunningGearResource
import com.atsuishio.superbwarfare.resource.vehicle.VehicleResource
import java.util.Locale
import java.util.WeakHashMap

enum class RunningGearKind {
    WHEELED,
    TRACKED,
}

enum class TrackRenderMode {
    LINKS,
}

enum class TrackLinkFit {
    CONTACT_INTERVAL,
    RIGID_PATH,
}

enum class RunningGearSide {
    LEFT,
    RIGHT,
}

data class TrackBoundsProfile(
    val yCenter: Float,
    val radius: Float,
    val zRear: Float,
    val zFront: Float,
)

data class TrackSideProfile(
    val side: RunningGearSide,
    val direction: Int,
    val trackBone: String,
    val brokenBone: String,
    val fallbackBone: String,
    val linkMovePrefix: String,
    val linkRotationPrefix: String,
    val legacyFramePrefix: String,
    /** Optional explicit path; null retains the legacy shared TrackRender.Path. */
    val path: TrackPathProfile?,
)

data class TrackPathKeyframe(val phase: Float, val value: Float)

data class TrackPathProfile(
    val sourceYMin: Float,
    val sourceYMax: Float,
    val sourceZMin: Float,
    val sourceZMax: Float,
    val moveY: List<TrackPathKeyframe>,
    val moveZ: List<TrackPathKeyframe>,
    val rotationX: List<TrackPathKeyframe>,
)

/** Runtime belt around the wheels (TrackBeltPath); `enabled` false keeps the authored path. */
data class TrackAutoProfile @JvmOverloads constructor(
    val enabled: Boolean = true,
    val offsetX: Float = 0F,
    val offsetY: Float = 0F,
    val radii: Map<String, Float> = emptyMap(),
    val leftOrder: List<String> = emptyList(),
    val rightOrder: List<String> = emptyList(),
    val farLinksBlocks: Float = 96F,
) {
    fun order(side: RunningGearSide): List<String> = if (side == RunningGearSide.LEFT) leftOrder else rightOrder

    companion object {
        @JvmField val DEFAULT = TrackAutoProfile()
    }
}

data class TrackRenderProfile @JvmOverloads constructor(
    val mode: TrackRenderMode,
    val linkCount: Int,
    val phaseDistance: Float,
    val travelScale: Float,
    val evaluationLayout: TrackBoundsProfile,
    val sides: Map<RunningGearSide, TrackSideProfile>,
    val path: TrackPathProfile,
    val linkHalfThickness: Float = 0F,
    val linkFit: TrackLinkFit = TrackLinkFit.CONTACT_INTERVAL,
    val auto: TrackAutoProfile = TrackAutoProfile.DEFAULT,
) {
    fun side(side: RunningGearSide): TrackSideProfile = requireNotNull(sides[side])

    /** Resolves the exact side path, falling back to the legacy shared path. */
    fun path(side: RunningGearSide): TrackPathProfile = side(side).path ?: this.path
}

data class WheelSteeringProfile(
    val steeringBone: String,
    val wheelBone: String,
    val maxAngleDegrees: Float,
    val sign: Int,
)

data class RunningGearProfile @JvmOverloads constructor(
    val leftWheelBones: List<String>,
    val rightWheelBones: List<String>,
    val trackRender: TrackRenderProfile?,
    val wheelSteering: List<WheelSteeringProfile> = emptyList(),
)

/** Validates raw resource data once and exposes immutable renderer-facing profiles. */
object RunningGearProfiles {
    private data class Cached(
        val profile: RunningGearProfile?,
        val error: String?,
        var errorLogged: Boolean = false,
    )

    private val cache = WeakHashMap<RunningGearResource, Cached>()

    /**
     * Returns the exact immutable resource object used as the profile-cache key.
     * This is a read-only identity token for render caches; it does not allocate.
     */
    @JvmStatic
    fun resourceIdentity(vehicle: VehicleEntity): RunningGearResource? =
        CustomData.VEHICLE_RESOURCE.get(VehicleResource.getRegistryId(vehicle.type))?.runningGear

    @JvmStatic
    fun resolve(vehicle: VehicleEntity): RunningGearProfile? {
        val raw = VehicleResource.getDefault(vehicle).runningGear ?: return null
        var shouldLogError = false
        val cached = synchronized(cache) {
            val value = cache[raw] ?: validate(raw).also { cache[raw] = it }
            shouldLogError = value.error != null && !value.errorLogged
            value.errorLogged = value.errorLogged || shouldLogError
            value
        }
        if (shouldLogError) {
            Mod.LOGGER.warn(
                "Ignoring invalid running-gear client profile for {}: {}",
                VehicleResource.getRegistryId(vehicle.type),
                cached.error,
            )
        }
        return cached.profile
    }

    private fun validate(raw: RunningGearResource): Cached =
        try {
            val kind = enumValue<RunningGearKind>(raw.type, "Type")
            require(raw.roadWheelCount > 0) { "RoadWheelCount must be positive" }
            val wheelBones = raw.wheelBones
            val leftWheels = validateNames(wheelBones.left, "WheelBones.Left")
            val rightWheels = validateNames(wheelBones.right, "WheelBones.Right")
            require(leftWheels.isNotEmpty() && rightWheels.isNotEmpty()) {
                "both wheel-bone lists must be non-empty"
            }

            val track = raw.trackRender?.let(::validateTrack)
            require((kind == RunningGearKind.TRACKED) == (track != null)) {
                "TRACKED profiles require TrackRender and WHEELED profiles must omit it"
            }
            val steering = raw.steering?.let {
                require(kind == RunningGearKind.WHEELED) { "Steering requires WHEELED running gear" }
                validateSteering(it, leftWheels, rightWheels)
            } ?: emptyList()
            Cached(
                RunningGearProfile(leftWheels, rightWheels, track, steering),
                null,
            )
        } catch (exception: IllegalArgumentException) {
            Cached(null, exception.message ?: exception.javaClass.simpleName)
        }

    private fun validateSteering(
        raw: RunningGearResource.SteeringRig,
        leftWheels: List<String>,
        rightWheels: List<String>,
    ): List<WheelSteeringProfile> {
        require(raw.schema == 1) { "Steering.Schema must be 1" }
        val wheels = requireNotNull(raw.wheels) { "Steering.Wheels is required" }
        require(wheels.size in 1..16) { "Steering.Wheels must contain 1..16 entries" }
        val wheelNames = (leftWheels + rightWheels).toSet()
        require(wheelNames.size == leftWheels.size + rightWheels.size) {
            "Steering requires distinct WheelBones identities"
        }
        val steeringNames = HashSet<String>()
        val assignedWheels = HashSet<String>()
        val profiles = wheels.map { entry ->
            val wheel = requireNotNull(entry) { "Steering.Wheels cannot contain null" }
            val steeringBone = requireName(wheel.steeringBone, "SteeringBone")
            val wheelBone = requireName(wheel.wheelBone, "WheelBone")
            require(steeringBone.length <= 96 && wheelBone.length <= 96) { "steering bone name too long" }
            require(wheelBone in wheelNames && assignedWheels.add(wheelBone)) {
                "Steering.WheelBone must identify one distinct declared spin bone"
            }
            require(steeringBone !in wheelNames && steeringNames.add(steeringBone)) {
                "SteeringBone must be distinct from every spin bone and other steering parent"
            }
            val maximum = requireNotNull(wheel.maxAngleDegrees) { "MaxAngleDegrees is required" }
            require(maximum.isFinite() && maximum > 0F && maximum <= 90F) {
                "MaxAngleDegrees must be finite and in (0,90]"
            }
            val sign = requireNotNull(wheel.sign) { "Sign is required" }
            require(sign == -1 || sign == 1) { "Sign must be -1 or 1" }
            WheelSteeringProfile(steeringBone, wheelBone, maximum, sign)
        }
        return java.util.List.copyOf(profiles)
    }

    private fun validateTrack(raw: RunningGearResource.TrackRender): TrackRenderProfile {
        val mode = enumValue<TrackRenderMode>(raw.mode, "TrackRender.Mode")
        val linkFit = enumValue<TrackLinkFit>(raw.linkFit, "TrackRender.LinkFit")
        require(raw.linkCount > 0) { "TrackRender.LinkCount must be positive" }
        requireFinitePositive(raw.phaseDistance, "TrackRender.PhaseDistance")
        require(raw.travelScale.isFinite() && raw.travelScale >= 0.0F) {
            "TrackRender.TravelScale must be finite and non-negative"
        }
        require(raw.linkHalfThickness.isFinite() && raw.linkHalfThickness in 0F..64F) {
            "TrackRender.LinkHalfThickness must be finite and between zero and 64 model units"
        }
        if (linkFit == TrackLinkFit.RIGID_PATH) {
            require(raw.travelScale == 1F && kotlin.math.abs(raw.phaseDistance * raw.linkCount - 100F) < 0.001F) {
                "Rigid track links require unit travel scale and one complete closed-path phase cycle"
            }
        }
        val evaluationLayout = validateBounds(
            requireNotNull(raw.evaluationLayout) { "TrackRender.EvaluationLayout is required" },
            "TrackRender.EvaluationLayout",
        )
        val sides = LinkedHashMap<RunningGearSide, TrackSideProfile>(2)
        for (rawSide in raw.sides) {
            val side = validateSide(requireNotNull(rawSide) { "TrackRender.Sides cannot contain null" }, mode)
            require(sides.put(side.side, side) == null) { "TrackRender.Sides must contain exactly LEFT and RIGHT" }
        }
        require(sides.size == 2) { "TrackRender.Sides must contain exactly LEFT and RIGHT" }
        val explicitSidePathCount = sides.values.count { it.path != null }
        require(explicitSidePathCount == 0 || explicitSidePathCount == 2) {
            "TrackRender.Sides.Path must be present for both LEFT and RIGHT or omitted for both"
        }
        val path = validatePath(requireNotNull(raw.path) { "TrackRender.Path is required" })
        return TrackRenderProfile(
            mode,
            raw.linkCount,
            raw.phaseDistance,
            raw.travelScale,
            evaluationLayout,
            sides,
            path,
            raw.linkHalfThickness,
            linkFit,
            raw.auto?.let(::validateAuto) ?: TrackAutoProfile.DEFAULT,
        )
    }

    private fun validateAuto(raw: RunningGearResource.TrackAuto): TrackAutoProfile {
        require(raw.offsetX.isFinite() && kotlin.math.abs(raw.offsetX) <= 64F) { "TrackRender.Auto.OffsetX must be finite, |x| <= 64" }
        require(raw.offsetY.isFinite() && kotlin.math.abs(raw.offsetY) <= 64F) { "TrackRender.Auto.OffsetY must be finite, |y| <= 64" }
        require(raw.farLinksBlocks.isFinite() && raw.farLinksBlocks >= 0F) { "TrackRender.Auto.FarLinksBlocks must be >= 0" }
        val radii = LinkedHashMap<String, Float>()
        for ((name, value) in raw.radii) {
            requireName(name, "TrackRender.Auto.Radii bone")
            require(value != null && value.isFinite() && value > 0F && value <= 256F) { "TrackRender.Auto.Radii.$name must be in (0,256]" }
            radii[name] = value
        }
        val order = raw.order
        return TrackAutoProfile(raw.isEnabled, raw.offsetX, raw.offsetY, java.util.Map.copyOf(radii),
            order?.let { validateNames(it.left, "TrackRender.Auto.Order.Left") } ?: emptyList(),
            order?.let { validateNames(it.right, "TrackRender.Auto.Order.Right") } ?: emptyList(),
            raw.farLinksBlocks)
    }

    private fun validateSide(
        raw: RunningGearResource.TrackSide,
        mode: TrackRenderMode,
    ): TrackSideProfile {
        val sideName = raw.side
        val side = when (sideName?.uppercase(Locale.ROOT)) {
            "L", "LEFT" -> RunningGearSide.LEFT
            "R", "RIGHT" -> RunningGearSide.RIGHT
            else -> throw IllegalArgumentException("invalid TrackRender side $sideName")
        }
        require(raw.direction == -1 || raw.direction == 1) {
            "TrackRender side ${raw.side} Direction must be -1 or 1"
        }
        validateBounds(raw, "TrackRender side ${raw.side}")
        require(raw.xCenter.isFinite() && raw.yBottom.isFinite() && raw.yTop.isFinite()) {
            "TrackRender side ${raw.side} coordinates must be finite"
        }
        require(raw.yTop > raw.yBottom) { "TrackRender side ${raw.side} YTop must exceed YBottom" }
        raw.roadCenters.forEach { value ->
            require(value.isFinite()) { "TrackRender side ${raw.side} RoadCenters must be finite" }
        }
        val movePrefix = if (mode == TrackRenderMode.LINKS) {
            requireName(raw.linkMovePrefix, "TrackRender side ${raw.side} LinkMovePrefix")
        } else raw.linkMovePrefix.orEmpty()
        val rotationPrefix = if (mode == TrackRenderMode.LINKS) {
            requireName(raw.linkRotationPrefix, "TrackRender side ${raw.side} LinkRotationPrefix")
        } else raw.linkRotationPrefix.orEmpty()
        return TrackSideProfile(
            side,
            raw.direction,
            requireName(raw.trackBone, "TrackRender side ${raw.side} TrackBone"),
            requireName(raw.brokenBone, "TrackRender side ${raw.side} BrokenBone"),
            requireName(raw.fallbackBone, "TrackRender side ${raw.side} FallbackBone"),
            movePrefix,
            rotationPrefix,
            raw.legacyFramePrefix.orEmpty(),
            raw.path?.let { validatePath(it, "TrackRender side ${raw.side} Path") },
        )
    }

    private fun validateBounds(raw: RunningGearResource.TrackBounds, name: String): TrackBoundsProfile {
        require(raw.yCenter.isFinite()) { "$name YCenter must be finite" }
        requireFinitePositive(raw.radius, "$name Radius")
        require(raw.zRear.isFinite() && raw.zFront.isFinite() && raw.zFront > raw.zRear) {
            "$name ZFront must be finite and exceed ZRear"
        }
        return TrackBoundsProfile(raw.yCenter, raw.radius, raw.zRear, raw.zFront)
    }

    private fun validatePath(
        raw: RunningGearResource.TrackPath,
        namePrefix: String = "TrackRender.Path",
    ): TrackPathProfile {
        require(raw.sourceYMin.isFinite() && raw.sourceYMax.isFinite() && raw.sourceYMax > raw.sourceYMin) {
            "$namePrefix source Y range is invalid"
        }
        require(raw.sourceZMin.isFinite() && raw.sourceZMax.isFinite() && raw.sourceZMax > raw.sourceZMin) {
            "$namePrefix source Z range is invalid"
        }
        return TrackPathProfile(
            raw.sourceYMin,
            raw.sourceYMax,
            raw.sourceZMin,
            raw.sourceZMax,
            validateCurve(raw.moveY, "$namePrefix.MoveY"),
            validateCurve(raw.moveZ, "$namePrefix.MoveZ"),
            validateCurve(raw.rotationX, "$namePrefix.RotationX"),
        )
    }

    private fun validateCurve(raw: Array<out FloatArray?>, name: String): List<TrackPathKeyframe> {
        require(raw.size >= 2) { "$name must contain at least two keyframes" }
        val points = raw.mapIndexed { index, rawValues ->
            val values = requireNotNull(rawValues) {
                "$name keyframe $index must be [finite phase, finite value]"
            }
            require(values.size == 2 && values[0].isFinite() && values[1].isFinite()) {
                "$name keyframe $index must be [finite phase, finite value]"
            }
            TrackPathKeyframe(values[0], values[1])
        }
        require(points.first().phase <= 0.0F && points.last().phase >= 100.0F) {
            "$name must cover phase 0 through 100"
        }
        for (index in 1 until points.size) {
            require(points[index].phase > points[index - 1].phase) {
                "$name phases must be strictly increasing"
            }
        }
        return points
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String?, name: String): T =
        try {
            enumValueOf(requireName(value, name).uppercase(Locale.ROOT))
        } catch (exception: IllegalArgumentException) {
            throw IllegalArgumentException("$name has unsupported value $value")
        }

    private fun validateNames(values: Array<out String?>, name: String): List<String> =
        values.mapIndexed { index, value -> requireName(value, "$name[$index]") }

    private fun requireName(value: String?, name: String): String {
        require(!value.isNullOrBlank()) { "$name must be non-empty" }
        return value
    }

    private fun requireFinitePositive(value: Float, name: String) {
        require(value.isFinite() && value > 0.0F) { "$name must be finite and positive" }
    }
}
