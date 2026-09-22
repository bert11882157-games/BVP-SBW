package com.atsuishio.superbwarfare.api.vehicle.presentation

import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftWheelContactGroup
import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftWheelTouchdown
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModSounds
import com.atsuishio.superbwarfare.network.message.receive.ShakeClientMessage
import com.atsuishio.superbwarfare.tools.sendPacket
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth

/** Presentation only; the terrain transaction owns touchdown onset and its cooldown. */
object VehicleLandingImpactPresentation {
    // Start at the last lobe of the existing damped shake, not a long explosion oscillation.
    internal const val IMPULSE_PHASE = 1.0
    internal const val OCCUPANT_RADIUS = 32.0
    internal const val MAX_PEAK_DEGREES = 0.65
    private const val DEGREES_PER_BLOCK_PER_TICK = 2.0
    private const val EXISTING_MOUNTED_ATTENUATION = 0.1
    internal const val MAX_WHEEL_PEAK_DEGREES = 3.0
    internal const val MIN_WHEEL_PEAK_DEGREES = 2.0
    internal const val MAX_SMOKE_PER_CONTACT_GROUP = 32

    internal fun amplitude(impactSpeedBlocksPerTick: Double): Double {
        if (!impactSpeedBlocksPerTick.isFinite() || impactSpeedBlocksPerTick <= 0.0) return 0.0
        val peak = (impactSpeedBlocksPerTick * DEGREES_PER_BLOCK_PER_TICK).coerceAtMost(MAX_PEAK_DEGREES)
        return peak / (Mth.DEG_TO_RAD * EXISTING_MOUNTED_ATTENUATION)
    }

    @JvmStatic
    fun onGearImpact(vehicle: VehicleEntity, impactSpeedBlocksPerTick: Double) {
        if (vehicle.level().isClientSide || vehicle.isRemoved || vehicle.isWreck) return
        if (vehicle.vehicleType != VehicleType.AIRPLANE && vehicle.vehicleType != VehicleType.HELICOPTER) return
        val amplitude = amplitude(impactSpeedBlocksPerTick)
        if (amplitude <= 0.0) return
        shakeOccupants(vehicle, amplitude)
    }

    internal fun wheelAmplitude(group: AircraftWheelContactGroup, sinkSpeedBlocksPerTick: Double): Double {
        if (!sinkSpeedBlocksPerTick.isFinite() || sinkSpeedBlocksPerTick <= 0.0) return 0.0
        val groupScale = when (group) {
            AircraftWheelContactGroup.MAIN -> 1.0
            AircraftWheelContactGroup.NOSE -> 0.6
            AircraftWheelContactGroup.TAIL -> 0.5
        }
        val peak = (MIN_WHEEL_PEAK_DEGREES + sinkSpeedBlocksPerTick * 5.0)
            .coerceAtMost(MAX_WHEEL_PEAK_DEGREES) * groupScale
        return peak / (Mth.DEG_TO_RAD * EXISTING_MOUNTED_ATTENUATION)
    }

    internal fun wheelSmokeCount(contacts: Int, sinkSpeedBlocksPerTick: Double): Int {
        if (contacts !in 1..32 || !sinkSpeedBlocksPerTick.isFinite() || sinkSpeedBlocksPerTick <= 0.0) return 0
        val perContact = 12 + (sinkSpeedBlocksPerTick.coerceAtMost(1.0) * 8.0).toInt()
        return (contacts * perContact).coerceAtMost(MAX_SMOKE_PER_CONTACT_GROUP)
    }

    internal fun tireVolume(horizontalSpeedBlocksPerTick: Double): Float {
        if (!horizontalSpeedBlocksPerTick.isFinite() || horizontalSpeedBlocksPerTick < 0.25) return 0F
        return (0.2 + (horizontalSpeedBlocksPerTick - 0.25) * 0.35).coerceAtMost(0.95).toFloat()
    }

    /** One server-owned group edge; no tick emitter or client-side contact inference. */
    @JvmStatic
    fun onWheelTouchdown(vehicle: VehicleEntity, event: AircraftWheelTouchdown) {
        val level = vehicle.level() as? ServerLevel ?: return
        if (vehicle.isRemoved || vehicle.isWreck || vehicle.vehicleType != VehicleType.AIRPLANE) return
        val amplitude = wheelAmplitude(event.group, event.sinkSpeedBlocksPerTick)
        if (amplitude <= 0.0) return
        shakeOccupants(vehicle, amplitude)
        val volume = tireVolume(vehicle.deltaMovement.horizontalDistance())
        if (volume > 0F) {
            // One sound per admitted gear-group contact, located at its actual support points.
            val x = event.contacts.sumOf { it.x } / event.contacts.size
            val y = event.contacts.sumOf { it.y } / event.contacts.size
            val z = event.contacts.sumOf { it.z } / event.contacts.size
            val pitch = when (event.group) {
                AircraftWheelContactGroup.MAIN -> 0.95F
                AircraftWheelContactGroup.NOSE -> 1.10F
                AircraftWheelContactGroup.TAIL -> 1.16F
            }
            level.playSound(null, x, y, z, ModSounds.AIRCRAFT_TIRE_TOUCHDOWN.get(),
                SoundSource.NEUTRAL, volume, pitch)
        }
        val total = wheelSmokeCount(event.contacts.size, event.sinkSpeedBlocksPerTick)
        val each = total / event.contacts.size
        val remainder = total % event.contacts.size
        for (index in event.contacts.indices) {
            val count = each + if (index < remainder) 1 else 0
            if (count == 0) continue
            val point = event.contacts[index]
            level.sendParticles(ParticleTypes.LARGE_SMOKE, point.x, point.y, point.z,
                count, 0.12, 0.05, 0.12, 0.025)
        }
    }

    private fun shakeOccupants(vehicle: VehicleEntity, amplitude: Double) {
        for (occupant in vehicle.passengers) {
            if (occupant !is ServerPlayer || !occupant.isAlive || occupant.isSpectator ||
                occupant.vehicle !== vehicle || occupant.level() !== vehicle.level()) continue
            // Center each occupant's impulse on them: no propagation delay or seat-distance bias.
            occupant.sendPacket(ShakeClientMessage(IMPULSE_PHASE, OCCUPANT_RADIUS, amplitude,
                occupant.x, occupant.y, occupant.z))
        }
    }
}
