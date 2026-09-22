package com.atsuishio.superbwarfare.client.overlay

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn

/** The native trace used to acquire this target. */
@OnlyIn(Dist.CLIENT)
enum class VehicleTargetTraceSource {
    CAMERA,
    VEHICLE_WEAPON
}

/**
 * Immutable result of the native vehicle-target acquisition pipeline.
 *
 * A snapshot exists only after the native block/smoke trace and decoy rejection accept a vehicle. The target is a
 * live client entity reference; the remaining values are captured when the client tick performs the trace.
 */
@OnlyIn(Dist.CLIENT)
data class VehicleTargetSnapshot(
    val target: VehicleEntity,
    val range: Double,
    val tracePosition: Vec3,
    val traceDirection: Vec3,
    val traceSource: VehicleTargetTraceSource
)

/**
 * Client-only presentation hook for an accepted native vehicle target.
 *
 * Return `true` after fully rendering the target presentation. Return `false` without drawing to pass the snapshot
 * to the next registered renderer and, if none handles it, the unchanged SBW renderer.
 */
@OnlyIn(Dist.CLIENT)
fun interface VehicleTargetRenderer {
    fun render(
        snapshot: VehicleTargetSnapshot,
        guiGraphics: GuiGraphics,
        partialTick: Float,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean
}
