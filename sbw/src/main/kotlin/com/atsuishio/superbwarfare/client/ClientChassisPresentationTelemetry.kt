package com.atsuishio.superbwarfare.client

import com.atsuishio.superbwarfare.api.vehicle.pose.VehicleChassisPresentation
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import java.util.WeakHashMap

/** Render correlation is part of Elite diagnostics, sampled once per vehicle per client tick. */
object ClientChassisPresentationTelemetry {
    private val lastTick = WeakHashMap<VehicleEntity, Int>()

    @JvmStatic fun recordRender(vehicle: VehicleEntity, p: VehicleChassisPresentation, partialTick: Float) {
        if (!EliteDiagnostics.isClientEnabled()) return
        if (lastTick.put(vehicle, vehicle.tickCount) == vehicle.tickCount) return
        val attachment = vehicle.getVehicleAttachmentSnapshot(partialTick)
            .transform("Vehicle")?.localToWorld(Vec3.ZERO)
        val obbOrigin = vehicle.obb.firstOrNull()?.let { info ->
            val transform = vehicle.getTransformFromString(info.transform, partialTick)
            val v = vehicle.transformPosition(transform, info.position.x, info.position.y, info.position.z)
            Vec3(v.x, v.y, v.z)
        }
        EliteDiagnostics.record(vehicle, "presentation", "chassis",
            "partial_tick", partialTick, "mode", p.mode,
            "lower_sequence", p.lowerSequence, "upper_sequence", p.upperSequence,
            "server_tick", p.presentationServerTick, "alpha", p.alpha, "buffer_depth", p.bufferDepth,
            "entity_position", vehicle.position(), "old_position", Vec3(vehicle.xo, vehicle.yo, vehicle.zo),
            "target_position", vehicle.getVehiclePositionTarget(),
            "interpolation_steps", vehicle.getVehiclePositionInterpolationSteps(),
            "model_anchor", p.anchor, "world_y", p.resolvedWorldY, "yaw", p.chassisYawDegrees,
            "ground_bias", p.pose.groundBias, "step_offset", p.pose.collisionStepOffset,
            "step_velocity", p.pose.collisionStepVelocity,
            "horizontal_correction", p.localHorizontalCorrection,
            "y_correction", p.localComposedYCorrection, "yaw_correction", p.localYawCorrection,
            "attachment_origin", attachment, "obb_origin", obbOrigin,
            "camera_origin", Minecraft.getInstance().gameRenderer.mainCamera.position)
    }
}
