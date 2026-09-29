package com.atsuishio.superbwarfare.client.aircraft

import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.VehicleRangeBallistics
import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.VehicleShotPredictionService
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn

/**
 * The fixed-wing pilot's aiming ring (owner report 2026-09-29: the old body-forward ring sat above where the guns hit
 * and where the laser landed).
 *
 * The ring used to be the body axis drawn from the pilot's eye. The guns do not fly along that line: they sit below the
 * eye, their rounds inherit the aircraft's velocity (which points below the nose at any angle of attack) and drop with
 * gravity, so a target under the ring was hit low. The laser, meanwhile, lased the centre of the view, which the chase
 * camera lets drift off the ring while manoeuvring.
 *
 * Now the ring is one aim point shared by both: with a ballistic gun selected it is where the rounds fired now cross the
 * range of whatever lies under the sight (terrain or a vehicle along the body line, else [DEFAULT_RANGE]), modelled by
 * the same nominal ballistics as the live shells (muzzle frame, inherited motion, gravity); otherwise it is the body
 * line at that range. The laser lases through it ([laserRay]).
 */
@OnlyIn(Dist.CLIENT)
object FixedWingGunSight {
    /** Range of the sight when nothing lies under it within [MAX_RANGE] (typical air-to-air / strafing distance). */
    const val DEFAULT_RANGE = 600.0
    const val MAX_RANGE = 1000.0

    private var vehicleId = -1
    private var level: Any? = null
    private var point: Vec3? = null
    private var frameNanos = 0L

    /**
     * The ring's world point for this frame, from [camera] (the rendered camera) and the rendered body [forward].
     * [gunSolved] tells whether it follows the gun rounds (true) or the body line (false).
     */
    fun aimPoint(vehicle: VehicleEntity, player: Player, partialTick: Float, camera: Vec3, forward: Vec3): Sight {
        val direction = forward.normalize()
        val range = sightRange(vehicle, player, camera, direction)
        val gunPoint = runCatching {
            VehicleShotPredictionService.captureClientNominalSnapshotIfFiredNow(vehicle, player, partialTick).snapshot
                ?.let { VehicleRangeBallistics.atRangePlane(it, camera, direction, range) }
        }.getOrNull()?.takeIf { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }
        val world = gunPoint ?: camera.add(direction.scale(range))
        vehicleId = vehicle.id
        level = vehicle.level()
        point = world
        frameNanos = System.nanoTime()
        return Sight(world, range, gunPoint != null)
    }

    data class Sight(val point: Vec3, val range: Double, val gunSolved: Boolean)

    /** Depth of the first block or other entity along the body line from the camera, else [DEFAULT_RANGE]. */
    internal fun sightRange(vehicle: VehicleEntity, player: Player, camera: Vec3, direction: Vec3): Double {
        val level = vehicle.level()
        val end = camera.add(direction.scale(MAX_RANGE))
        val block = level.clip(ClipContext(camera, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        var range = if (block.type == HitResult.Type.MISS) Double.POSITIVE_INFINITY
            else block.location.subtract(camera).dot(direction)
        val limit = if (range.isFinite()) range else MAX_RANGE
        val reach = camera.add(direction.scale(limit))
        val world = Minecraft.getInstance().level
        if (world === level) {
            for (entity in world.entitiesForRendering()) {
                if (!targetable(entity, vehicle, player)) continue
                val box = entity.boundingBox.inflate(entity.pickRadius.toDouble() + 0.3)
                val hit = box.clip(camera, reach).orElse(null) ?: continue
                val depth = hit.subtract(camera).dot(direction)
                if (depth in 1.0..range) range = depth
            }
        }
        return if (range.isFinite() && range >= 1.0) range else DEFAULT_RANGE
    }

    private fun targetable(entity: Entity, vehicle: VehicleEntity, player: Player): Boolean =
        entity !== vehicle && entity !== player && !entity.isRemoved && !entity.isSpectator &&
            entity.rootVehicle !== vehicle && (entity is VehicleEntity || entity.isPickable) &&
            entity.boundingBox.size in 0.2..64.0

    /**
     * The laser ray through the ring the pilot saw: from [origin] toward the ring's world point of the last rendered
     * frame, if that frame belongs to [vehicle] and is recent. Null otherwise (the caller keeps its fallback).
     */
    fun laserRay(vehicle: VehicleEntity, origin: Vec3): Vec3? {
        val target = point ?: return null
        if (vehicleId != vehicle.id || level !== vehicle.level() || System.nanoTime() - frameNanos > 500_000_000L) return null
        val ray = target.subtract(origin)
        val length = ray.length()
        return if (length > 1.0E-3 && length.isFinite()) ray.scale(1.0 / length) else null
    }

    fun reset() {
        vehicleId = -1; level = null; point = null; frameNanos = 0L
    }
}
