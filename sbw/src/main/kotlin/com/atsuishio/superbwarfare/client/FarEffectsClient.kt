package com.atsuishio.superbwarfare.client

import com.atsuishio.superbwarfare.api.projectile.FarProjectileAccess
import com.atsuishio.superbwarfare.api.vehicle.render.FarTerrainPolicy
import com.atsuishio.superbwarfare.config.client.FarVehicleRenderConfig
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.Tesselator
import net.minecraft.client.Minecraft
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraftforge.client.event.RenderLevelStageEvent
import java.util.Collections
import java.util.IdentityHashMap
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraft.client.Screenshot
import com.atsuishio.superbwarfare.Mod

/** Native particles keep their native tick/lifetime owner; only their distant draw pass changes. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object FarEffectsClient {
    private val particles = Collections.newSetFromMap(IdentityHashMap<Particle, Boolean>())
    private val retainedParticles = Collections.newSetFromMap(IdentityHashMap<Particle, Boolean>())

    /** Explicit server event/rendered wreck admission, still bounded by the shared particle budget. */
    fun retainParticle(particle: Particle) {
        if (particles.size < 2048 && retainedParticles.size < 2048) {
            retainedParticles.add(particle)
            if (outsideNative(particle.boundingBox.center)) particles.add(particle)
        }
    }
    private var lastDiagnostic = Long.MIN_VALUE
    private var captureSession: java.util.UUID? = null
    private var projectileCaptures = 0
    private var particleCaptures = 0
    private var lastCaptureNanos = 0L
    private var pendingCapture: Pair<Int, Int>? = null

    /** Passive framebuffer evidence only in explicitly enabled disposable diagnostic sessions. */
    @SubscribeEvent fun capture(event: TickEvent.RenderTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val counts = pendingCapture ?: return
        pendingCapture = null
        val mc = Minecraft.getInstance()
        val session = EliteDiagnostics.clientSessionId() ?: return
        if (session != captureSession || !EliteDiagnostics.isClientEnabled() || mc.screen != null ||
            !java.lang.Boolean.getBoolean("bvp.diagnostics.scenarios")) return
        val kind = if (counts.second > 0) "particles" else "projectile"
        if (kind == "particles") particleCaptures++ else projectileCaptures++
        val filename = "far-fx-$session-$kind-${projectileCaptures + particleCaptures}.png"
        lastCaptureNanos = System.nanoTime()
        Screenshot.grab(mc.gameDirectory, filename, mc.mainRenderTarget) {
            EliteDiagnostics.recordClient(mc.level?.gameTime ?: 0, "far_effect", "FRAMEBUFFER_RESULT",
                "filename", filename, "projectiles", counts.first, "particles", counts.second, "result", it.string)
        }
    }
    data class ParticleGroup(val bounds: AABB, val particles: List<Particle>)
    data class Frame(val projectiles: List<Entity>, val groups: List<ParticleGroup>) {
        fun bounds(): List<AABB> = projectiles.map { it.boundingBox.expandTowards(it.deltaMovement.scale(-1.0)) } + groups.map { it.bounds }
        fun isEmpty() = projectiles.isEmpty() && groups.isEmpty()
    }

    @JvmStatic fun clear() { particles.clear(); retainedParticles.clear(); lastDiagnostic = Long.MIN_VALUE }

    private fun outsideNative(position: Vec3, halfWidth: Double = 0.0): Boolean {
        val mc = Minecraft.getInstance()
        val camera = mc.gameRenderer.mainCamera.position
        val dx = position.x - camera.x; val dz = position.z - camera.z
        return FarTerrainPolicy.deferNative(dx * dx + dz * dz, mc.options.effectiveRenderDistance,
            halfWidth, FarVehicleRenderConfig.ENABLED.get(), false)
    }

    @JvmStatic fun deferProjectile(entity: Entity): Boolean = entity is FarProjectileAccess &&
        outsideNative(entity.position(), maxOf(entity.bbWidth.toDouble(), entity.deltaMovement.length()))

    @JvmStatic fun deferParticle(particle: Particle): Boolean {
        if (!outsideNative(particle.boundingBox.center)) return false
        val mc = Minecraft.getInstance()
        val origin = mc.gameRenderer.mainCamera.position
        val point = particle.boundingBox.center
        if (particles.size < 2048 && (particle in retainedParticles ||
            FarTerrainPolicy.inside(origin.x, origin.z, point.x, point.z, FarTerrainClient.renderRadius())))
            particles.add(particle)
        return true
    }

    @JvmStatic fun light(particle: Particle): Int? {
        if (particle !in particles) return null
        val pos = BlockPos.containing(particle.boundingBox.center)
        return if (FarTerrainClient.lightOverride(net.minecraft.world.level.LightLayer.SKY, pos) != null)
            LevelRenderer.getLightColor(FarTerrainClient, pos) else null
    }

    fun frame(): Frame {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return Frame(emptyList(), emptyList())
        val origin = mc.gameRenderer.mainCamera.position
        val radius = FarTerrainClient.renderRadius()
        retainedParticles.removeIf { !it.isAlive }
        particles.removeIf { !it.isAlive || !outsideNative(it.boundingBox.center) ||
            (it !in retainedParticles && !FarTerrainPolicy.inside(origin.x, origin.z, it.boundingBox.center.x, it.boundingBox.center.z, radius)) }
        val projectiles = level.entitiesForRendering().asSequence()
            .filter { !it.isRemoved && deferProjectile(it) && FarTerrainPolicy.inside(origin.x, origin.z, it.x, it.z, radius) }
            .sortedBy { it.distanceToSqr(origin) }.take(512).toList()
        val groups = particles.groupBy { net.minecraft.core.SectionPos.asLong(BlockPos.containing(it.boundingBox.center)) }
            .values.map { group -> ParticleGroup(group.map { it.boundingBox }.reduce(AABB::minmax), group) }
        return Frame(projectiles, groups)
    }

    fun render(frame: Frame, event: RenderLevelStageEvent) {
        val mc = Minecraft.getInstance()
        val camera = event.camera
        val origin = camera.position
        val buffers = mc.renderBuffers().bufferSource()
        var projectileDraws = 0
        for (entity in frame.projectiles) {
            val renderer = mc.entityRenderDispatcher.getRenderer(entity)
            val position = entity.getPosition(event.partialTick)
            event.poseStack.pushPose()
            try {
                event.poseStack.translate(position.x - origin.x, position.y - origin.y, position.z - origin.z)
                renderer.render(entity, entity.yRot, event.partialTick, event.poseStack, buffers,
                    renderer.getPackedLightCoords(entity, event.partialTick))
                projectileDraws++
            } finally { event.poseStack.popPose() }
        }
        buffers.endBatch()
        val visible = frame.groups.flatMap { it.particles }
        val modelView = RenderSystem.getModelViewStack()
        modelView.pushPose()
        mc.gameRenderer.lightTexture().turnOnLightLayer()
        RenderSystem.enableDepthTest()
        try {
            modelView.mulPoseMatrix(event.poseStack.last().pose())
            RenderSystem.applyModelViewMatrix()
            for ((type, group) in visible.groupBy { it.renderType }) {
                if (type == ParticleRenderType.NO_RENDER) continue
                RenderSystem.setShader(GameRenderer::getParticleShader)
                val tessellator = Tesselator.getInstance()
                type.begin(tessellator.builder, mc.textureManager)
                try {
                    if (EliteDiagnostics.isClientEnabled() &&
                        (lastDiagnostic == Long.MIN_VALUE || (mc.level?.gameTime ?: 0) - lastDiagnostic >= 20)) {
                        val point = group.first().boundingBox.center
                        val clip = org.joml.Vector4f((point.x - origin.x).toFloat(),
                            (point.y - origin.y).toFloat(), (point.z - origin.z).toFloat(), 1F)
                            .mul(RenderSystem.getModelViewMatrix()).mul(RenderSystem.getProjectionMatrix())
                        EliteDiagnostics.recordClient(mc.level?.gameTime ?: 0, "far_effect", "PARTICLE_STATE",
                            "type", type.toString(), "count", group.size, "position", point,
                            "camera", origin, "clip", listOf(clip.x, clip.y, clip.z, clip.w),
                            "fog_start", RenderSystem.getShaderFogStart(), "fog_end", RenderSystem.getShaderFogEnd(),
                            "shader_color", RenderSystem.getShaderColor().toList(),
                            "depth_test", org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST),
                            "depth_function", org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_DEPTH_FUNC),
                            "cull", org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_CULL_FACE),
                            "framebuffer", org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING))
                    }
                    group.forEach { it.render(tessellator.builder, camera, event.partialTick) }
                }
                finally { type.end(tessellator) }
            }
        } finally {
            modelView.popPose(); RenderSystem.applyModelViewMatrix()
            RenderSystem.depthMask(true); RenderSystem.disableBlend()
            mc.gameRenderer.lightTexture().turnOffLightLayer()
        }
        val tick = mc.level?.gameTime ?: return
        if (EliteDiagnostics.isClientEnabled() && java.lang.Boolean.getBoolean("bvp.diagnostics.scenarios")) {
            val session = EliteDiagnostics.clientSessionId()
            if (captureSession != session) {
                captureSession = session; projectileCaptures = 0; particleCaptures = 0; lastCaptureNanos = 0
            }
            if (System.nanoTime() - lastCaptureNanos >= 150_000_000L &&
                ((visible.isNotEmpty() && particleCaptures < 3) ||
                    (visible.isEmpty() && projectileDraws > 0 && projectileCaptures < 3)))
                pendingCapture = projectileDraws to visible.size
        }
        if (tick - lastDiagnostic >= 20 || lastDiagnostic == Long.MIN_VALUE) {
            lastDiagnostic = tick
            EliteDiagnostics.recordClient(tick, "far_effect", "RENDERED", "projectiles", projectileDraws,
                "particles", visible.size, "pending_projectiles", frame.projectiles.size - projectileDraws,
                "pending_particles", particles.size - visible.size)
        }
    }
}
