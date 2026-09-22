package com.atsuishio.superbwarfare.client

import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimChannel
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimMath
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimMode
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimProfile
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import java.util.EnumMap
import java.util.UUID
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Local presentation prediction for the player currently driving a weapon channel.
 *
 * Server snapshots remain authoritative for gameplay and remote observers. While the local player
 * owns a legacy channel, this helper reproduces its established render-facing servo. Camera-
 * directed ground turrets deliberately bypass prediction and consume authoritative actuals so the
 * local physical marker can never lead the server muzzle or oscillate around delayed corrections.
 */
@OnlyIn(Dist.CLIENT)
object VehicleAimPresentationController {
    private data class Context(
        val controllerId: UUID,
        val seatIndex: Int,
        val selectedWeaponIndex: Int,
    )

    private data class State(
        val context: Context,
        var yaw: Float,
        var pitch: Float,
        var locked: Boolean,
    )

    private val states = WeakHashMap<VehicleEntity, EnumMap<VehicleAimChannel, State>>()

    @JvmStatic
    fun tick(vehicle: VehicleEntity?) {
        resetInactiveVehicles(vehicle)
        vehicle ?: return
        if (!vehicle.level().isClientSide) return
        if (vehicle.isWreck) {
            vehicle.turretYRotLock = 0F
            resetVehicle(vehicle)
            return
        }

        val player = Minecraft.getInstance().player
        if (player == null || player.vehicle !== vehicle) {
            resetVehicle(vehicle)
            return
        }

        val seatIndex = vehicle.getSeatIndex(player)
        if (seatIndex < 0) {
            resetVehicle(vehicle)
            return
        }
        val selectedWeaponIndex = vehicle.getPrimaryWeaponIndex(seatIndex)
        val channelStates = states.getOrPut(vehicle) {
            EnumMap(VehicleAimChannel::class.java)
        }

        for (channel in VehicleAimChannel.entries) {
            val profile = vehicle.getVehicleAimPresentationProfile(player, channel)
            val localMode = profile?.defaultMode ?: VehicleAimMode.INACTIVE
            val ownsChannel = profile != null &&
                    profile.channel == channel &&
                    controller(vehicle, channel) === player
            val eligible = localMode == VehicleAimMode.PLAYER_LOOK_AIM && ownsChannel
            if (!eligible) {
                if (ownsChannel && profile!!.snapToNeutralWhenInactive) {
                    applyInactiveNeutral(vehicle, channel, profile)
                }
                resetChannel(channelStates, channel)
                continue
            }

            predict(
                vehicle,
                player,
                channelStates,
                channel,
                profile,
                Context(player.uuid, seatIndex, selectedWeaponIndex),
            )
        }

        if (channelStates.isEmpty()) states.remove(vehicle)
    }

    /** Client-local residual lock used by the controlling player's presentation-only reticle. */
    @JvmStatic
    fun localLock(vehicle: VehicleEntity, seatIndex: Int, selectedWeaponIndex: Int): Boolean? =
        states[vehicle]
            ?.values
            ?.firstOrNull {
                it.context.seatIndex == seatIndex &&
                        it.context.selectedWeaponIndex == selectedWeaponIndex
            }
            ?.locked

    /** Prevents delayed snapshots from overwriting an actively predicted local channel mid-tick. */
    @JvmStatic
    fun isPredicting(vehicle: VehicleEntity, channel: VehicleAimChannel): Boolean =
        states[vehicle]?.containsKey(channel) == true

    private fun predict(
        vehicle: VehicleEntity,
        controller: Entity,
        channelStates: EnumMap<VehicleAimChannel, State>,
        channel: VehicleAimChannel,
        profile: VehicleAimProfile,
        context: Context,
    ) {
        var state = channelStates[channel]
        if (state == null || state.context != context) {
            state = State(context, actualYaw(vehicle, channel), actualPitch(vehicle, channel), false)
            channelStates[channel] = state
        }

        // Reassert the independent local state before evaluating attachment vectors so no other
        // client-tick consumer can make prediction accumulate from a delayed authority sample.
        setPresentation(vehicle, channel, state.yaw, state.pitch, state.yaw, state.pitch, profile)

        val desired = controller.getViewVector(1F)
        val current = when (channel) {
            VehicleAimChannel.TURRET -> vehicle.getBarrelVector(1F)
            VehicleAimChannel.PASSENGER_WEAPON -> vehicle.getPassengerWeaponStationVector(1F)
        }
        val yawDelta = Mth.wrapDegrees(
            -VehicleVecUtils.getYRotFromVector(desired) + VehicleVecUtils.getYRotFromVector(current)
        ).toFloat()
        val pitchDelta = Mth.wrapDegrees(
            -VehicleVecUtils.getXRotFromVector(desired) + VehicleVecUtils.getXRotFromVector(current)
        ).toFloat()
        val yawStep = vehicle.resolveVehicleAimYawRateDegreesPerSecond(
            channel,
            profile.yawRateDegreesPerSecond,
        ) / TICKS_PER_SECOND
        val pitchStep = vehicle.resolveVehicleAimPitchRateDegreesPerSecond(
            channel,
            profile.pitchRateDegreesPerSecond,
        ) / TICKS_PER_SECOND
        val appliedYaw = Mth.clamp(yawDelta, -yawStep, yawStep)
        val appliedPitch = Mth.clamp(pitchDelta, -pitchStep, pitchStep)
        val yawChange = VehicleAimMath.softenDeltaNearRange(
            state.yaw,
            -appliedYaw,
            profile.minYaw,
            profile.maxYaw,
            profile.softYawLimitDegrees,
        )
        val pitchChange = VehicleAimMath.softenDeltaNearRange(
            state.pitch,
            appliedPitch,
            profile.minPitch,
            profile.maxPitch,
            profile.softPitchLimitDegrees,
        )
        val nextYaw = VehicleAimMath.applyYawRange(state.yaw + yawChange, profile.minYaw, profile.maxYaw)
        val nextPitch = Mth.clamp(state.pitch + pitchChange, profile.minPitch, profile.maxPitch)
        val actualAppliedYaw = if (VehicleAimMath.isFullYawRange(profile.minYaw, profile.maxYaw)) {
            Mth.wrapDegrees(state.yaw - nextYaw)
        } else {
            state.yaw - nextYaw
        }
        val actualAppliedPitch = nextPitch - state.pitch
        val locked = abs(yawDelta - actualAppliedYaw) <= profile.lockToleranceDegrees &&
                abs(pitchDelta - actualAppliedPitch) <= profile.lockToleranceDegrees

        setPresentation(vehicle, channel, state.yaw, state.pitch, nextYaw, nextPitch, profile)
        state.yaw = nextYaw
        state.pitch = nextPitch
        state.locked = locked
    }

    private fun actualYaw(vehicle: VehicleEntity, channel: VehicleAimChannel): Float = when (channel) {
        VehicleAimChannel.TURRET -> vehicle.turretYRot
        VehicleAimChannel.PASSENGER_WEAPON -> vehicle.gunYRot
    }

    private fun actualPitch(vehicle: VehicleEntity, channel: VehicleAimChannel): Float = when (channel) {
        VehicleAimChannel.TURRET -> vehicle.turretXRot
        VehicleAimChannel.PASSENGER_WEAPON -> vehicle.gunXRot
    }

    private fun applyInactiveNeutral(
        vehicle: VehicleEntity,
        channel: VehicleAimChannel,
        profile: VehicleAimProfile,
    ) {
        val neutralYaw = VehicleAimMath.applyYawRange(profile.neutralYaw, profile.minYaw, profile.maxYaw)
        val neutralPitch = Mth.clamp(profile.neutralPitch, profile.minPitch, profile.maxPitch)
        setPresentation(
            vehicle,
            channel,
            neutralYaw,
            neutralPitch,
            neutralYaw,
            neutralPitch,
            profile,
        )
    }

    private fun resetVehicle(vehicle: VehicleEntity) {
        states.remove(vehicle)
    }

    private fun resetInactiveVehicles(activeVehicle: VehicleEntity?) {
        val iterator = states.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val vehicle = entry.key
            if (vehicle === activeVehicle) continue
            iterator.remove()
        }
    }

    private fun resetChannel(
        channelStates: EnumMap<VehicleAimChannel, State>,
        channel: VehicleAimChannel,
    ) {
        channelStates.remove(channel)
    }

    private fun setPresentation(
        vehicle: VehicleEntity,
        channel: VehicleAimChannel,
        previousYaw: Float,
        previousPitch: Float,
        nextYaw: Float,
        nextPitch: Float,
        profile: VehicleAimProfile,
    ) {
        val interpolationYaw = VehicleAimMath.previousYawForInterpolation(
            previousYaw,
            nextYaw,
            profile.minYaw,
            profile.maxYaw,
        )
        when (channel) {
            VehicleAimChannel.TURRET -> {
                vehicle.turretYRotO = interpolationYaw
                vehicle.turretXRotO = previousPitch
                vehicle.turretYRot = nextYaw
                vehicle.turretXRot = nextPitch
                vehicle.turretYRotLock = Mth.wrapDegrees(nextYaw - interpolationYaw)
            }
            VehicleAimChannel.PASSENGER_WEAPON -> {
                vehicle.gunYRotO = interpolationYaw
                vehicle.gunXRotO = previousPitch
                vehicle.gunYRot = nextYaw
                vehicle.gunXRot = nextPitch
            }
        }
    }

    private fun controller(vehicle: VehicleEntity, channel: VehicleAimChannel): Entity? = when (channel) {
        VehicleAimChannel.TURRET -> vehicle.getNthEntity(vehicle.turretControllerIndex)
        VehicleAimChannel.PASSENGER_WEAPON ->
            vehicle.getNthEntity(vehicle.passengerWeaponStationControllerIndex)
    }

    private const val TICKS_PER_SECOND = 20F
}
