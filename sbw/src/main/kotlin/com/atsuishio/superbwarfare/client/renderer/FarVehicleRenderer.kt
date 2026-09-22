package com.atsuishio.superbwarfare.client.renderer

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleCopies
import com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleDiagnostics
import com.atsuishio.superbwarfare.client.FarVehicleClient
import com.atsuishio.superbwarfare.client.FarTerrainClient
import com.atsuishio.superbwarfare.client.FarEffectsClient
import com.atsuishio.superbwarfare.api.vehicle.render.FarTerrainPolicy
import com.atsuishio.superbwarfare.client.renderer.entity.VehicleRenderer
import com.atsuishio.superbwarfare.config.client.FarVehicleRenderConfig
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.level.material.FogType
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.joml.Matrix4f
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.IdentityHashMap

/** Additional visual pass for loaded server vehicles beyond vanilla's tracking/render distances. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object FarVehicleRenderer {
    private val logger = LoggerFactory.getLogger(FarVehicleRenderer::class.java)
    private val drawn = Collections.newSetFromMap(IdentityHashMap<VehicleEntity, Boolean>())
    private var rendering = false

    data class VehicleFog(val start: Float, val end: Float,
                          val buffers: net.minecraft.client.renderer.MultiBufferSource.BufferSource)

    /** Both sides of the tracking boundary use the same vehicle fog; other entities retain theirs. */
    fun beginVehicleFog(vehicle: VehicleEntity, source: net.minecraft.client.renderer.MultiBufferSource): VehicleFog? {
        if (rendering || !FarVehicleRenderConfig.ENABLED.get() || !FarTerrainClient.ready()) return null
        val mc = Minecraft.getInstance()
        if (vehicle.level() !== mc.level || source !== mc.renderBuffers().bufferSource() ||
            source !is net.minecraft.client.renderer.MultiBufferSource.BufferSource ||
            mc.gameRenderer.mainCamera.fluidInCamera != FogType.NONE ||
            mc.player?.hasEffect(MobEffects.BLINDNESS) == true || mc.player?.hasEffect(MobEffects.DARKNESS) == true) return null
        val old = VehicleFog(RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd(), source)
        source.endBatch()
        val range = FarTerrainClient.renderRadius()
        RenderSystem.setShaderFogStart(maxOf(old.start, range.toFloat()))
        RenderSystem.setShaderFogEnd(maxOf(old.end, range + 512F))
        return old
    }

    fun endVehicleFog(state: VehicleFog?) {
        if (state == null) return
        try { state.buffers.endBatch() }
        finally {
            RenderSystem.setShaderFogStart(state.start)
            RenderSystem.setShaderFogEnd(state.end)
        }
    }

    fun markDrawn(vehicle: VehicleEntity) {
        if (!rendering) {
            drawn.add(vehicle)
            FarVehicleDiagnostics.visibility(vehicle, "DRAWN_NATIVE")
        }
    }

    @SubscribeEvent
    fun start(event: TickEvent.RenderTickEvent) {
        if (event.phase == TickEvent.Phase.START) drawn.clear()
    }

    @SubscribeEvent
    fun render(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_ENTITIES || !FarVehicleRenderConfig.ENABLED.get()) return
        val level = FarVehicleClient.currentLevel() ?: return
        if (!FarTerrainClient.ready()) {
            FarVehicleDiagnostics.renderPass(level.gameTime, FarVehicleClient.store.values().size,
                0, 0, FarTerrainClient.radius(), "SESSION_PENDING")
            return
        }
        val mc = Minecraft.getInstance()
        val camera = event.camera
        // Preserve environmental visibility restrictions instead of extending their fog distances.
        if (camera.fluidInCamera != FogType.NONE || mc.player?.hasEffect(MobEffects.BLINDNESS) == true ||
            mc.player?.hasEffect(MobEffects.DARKNESS) == true) return
        val range = FarTerrainClient.renderRadius()
        val origin = camera.position
        val entries = FarVehicleClient.store.values().asSequence()
            .sortedBy { it.current.distanceSquared(origin.x, origin.y, origin.z) }
            .toList()
        val effects = FarEffectsClient.frame()
        if (entries.isEmpty() && effects.isEmpty()) {
            FarTerrainClient.prioritize(emptyList())
            FarVehicleDiagnostics.renderPass(level.gameTime, 0, 0, 0, range, "EMPTY_FRAME")
            return
        }
        val resolved = entries.mapNotNull { entry ->
            FarVehicleClient.resolve(entry, event.partialTick)?.let {
                Triple(entry, it, FarVehicleClient.renderBounds(it, event.partialTick))
            }
        }
        val oldProjection = Matrix4f(RenderSystem.getProjectionMatrix())
        val frustum = Frustum(event.poseStack.last().pose(), oldProjection)
        frustum.prepare(origin.x, origin.y, origin.z)
        // Frustum culling affects drawing only; subscription and copies survive off-screen views.
        FarTerrainClient.prioritize(emptyList())
        val sorting = RenderSystem.getVertexSorting()
        // GameRenderer extends the shared projection before ANY terrain/entity depth is written.
        val projection = oldProjection
        val oldFogStart = RenderSystem.getShaderFogStart()
        val oldFogEnd = RenderSystem.getShaderFogEnd()
        val buffers = mc.renderBuffers().bufferSource()
        // Finish ordinary entity geometry with its original projection and fog.
        buffers.endBatch()
        var count = 0
        var copyCount = 0
        val drawGate = "DRAW_ATTEMPTED"
        rendering = true
        try {
            RenderSystem.setProjectionMatrix(projection, sorting)
            RenderSystem.setShaderFogStart(maxOf(oldFogStart, range.toFloat()))
            RenderSystem.setShaderFogEnd(maxOf(oldFogEnd, range + 512F))
            for ((entry, vehicle, bounds) in resolved) {
                if (count >= FarVehicleRenderConfig.MAX_VEHICLES.get()) break
                try {
                    if (vehicle in drawn || vehicle.isRemoved || vehicle.isInvisible ||
                        !frustum.isVisible(bounds.inflate(FarTerrainPolicy.RENDER_PADDING_BLOCKS))) continue
                    val position = vehicle.getLegacyInterpolatedPosition(event.partialTick)
                    val renderer = mc.entityRenderDispatcher.getRenderer(vehicle)
                    // Third-party renderers may not participate in markDrawn; retain their normal culling contract.
                    if (!FarVehicleCopies.isCopy(vehicle) && renderer !is VehicleRenderer<*> &&
                        !FarTerrainClient.deferNative(vehicle) &&
                        renderer.shouldRender(vehicle, event.frustum, origin.x, origin.y, origin.z)) continue
                    val light = renderer.getPackedLightCoords(vehicle, event.partialTick)
                    event.poseStack.pushPose()
                    try {
                        event.poseStack.translate(position.x - origin.x, position.y - origin.y, position.z - origin.z)
                        renderer.render(vehicle, vehicle.getYaw(event.partialTick), event.partialTick,
                            event.poseStack, buffers, light)
                        count++
                        FarVehicleDiagnostics.visibility(vehicle, "DRAWN_FAR")
                        if (FarVehicleCopies.isCopy(vehicle)) copyCount++
                    } finally {
                        event.poseStack.popPose()
                    }
                } catch (exception: RuntimeException) {
                    FarVehicleClient.renderFailed(entry.current.type)
                    logger.warn("Disabling far rendering for vehicle type {} until reload", entry.current.type, exception)
                }
            }
            FarEffectsClient.render(effects, event)
        } finally {
            try {
                // Flush while the extended projection/fog are active, including exceptional exits.
                buffers.endBatch()
            } finally {
                RenderSystem.setProjectionMatrix(oldProjection, sorting)
                RenderSystem.setShaderFogStart(oldFogStart)
                RenderSystem.setShaderFogEnd(oldFogEnd)
                rendering = false
                FarVehicleDiagnostics.renderPass(level.gameTime, entries.size, count, copyCount, range,
                    drawGate, 0)
            }
        }
    }

    internal fun extendedProjection(original: Matrix4f, far: Float): Matrix4f? {
        val near = original.m32() / (original.m22() - 1F)
        if (!near.isFinite() || near <= 0F || !far.isFinite() || far <= near) return null
        return Matrix4f(original).m22(-(far + near) / (far - near)).m32(-(2F * far * near) / (far - near))
    }
}
