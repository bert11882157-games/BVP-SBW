package com.atsuishio.superbwarfare.client.renderer

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.vehicle.flight.AircraftWreckBreakup
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingFlightStrategy
import com.atsuishio.superbwarfare.client.FarTerrainClient
import com.atsuishio.superbwarfare.client.particle.AircraftCombatParticles
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.joml.Quaternionf
import java.util.UUID

/** Shared near/far debris pass; no server entities, packets, damage or terrain tickets. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object AircraftDetachedWings {
    fun interface Visual {
        fun render(pose: PoseStack, buffers: MultiBufferSource, blackened: Boolean)
    }
    private data class Key(val id: UUID, val side: Int, val started: Long)
    private data class Part(val motion: AircraftDebrisMotion, val visual: Visual, val radius: Double,
                            val corners: List<Vec3>)
    private val pieces = LinkedHashMap<Key, Part>()
    private val observed = LinkedHashMap<Key, Long>()
    private var world: Any? = null
    private var clock = 0L
    private val random = java.util.Random()

    @JvmStatic fun clear() {
        pieces.clear(); observed.clear(); world = Minecraft.getInstance().level; clock = 0
        com.atsuishio.superbwarfare.compat.voxy.ClientDebrisTerrain.clear()
    }
    private fun syncWorld() { if (world !== Minecraft.getInstance().level) clear() }
    @JvmStatic fun needsCapture(vehicle: VehicleEntity, side: Int): Boolean {
        syncWorld()
        return vehicle.aircraftWreckStart >= 0 && vehicle.aircraftWreckWings >= 0 &&
            AircraftWreckBreakup.mask(vehicle) and side != 0 &&
            Key(vehicle.uuid, side, vehicle.aircraftWreckStart) !in observed
    }

    /** Center is world-space, orientation maps captured mesh-local coordinates into world space. */
    @JvmStatic fun capture(vehicle: VehicleEntity, side: Int, center: Vec3, orientation: Quaternionf,
                           halfExtents: Vec3, visual: Visual) {
        if (!needsCapture(vehicle, side)) return
        val key = Key(vehicle.uuid, side, vehicle.aircraftWreckStart)
        observed[key] = clock
        if (observed.size > 512) observed.remove(observed.keys.first())
        if (pieces.size >= 64) pieces.remove(pieces.keys.first())
        val motion = Vec3(vehicle.aircraftWreckMotionX.toDouble(), vehicle.aircraftWreckMotionY.toDouble(),
            vehicle.aircraftWreckMotionZ.toDouble())
            .takeIf { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() && it.lengthSqr() < 10000 }
            ?: vehicle.deltaMovement
        val gravity = (vehicle.resolveVehicleFlightStrategy() as? FixedWingFlightStrategy)
            ?.handling?.gravityMps2?.div(400.0) ?: (9.80665 / 400.0)
        val spin = Vec3(random.nextDouble() * .06 - .03, random.nextDouble() * .04 - .02,
            (if (side == AircraftWreckBreakup.LEFT) -1 else 1) * (.025 + random.nextDouble() * .045))
        pieces[key] = Part(AircraftDebrisMotion(center, motion, orientation, spin,
            random.nextDouble() * Math.PI * 2, gravity), visual, halfExtents.length().coerceIn(.25, 24.0),
            listOf(Vec3.ZERO) + (0..7).map { bits -> Vec3(
                halfExtents.x * if (bits and 1 == 0) -1 else 1,
                halfExtents.y * if (bits and 2 == 0) -1 else 1,
                halfExtents.z * if (bits and 4 == 0) -1 else 1) })
    }

    @SubscribeEvent fun tick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        syncWorld()
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        if (mc.isPaused) return
        clock++
        val terrain = com.atsuishio.superbwarfare.compat.voxy.ClientDebrisTerrain
        terrain.tick()
        var emissions = 0
        pieces.entries.removeIf { (_, part) -> part.motion.age >= 1200 || part.motion.position.y < level.minBuildHeight - 32 }
        for (part in pieces.values) {
            val motion = part.motion
            if (!motion.grounded) {
                terrain.prefetch(motion.position)
                terrain.prefetch(motion.position.add(motion.velocity.scale(12.0)).add(0.0, -2.0, 0.0))
            }
            motion.tick { from, to ->
                part.corners.mapNotNull { local ->
                    val rotated = motion.orientation.transform(org.joml.Vector3f(local.x.toFloat(), local.y.toFloat(), local.z.toFloat()))
                    val offset = Vec3(rotated.x.toDouble(), rotated.y.toDouble(), rotated.z.toDouble())
                    val hit = terrain.clip(ClipContext(from.add(offset), to.add(offset), ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.ANY, mc.player))
                    hit.location.subtract(offset).takeIf { hit.type != HitResult.Type.MISS }
                }.minByOrNull { it.distanceToSqr(from) }
            }
            if (!motion.grounded && emissions < 64 &&
                motion.position.distanceToSqr(mc.gameRenderer.mainCamera.position) < 16384.0 * 16384) {
                AircraftCombatParticles.fire(motion.position, 2.2f, false)
                if (clock % 2L == 0L) AircraftCombatParticles.fire(motion.position, 3.2f, true)
                emissions += 2
            }
        }
    }

    @SubscribeEvent fun render(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_ENTITIES || pieces.isEmpty()) return
        val mc = Minecraft.getInstance()
        val camera = event.camera.position
        val buffers = mc.renderBuffers().bufferSource()
        val pose = event.poseStack
        val oldStart = RenderSystem.getShaderFogStart()
        val oldEnd = RenderSystem.getShaderFogEnd()
        val frustum = net.minecraft.client.renderer.culling.Frustum(event.poseStack.last().pose(), RenderSystem.getProjectionMatrix())
        frustum.prepare(camera.x, camera.y, camera.z)
        buffers.endBatch()
        try {
            RenderSystem.setShaderFogStart(maxOf(oldStart, FarTerrainClient.renderRadius().toFloat()))
            RenderSystem.setShaderFogEnd(maxOf(oldEnd, FarTerrainClient.renderRadius() + 512f))
            for (part in pieces.values) {
                val motion = part.motion
                val position = motion.previousPosition.lerp(motion.position, event.partialTick.toDouble())
                if (position.distanceToSqr(camera) > 16384.0 * 16384) continue
                val bounds = net.minecraft.world.phys.AABB(position, position).inflate(part.radius)
                if (!frustum.isVisible(bounds)) continue
                pose.pushPose()
                try {
                    pose.translate(position.x - camera.x, position.y - camera.y, position.z - camera.z)
                    pose.mulPose(Quaternionf(motion.previousOrientation).slerp(motion.orientation, event.partialTick))
                    part.visual.render(pose, buffers, motion.grounded)
                } finally { pose.popPose() }
            }
            buffers.endBatch()
        } finally {
            RenderSystem.setShaderFogStart(oldStart); RenderSystem.setShaderFogEnd(oldEnd)
        }
    }
}
