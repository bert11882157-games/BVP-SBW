package com.atsuishio.superbwarfare.api.vehicle.render

import com.atsuishio.superbwarfare.api.vehicle.pose.VehicleChassisPresentation
import com.atsuishio.superbwarfare.api.vehicle.pose.VehiclePoseSnapshot
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimChannel
import com.atsuishio.superbwarfare.client.renderer.vehicle.RunningGearRenderState
import com.atsuishio.superbwarfare.client.renderer.vehicle.VehicleRenderPartSnapshot
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.world.phys.Vec3
import net.minecraft.world.entity.player.Player
import java.util.IdentityHashMap

data class FarVehiclePresentation @JvmOverloads constructor(
    val snapshot: FarVehicleSnapshot,
    val chassis: VehicleChassisPresentation,
    val fixedWingControls: FarFixedWingVisualState? = null,
)

/** Strong references are bounded and released by the copy owner's remove/clear lifecycle. */
internal class FarVehicleCopyRegistry<K : Any, V : Any>(private val capacity: Int) {
    private val entries = IdentityHashMap<K, V?>()

    init { require(capacity > 0) }

    @Synchronized fun contains(key: K): Boolean = entries.containsKey(key)
    @Synchronized operator fun get(key: K): V? = entries[key]
    @Synchronized fun size(): Int = entries.size

    @Synchronized fun register(key: K) {
        check(entries.containsKey(key) || entries.size < capacity) { "Far vehicle copy capacity exceeded" }
        entries[key] = null
    }

    @Synchronized fun update(key: K, value: V) {
        check(entries.containsKey(key)) { "Unregistered far vehicle copy" }
        entries[key] = value
    }

    @Synchronized fun remove(key: K) { entries.remove(key) }
    @Synchronized fun clear() { entries.clear() }
    @Synchronized fun find(predicate: (K) -> Boolean): K? = entries.keys.firstOrNull(predicate)
}

/** Explicit membership separates cosmetic copies from live client/server entities. */
object FarVehicleCopies {
    fun find(id: java.util.UUID): VehicleEntity? = copies.find { it.uuid == id }
    const val SURFACE_DAMAGE_KEY = "sbw_surface_damage"
    // Entity equality uses numeric IDs, which a copy deliberately shares with its live entity.
    private val copies = FarVehicleCopyRegistry<VehicleEntity, FarVehiclePresentation>(FarVehicleStore.MAX_VEHICLES)
    private val visualRadii = IdentityHashMap<VehicleEntity, Double>()

    @JvmStatic fun isCopy(vehicle: VehicleEntity): Boolean = copies.contains(vehicle)
    @JvmStatic fun frame(vehicle: VehicleEntity): FarVehiclePresentation? = copies[vehicle]

    fun register(vehicle: VehicleEntity) {
        require(vehicle.level().isClientSide && vehicle.level().getEntity(vehicle.id) !== vehicle)
        copies.register(vehicle)
        vehicle.computed().aircraftTerrainContact?.let { definition ->
            // A cosmetic copy never collides. Cache a conservative rotation-independent envelope
            // instead of reconstructing physical parts and vertices on every rendered setPos.
            val boxes = listOf(definition.fuselage, definition.landingGear)
            val x = boxes.maxOf { maxOf(kotlin.math.abs(it.minimum.x), kotlin.math.abs(it.maximum.x)) }
            val y = boxes.maxOf { maxOf(kotlin.math.abs(it.minimum.y), kotlin.math.abs(it.maximum.y)) }
            val z = boxes.maxOf { maxOf(kotlin.math.abs(it.minimum.z), kotlin.math.abs(it.maximum.z)) }
            visualRadii[vehicle] = kotlin.math.sqrt(x*x + y*y + z*z)
        }
    }

    fun visualBounds(vehicle: VehicleEntity): net.minecraft.world.phys.AABB? {
        val radius = visualRadii[vehicle] ?: return null
        return net.minecraft.world.phys.AABB(vehicle.x-radius, vehicle.y-radius, vehicle.z-radius,
            vehicle.x+radius, vehicle.y+radius, vehicle.z+radius)
    }

    fun remove(vehicle: VehicleEntity) { copies.remove(vehicle); visualRadii.remove(vehicle) }
    fun clear() { copies.clear(); visualRadii.clear() }

    /** Samples current server values without save/load, inventory traversal, or a second solver. */
    fun capture(vehicle: VehicleEntity): FarVehicleSnapshot {
        require(!vehicle.level().isClientSide)
        val pose = vehicle.getVehiclePoseSnapshot(1F).copy(
            anchor = vehicle.position(), chassisYawDegrees = vehicle.yRot)
        val parts = VehicleRenderPartSnapshot.capture(vehicle, vehicle.yRot, 1F)
        val seat = vehicle.turretControllerIndex
        val weapon = if (seat >= 0) vehicle.getSelectedWeapon(seat) else -1
        val agsPitch = if (!vehicle.isWreck && seat >= 0 && weapon >= 0 &&
            vehicle.getNthEntity(seat) is Player && vehicle.getGunName(seat, weapon) == "GrenadeLauncher" &&
            vehicle.resolveVehicleAimProfile(seat, weapon)?.channel == VehicleAimChannel.TURRET)
            parts.barrelPitchDegrees else null
        val visuals = (vehicle as? FarVehicleVisualExtension)?.captureFarRenderVisuals()
            ?.toMutableMap() ?: mutableMapOf()
        // This reserved tuple is sampled from accepted common flight state, never addon controls.
        visuals.remove(FarFixedWingVisualState.VISUAL_KEY)
        visuals.remove(SURFACE_DAMAGE_KEY)
        if (!vehicle.isWreck && vehicle.isFixedWingFlightVehicle()) {
            val modules = com.atsuishio.superbwarfare.api.aircraft.AircraftSurfaceModules
            val damaged = modules.ids.foldIndexed(0) { index, bits, id ->
                if (modules.damaged(vehicle, id)) bits or (1 shl index) else bits
            }
            if (visuals.size < 7) visuals[SURFACE_DAMAGE_KEY] = damaged.toString()
            val flight = vehicle.getVehicleFlightPresentationSnapshot(1F)
            val controls = flight.controlSurfaces
            if (controls != null && controls.serverTick == flight.serverTick) {
                FarFixedWingVisualState.create(flight.sequence, flight.serverTick,
                    controls.elevator, controls.aileron, controls.rudder, flight.throttle, controls.airbrake)?.let {
                    val encoded = it.encode()
                    val bytes = vehicle.override.toByteArray(Charsets.UTF_8).size +
                        visuals.entries.sumOf { field -> field.key.toByteArray(Charsets.UTF_8).size +
                            field.value.toByteArray(Charsets.UTF_8).size }
                    if (visuals.size < 8 && bytes + encoded.length + FarFixedWingVisualState.VISUAL_KEY.length <= 8192)
                        visuals[FarFixedWingVisualState.VISUAL_KEY] = encoded
                }
            }
        }
        return FarVehicleSnapshot(
            vehicle.id, vehicle.uuid.toString(), net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(vehicle.type).toString(),
            vehicle.x, vehicle.y, vehicle.z, vehicle.yRot, vehicle.xRot, vehicle.roll,
            vehicle.tickCount, pose.encode(), parts.copy(ags30PitchDegrees = agsPitch),
            RunningGearRenderState.capture(vehicle, 1F), vehicle.power, vehicle.targetSpeed,
            vehicle.deltaMovement.x, vehicle.deltaMovement.y, vehicle.deltaMovement.z,
            vehicle.propellerRot, vehicle.gearRot,
            listOf(vehicle.flap1LRot, vehicle.flap1RRot, vehicle.flap1L2Rot, vehicle.flap1R2Rot,
                vehicle.flap2LRot, vehicle.flap2RRot, vehicle.flap3Rot),
            vehicle.selectedWeapon.toList(), vehicle.health, vehicle.isWreck,
            vehicle.sympatheticDetonated, vehicle.turretBurned, vehicle.passengers.isNotEmpty(),
            vehicle.override, visuals.toMap(), vehicle.aircraftWreckStart, vehicle.aircraftWreckWings,
            vehicle.aircraftWreckMotionX, vehicle.aircraftWreckMotionY, vehicle.aircraftWreckMotionZ,
            vehicle.aircraftWreckImpactTime,
        )
    }

    fun apply(vehicle: VehicleEntity, entry: FarVehicleStore.Entry, renderTick: Double) {
        require(isCopy(vehicle) && vehicle.level().isClientSide &&
            vehicle.level().getEntity(vehicle.id) !== vehicle) { "Far state may only mutate a render copy" }
        val segment = FarVehicleStore.segment(entry, renderTick)
        val alpha = FarVehicleStore.alpha(segment, renderTick)
        val s = FarVehicleStore.interpolate(segment, alpha, entry.current)
        val anchor = Vec3(s.x, s.y, s.z)
        val pose = VehiclePoseSnapshot.interpolate(segment.previousPose, segment.currentPose, alpha)
            .copy(anchor = anchor, chassisYawDegrees = s.yaw)
        copies.update(vehicle, FarVehiclePresentation(s, VehicleChassisPresentation(
            pose, anchor, s.yaw, pose.serverTick.toDouble(), alpha,
            segment.previousPose.sequence, segment.currentPose.sequence, 2,
            VehicleChassisPresentation.Mode.REMOTE_INTERPOLATED),
            FarVehicleStore.fixedWingControls(entry, segment, alpha)))
        vehicle.setPos(anchor)
        vehicle.xo = s.x
        vehicle.yo = s.y
        vehicle.zo = s.z
        vehicle.yRot = s.yaw
        vehicle.yRotO = s.yaw
        vehicle.xRot = s.pitch
        vehicle.xRotO = s.pitch
        vehicle.roll = s.roll
        vehicle.prevRoll = s.roll
        vehicle.tickCount = s.age
        vehicle.deltaMovement = Vec3(s.motionX, s.motionY, s.motionZ)
        vehicle.power = s.power
        vehicle.targetSpeed = s.targetSpeed
        vehicle.turretYRot = s.parts.turretWorldYawDegrees
        vehicle.turretYRotO = vehicle.turretYRot
        vehicle.turretXRot = s.parts.turretPitchDegrees
        vehicle.turretXRotO = vehicle.turretXRot
        vehicle.gunYRot = s.parts.stationYawFromRenderedHullDegrees + s.parts.turretYawFromRenderedHullDegrees
        vehicle.gunYRotO = vehicle.gunYRot
        vehicle.gunXRot = -s.parts.stationPitchDegrees
        vehicle.gunXRotO = vehicle.gunXRot
        vehicle.leftWheelRot = s.runningGear.leftWheelRotation
        vehicle.leftWheelRotO = vehicle.leftWheelRot
        vehicle.rightWheelRot = s.runningGear.rightWheelRotation
        vehicle.rightWheelRotO = vehicle.rightWheelRot
        vehicle.leftTrack = s.runningGear.leftTrackPhase
        vehicle.leftTrackO = vehicle.leftTrack
        vehicle.rightTrack = s.runningGear.rightTrackPhase
        vehicle.rightTrackO = vehicle.rightTrack
        vehicle.rudderRot = s.runningGear.rudderRotation
        vehicle.rudderRotO = vehicle.rudderRot
        vehicle.propellerRot = s.propeller
        vehicle.propellerRotO = s.propeller
        vehicle.synchedPropellerRot = s.propeller
        vehicle.gearRot = s.gear
        vehicle.synchedGearRot = s.gear
        vehicle.flap1LRot = s.flaps[0]
        vehicle.flap1LRotO = s.flaps[0]
        vehicle.flap1RRot = s.flaps[1]
        vehicle.flap1RRotO = s.flaps[1]
        vehicle.flap1L2Rot = s.flaps[2]
        vehicle.flap1L2RotO = s.flaps[2]
        vehicle.flap1R2Rot = s.flaps[3]
        vehicle.flap1R2RotO = s.flaps[3]
        vehicle.flap2LRot = s.flaps[4]
        vehicle.flap2LRotO = s.flaps[4]
        vehicle.flap2RRot = s.flaps[5]
        vehicle.flap2RRotO = s.flaps[5]
        vehicle.flap3Rot = s.flaps[6]
        vehicle.flap3RotO = s.flaps[6]
        vehicle.cannonRecoilTime = s.parts.cannonRecoilTime
        vehicle.cannonRecoilForce = s.parts.cannonRecoilForce
        vehicle.recoilShake = s.parts.recoilShake.toDouble()
        vehicle.recoilShakeO = vehicle.recoilShake
        if (vehicle.selectedWeapon != s.selectedWeapons) vehicle.selectedWeapon = s.selectedWeapons
        vehicle.health = s.health
        vehicle.isWreck = s.wreck
        vehicle.aircraftWreckStart = s.aircraftWreckStart
        vehicle.aircraftWreckWings = s.aircraftWreckWings
        vehicle.aircraftWreckMotionX = s.aircraftWreckMotionX
        vehicle.aircraftWreckMotionY = s.aircraftWreckMotionY
        vehicle.aircraftWreckMotionZ = s.aircraftWreckMotionZ
        vehicle.aircraftWreckImpactTime = s.aircraftWreckImpactTime

        vehicle.sympatheticDetonated = s.turretEjected
        vehicle.turretBurned = s.turretBurned
        (vehicle as? FarVehicleVisualExtension)?.applyFarRenderVisuals(s.visualData)
    }
}
