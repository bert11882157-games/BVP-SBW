package com.atsuishio.superbwarfare.client.renderer

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.vehicle.flight.WreckDebrisPhysics
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.joml.Quaternionf

/**
 * Parts a vehicle throws off (the Shturm-S spent launcher tube, owner 2026-09-29): client-side physics objects that
 * fall, tumble, hit and slide on the terrain with the wreck-debris motion, and vanish after a fixed life. No server
 * entity, packets or damage; every client simulates its own copy.
 */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object JettisonedParts {
    /** Draws the part in its own frame (origin at its centre); the pose is already placed and oriented. */
    fun interface Visual {
        fun render(pose: PoseStack, buffers: MultiBufferSource, light: Int)
    }

    private class Part(val motion: AircraftDebrisMotion, val visual: Visual, val radius: Double, val life: Int)

    private const val MAX_PARTS = 48
    private val parts = ArrayList<Part>()
    private var world: Any? = null

    /**
     * Throws a part from [position] (its centre, world) with [velocity] (blocks per tick) and [orientation] (part frame
     * to world), tumbling at [spin] (radians per tick about the part axes), resting on a side of its [halfExtents] box
     * (x lateral, y up, z along its length), removed after [lifeTicks].
     */
    @JvmStatic
    fun spawn(position: Vec3, velocity: Vec3, orientation: Quaternionf, spin: Vec3, halfExtents: Vec3,
              lifeTicks: Int, visual: Visual) {
        if (!finite(position) || !finite(velocity) || !finite(spin) || !finite(halfExtents) || lifeTicks <= 0) return
        syncWorld()
        if (parts.size >= MAX_PARTS) parts.removeAt(0)
        val motion = AircraftDebrisMotion(position, velocity, Quaternionf(orientation).normalize(), spin,
            Math.random() * Math.PI * 2, 9.80665 / 400.0, 0, halfExtents, WreckDebrisPhysics.Rest.FUSELAGE,
            WreckDebrisPhysics.WING_FRICTION)
        motion.placeOn(surface)
        parts += Part(motion, visual, halfExtents.length().coerceIn(.25, 16.0), lifeTicks)
    }

    @JvmStatic fun count(): Int = parts.size

    private fun finite(v: Vec3) = v.x.isFinite() && v.y.isFinite() && v.z.isFinite()

    private fun syncWorld() {
        val level = Minecraft.getInstance().level
        if (world !== level) { parts.clear(); world = level }
    }

    private fun clip(start: Vec3, end: Vec3) = Minecraft.getInstance().level?.clip(ClipContext(start, end,
        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, Minecraft.getInstance().player))
        ?.takeUnless { it.type == HitResult.Type.MISS }

    private val surface = AircraftDebrisMotion.Surface { from, depth -> AircraftDebrisContact.surface(from, depth, ::clip) }

    @SubscribeEvent
    fun tick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        syncWorld()
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        if (mc.isPaused || parts.isEmpty()) return
        parts.removeIf { it.motion.age >= it.life || it.motion.position.y < level.minBuildHeight - 32 }
        for (part in parts) {
            val motion = part.motion
            motion.tick({ from, to ->
                motion.sweep(from, to) { start, end -> AircraftDebrisContact.relaxedSweep(start, end, 1.0, ::clip) }
            }, surface)
        }
    }

    @SubscribeEvent
    fun render(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_ENTITIES || parts.isEmpty()) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val camera = event.camera.position
        val buffers = mc.renderBuffers().bufferSource()
        val pose = event.poseStack
        for (part in parts) {
            val motion = part.motion
            val position = motion.previousPosition.lerp(motion.position, event.partialTick.toDouble())
            if (position.distanceToSqr(camera) > 512.0 * 512.0) continue
            if (!event.frustum.isVisible(AABB(position, position).inflate(part.radius))) continue
            val light = LevelRenderer.getLightColor(level, BlockPos.containing(position.add(0.0, part.radius * .5, 0.0)))
            pose.pushPose()
            try {
                pose.translate(position.x - camera.x, position.y - camera.y, position.z - camera.z)
                pose.mulPose(Quaternionf(motion.previousOrientation).slerp(motion.orientation, event.partialTick))
                part.visual.render(pose, buffers, light)
            } finally {
                pose.popPose()
            }
        }
        buffers.endBatch()
    }
}
