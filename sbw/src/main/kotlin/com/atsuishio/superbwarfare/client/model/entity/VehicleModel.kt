package com.atsuishio.superbwarfare.client.model.entity

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.Mod.Companion.loc
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimOpticalCameraPolicy
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimReticleProfileProvider
import com.atsuishio.superbwarfare.client.RenderHelper
import com.atsuishio.superbwarfare.client.renderer.vehicle.RunningGearRenderState
import com.atsuishio.superbwarfare.client.renderer.vehicle.RunningGearProfile
import com.atsuishio.superbwarfare.client.renderer.vehicle.RunningGearProfiles
import com.atsuishio.superbwarfare.client.renderer.vehicle.RunningGearSide
import com.atsuishio.superbwarfare.client.renderer.vehicle.RunningGearTrackEvaluator
import com.atsuishio.superbwarfare.client.renderer.vehicle.TrackRenderMode
import com.atsuishio.superbwarfare.client.renderer.vehicle.VehicleRenderPartSnapshot
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.event.ClientEventHandler
import com.atsuishio.superbwarfare.resource.vehicle.DefaultVehicleResource
import com.atsuishio.superbwarfare.resource.vehicle.VehicleResource
import com.atsuishio.superbwarfare.tools.ResourceOnceLogger
import net.minecraft.client.Minecraft
import net.minecraft.client.CameraType
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Player
import software.bernie.geckolib.core.animatable.GeoAnimatable
import software.bernie.geckolib.core.animatable.model.CoreGeoBone
import software.bernie.geckolib.core.animation.AnimationState
import software.bernie.geckolib.model.GeoModel
import java.util.regex.Pattern

open class VehicleModel<T> : GeoModel<T>() where T : VehicleEntity, T : GeoAnimatable {
    protected var leftWheelRot = 0f
    protected var rightWheelRot = 0f
    protected var leftTrack = 0f
    protected var rightTrack = 0f

    protected var turretYRot = 0f

    protected var turretXRot = 0f
    protected var barrelPitch = 0f
    protected var recoilShake = 0f
    protected var passengerWeaponStationYaw = 0f
    protected var passengerWeaponStationPitchRadians = 0f
    protected var nativeTurretHidden = false
    protected var rudderRotation = 0f
    protected var runningGearTrackAnimationLength = 1
    protected var runningGearProfile: RunningGearProfile? = null
    private val profiledTrackPose = FloatArray(4)

    protected var hideForTurretControllerWhileZooming = false
    protected var hideForPassengerWeaponStationControllerWhileZooming = false

    private val LOGGER = ResourceOnceLogger()

    override fun getAnimationResource(vehicle: T): ResourceLocation? {
        return getDefault(vehicle).model.animation
    }

    protected var modelCache: ResourceLocation? = null

    override fun getModelResource(vehicle: T): ResourceLocation? {
        if (RenderHelper.isInGui()) {
            return getDefault(vehicle).model.model
        }

        val lodLevel = getLODLevel(vehicle)
        val lodModel: ResourceLocation? = getDefault(vehicle).model.getLODModel(lodLevel)

        if (lodModel == null) {
            if (modelCache != null) {
                return modelCache
            }

            LOGGER.log(vehicle) { logger -> logger.error("failed to load model for {}!", vehicle) }
            val loc = loc("geo/" + EntityType.getKey(vehicle.type).path + ".geo.json")
            modelCache = loc
            return loc
        }

        modelCache = lodModel
        return lodModel
    }

    fun getPreciseModelResource(vehicle: T): ResourceLocation? = getDefault(vehicle).model.model

    protected var textureCache: ResourceLocation? = null

    override fun getTextureResource(vehicle: T): ResourceLocation? {
        if (RenderHelper.isInGui()) {
            return getDefault(vehicle).model.texture
        }

        val lodLevel = getLODLevel(vehicle)
        val lodTexture: ResourceLocation? = getDefault(vehicle).model.getLODTexture(lodLevel)

        if (lodTexture == null) {
            if (textureCache != null) {
                return textureCache
            }

            LOGGER.log(vehicle) { logger -> logger.error("failed to load texture for {}!", vehicle) }
            val loc = loc("textures/entity/" + EntityType.getKey(vehicle.type).path + ".png")
            textureCache = loc
            return loc
        }

        textureCache = lodTexture
        return lodTexture
    }

    fun getPreciseTextureResource(vehicle: T): ResourceLocation? = getDefault(vehicle).model.texture

    fun getLODLevel(vehicle: T): Int {
        val defaultData: DefaultVehicleResource = getDefault(vehicle)
        val model = defaultData.model
        if (defaultData.lodDistance == null || defaultData.lodDistance.list.isEmpty() || !model.hasLOD()) return 0

        val player: Player? = Minecraft.getInstance().player
        if (player == null || player.isScoping) return 0

        val distance = player.position().distanceTo(vehicle.position())
        for (i in defaultData.lodDistance.list.indices) {
            if (distance <= defaultData.lodDistance.list[i]) {
                return i
            }
        }

        return Int.MAX_VALUE
    }

    fun interface TransformContext<T> where T : VehicleEntity, T : GeoAnimatable {
        fun transform(bone: CoreGeoBone, vehicle: T, animationState: AnimationState<T>)
    }

    /** Compiled bindings are keyed by the active model/LOD bone set and survive safe LOD swaps. */
    private val transformsByBoneSet = mutableMapOf<List<String>, List<Pair<String, TransformContext<T>>>>()
    private val transformsByFirstBone =
        java.util.IdentityHashMap<Any, Pair<Int, List<Pair<String, TransformContext<T>>>>>()

    open fun collectTransform(boneName: String): TransformContext<T>? {
        // Typed role state owns visibility.  Do not consult a vehicle-model override here:
        // retained T-90/BMP overrides belonged to an older fixed-primary camera path and hid an
        // intact turret during a weapon rebind or transient coherent-frame gap.
        if (boneName == "root") {
            return TransformContext { bone, _, _ ->
                bone.isHidden = hideForTurretControllerWhileZooming
            }
        }

        // A seat-authored passenger role remains visible; only an explicit typed optic or the
        // legacy untyped policy may hide this subtree.
        if (boneName == "passengerWeaponStation") {
            return TransformContext { bone, _, _ ->
                bone.isHidden = hideForPassengerWeaponStationControllerWhileZooming
            }
        }

        if (boneName == "laser") {
            return TransformContext { bone, vehicle, state ->
                bone.scaleZ = 10 * vehicle.laserLength
                val scale = Mth.lerp(
                    state.partialTick,
                    vehicle.laserScaleO,
                    vehicle.laserScale
                ).coerceAtMost(1.2f)

                bone.scaleX = scale
                bone.scaleY = scale
            }
        }

        //射击时带来的车体摇晃视觉效果
        when (boneName) {
            "base" -> {
                return TransformContext { bone, vehicle, _ ->
                    val a = vehicle.yawWhileShoot
                    val r = (Mth.abs(a) - 90f) / 90f

                    val r2 = if (Mth.abs(a) <= 90f) {
                        a / 90f
                    } else {
                        if (a < 0) {
                            -(180f + a) / 90f
                        } else {
                            (180f - a) / 90f
                        }
                    }

                    bone.posX = r2 * recoilShake * 0.5f
                    bone.posZ = r * recoilShake * 1f
                    bone.rotX = r * recoilShake * Mth.DEG_TO_RAD
                    bone.rotZ = r2 * recoilShake * Mth.DEG_TO_RAD
                }
            }

            "turret" -> {
                return TransformContext { bone, _, _ ->
                    bone.rotY = turretYRot * Mth.DEG_TO_RAD
                    val turretLaser = animationProcessor.getBone("turretLaser")
                    turretLaser?.rotY = bone.rotY

                    bone.isHidden = nativeTurretHidden
                }
            }

            "barrel" -> {
                return TransformContext { bone, _, _ ->
                    bone.rotX = barrelPitch * Mth.DEG_TO_RAD

                    val barrelLaser = animationProcessor.getBone("barrelLaser")
                    barrelLaser?.rotX = bone.rotX
                }
            }

            "passengerWeaponStationYaw" -> {
                return TransformContext { bone, _, _ ->
                    bone.rotY = passengerWeaponStationYaw * Mth.DEG_TO_RAD
                }
            }

            "passengerWeaponStationPitch" -> {
                return TransformContext { bone, _, _ ->
                    bone.rotX = passengerWeaponStationPitchRadians
                }
            }
        }

        // track(Mov|Rot)[RL]\d+
        val trackMatcher = TRACK_PATTERN.matcher(boneName)
        if (trackMatcher.matches()) {
            val isRot = trackMatcher.group("type") == "Rot"
            val isL = trackMatcher.group("direction") == "L"
            val index = trackMatcher.group("id").toInt()

            if (isRot) {
                return TransformContext { bone, _, _ ->
                    val hasProfile = profiledTrackPoseInto(isL, index)
                    val trackPhase = if (isL) leftTrack else rightTrack
                    bone.rotX = if (hasProfile) {
                        profiledTrackPose[2] * Mth.DEG_TO_RAD
                    } else {
                        -getBoneRotX(
                            wrap(trackPhase + getTrackDistance() * index, runningGearTrackAnimationLength)
                        ) * Mth.DEG_TO_RAD
                    }
                    bone.scaleZ = if (hasProfile) profiledTrackPose[3] else 1.0F
                }
            }
            return TransformContext { bone, _, _ ->
                if (profiledTrackPoseInto(isL, index)) {
                    bone.posY = profiledTrackPose[0]
                    bone.posZ = profiledTrackPose[1]
                } else {
                    val trackPhase = if (isL) leftTrack else rightTrack
                    val t = wrap(trackPhase + getTrackDistance() * index, runningGearTrackAnimationLength)
                    bone.posY = getBoneMoveY(t)
                    bone.posZ = getBoneMoveZ(t)
                }
            }
        }

        val wheelMatcher = WHEEL_PATTERN.matcher(boneName)
        if (wheelMatcher.matches()) {
            val isL = wheelMatcher.group("direction") == "L"

            return if (boneName.endsWith("Turn")) {
                TransformContext { bone, _, _ ->
                    bone.rotX = 1.5f * (if (isL) leftWheelRot else rightWheelRot)
                    bone.rotY = rudderRotation
                }
            } else {
                TransformContext { bone, _, _ -> bone.rotX = 1.5f * (if (isL) leftWheelRot else rightWheelRot) }
            }
        }

        return null
    }

    override fun setCustomAnimations(vehicle: T, instanceId: Long, animationState: AnimationState<T>) {
        // the bone set only changes when the baked model does: keyed by its first bone and size (identity), the
        // name list (and hashing it) is built only then, not every frame for every vehicle
        val registered = animationProcessor.registeredBones
        val first = registered.firstOrNull()
        val cachedSet = if (first != null) transformsByFirstBone[first]?.takeIf { it.first == registered.size } else null
        val transforms = cachedSet?.second ?: transformsByBoneSet.getOrPut(registered.map { it.name }) {
            buildList {
                animationProcessor.registeredBones.forEach { bone ->
                    val name = bone.name
                    try {
                        val transform = collectTransform(name)
                        if (transform != null) {
                            add(name to transform)
                        }
                    } catch (exception: Exception) {
                        Mod.LOGGER.error("failed to collect transform for vehicle {} bone {}:", vehicle, name, exception)
                    }
                }
            }
        }
        if (first != null && cachedSet == null) transformsByFirstBone[first] = registered.size to transforms

        val partialTick = animationState.partialTick
        val parts = VehicleRenderPartSnapshot.capture(vehicle, partialTick)
        val runningGear = RunningGearRenderState.capture(vehicle, partialTick)
        runningGearProfile = RunningGearProfiles.resolve(vehicle)

        leftWheelRot = runningGear.leftWheelRotation
        rightWheelRot = runningGear.rightWheelRotation

        leftTrack = runningGear.leftTrackPhase
        rightTrack = runningGear.rightTrackPhase

        turretYRot = parts.turretWorldYawDegrees
        turretXRot = parts.turretPitchDegrees
        barrelPitch = parts.barrelPitchDegrees

        recoilShake = parts.recoilShake
        passengerWeaponStationYaw = parts.stationYawRelativeToTurretDegrees
        passengerWeaponStationPitchRadians = parts.stationPitchRadians
        nativeTurretHidden = parts.turretHiddenByVehicleState
        rudderRotation = runningGear.rudderRotation
        runningGearTrackAnimationLength = runningGear.trackAnimationLength

        val minecraft = Minecraft.getInstance()
        val localPlayer = minecraft.player
        val turretSeat = vehicle.turretControllerIndex
        val turretWeapon = turretSeat.takeIf { it >= 0 }?.let(vehicle::getSelectedWeapon) ?: -1
        val reticleProvider = vehicle as? VehicleAimReticleProfileProvider
        val turretOpticalPolicy = reticleProvider
            ?.getVehicleAimReticleRole(turretSeat, turretWeapon)?.opticalCameraPolicy
        // Typed roles, rather than model classes or weapon names, own presentation visibility.
        // FOV_ONLY/SEAT_AUTHORED roles never hide intact topology; an explicit
        // WEAPON_ATTACHMENT role may hide the authored optic. Untyped legacy models retain
        // their established model override policy.
        val hideTypedBvpRoot = if (reticleProvider != null) {
            turretOpticalPolicy == VehicleAimOpticalCameraPolicy.WEAPON_ATTACHMENT
        } else {
            hideForTurretControllerWhileZooming()
        }
        val stationSeat = vehicle.passengerWeaponStationControllerIndex
        val stationWeapon = stationSeat.takeIf { it >= 0 }?.let(vehicle::getSelectedWeapon) ?: -1
        val stationOpticalPolicy = reticleProvider
            ?.getVehicleAimReticleRole(stationSeat, stationWeapon)?.opticalCameraPolicy
        val hideTypedBvpStation = if (reticleProvider != null) {
            stationOpticalPolicy == VehicleAimOpticalCameraPolicy.WEAPON_ATTACHMENT
        } else {
            hideForPassengerWeaponStationControllerWhileZooming()
        }
        hideForTurretControllerWhileZooming =
            ClientEventHandler.zoomVehicle && minecraft.options.cameraType == CameraType.FIRST_PERSON &&
                    vehicle.getNthEntity(turretSeat) === localPlayer &&
                    hideTypedBvpRoot
        hideForPassengerWeaponStationControllerWhileZooming =
            ClientEventHandler.zoomVehicle && minecraft.options.cameraType == CameraType.FIRST_PERSON &&
                    vehicle.getNthEntity(stationSeat) === localPlayer &&
                    hideTypedBvpStation

        transforms.forEach { (name, transform) ->
            val bone = animationProcessor.getBone(name)

            // TODO 这里怎么可能为空？
            if (bone != null) {
                transform.transform(bone, vehicle, animationState)
            }
        }
    }

    open fun hideForTurretControllerWhileZooming() = false

    /** Defaults to the legacy shared policy but allows station visibility to evolve independently. */
    open fun hideForPassengerWeaponStationControllerWhileZooming() = hideForTurretControllerWhileZooming()

    open fun getBoneRotX(t: Float) = t
    open fun getBoneMoveY(t: Float) = t
    open fun getBoneMoveZ(t: Float) = t
    open fun getTrackDistance() = 2f

    /**
     * The last pose evaluated for each track link: [phase, y, z, rotation, scale]. Every link has a Mov and a Rot
     * bone, and both asked for the same full link evaluation (up to 16 path samples) in the same frame; the second
     * one now reuses it. The pose depends only on the track profile, side, link and phase, so a hit is exact.
     */
    private val trackPoseCache = HashMap<Int, FloatArray>()
    private var trackPoseCacheProfile: Any? = null

    private fun profiledTrackPoseInto(left: Boolean, index: Int): Boolean {
        val track = runningGearProfile?.trackRender ?: return false
        if (track.mode != TrackRenderMode.LINKS) return false
        val side = if (left) RunningGearSide.LEFT else RunningGearSide.RIGHT
        val phase = RunningGearTrackEvaluator.normalizePhase(
            if (left) leftTrack else rightTrack,
            runningGearTrackAnimationLength,
        ) * track.side(side).direction
        if (trackPoseCacheProfile !== track) { trackPoseCache.clear(); trackPoseCacheProfile = track }
        val key = (if (left) 0 else 1 shl 20) or (index and 0xFFFFF)
        val cached = trackPoseCache[key]
        if (cached != null && cached[0] == phase) {
            System.arraycopy(cached, 1, profiledTrackPose, 0, 4)
            return true
        }
        RunningGearTrackEvaluator.linkPoseInto(
            track,
            side,
            index,
            phase,
            profiledTrackPose,
            0,
        )
        val entry = cached ?: FloatArray(5).also { trackPoseCache[key] = it }
        entry[0] = phase
        System.arraycopy(profiledTrackPose, 0, entry, 1, 4)
        return true
    }

    protected fun wrap(value: Float, range: Int) = ((value % range) + range) % range

    companion object {
        private fun <T> getDefault(vehicle: T): DefaultVehicleResource where T : VehicleEntity, T : GeoAnimatable {
            return VehicleResource.getDefault(vehicle)
        }

        val TRACK_PATTERN: Pattern = Pattern.compile("^track(?<type>Mov|Rot)(?<direction>[LR])(?<id>\\d+)$")
        val WHEEL_PATTERN: Pattern = Pattern.compile("^wheel(?<direction>[LR]).*$")
    }
}
