package com.atsuishio.superbwarfare.client.renderer.vehicle

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.util.Mth

/** Immutable interpolated running-gear state for one rendered frame. */
data class RunningGearRenderState(
    val leftWheelRotation: Float,
    val rightWheelRotation: Float,
    val leftTrackPhase: Float,
    val rightTrackPhase: Float,
    val rudderRotation: Float,
    val trackAnimationLength: Int,
) {
    companion object {
        @JvmStatic
        fun capture(vehicle: VehicleEntity, partialTick: Float): RunningGearRenderState {
            return RunningGearRenderState(
                leftWheelRotation = Mth.lerp(partialTick, vehicle.leftWheelRotO, vehicle.leftWheelRot),
                rightWheelRotation = Mth.lerp(partialTick, vehicle.rightWheelRotO, vehicle.rightWheelRot),
                leftTrackPhase = Mth.lerp(partialTick, vehicle.leftTrackO, vehicle.leftTrack),
                rightTrackPhase = Mth.lerp(partialTick, vehicle.rightTrackO, vehicle.rightTrack),
                rudderRotation = Mth.lerp(partialTick, vehicle.rudderRotO, vehicle.rudderRot),
                trackAnimationLength = vehicle.getTrackAnimationLength(),
            )
        }
    }
}
