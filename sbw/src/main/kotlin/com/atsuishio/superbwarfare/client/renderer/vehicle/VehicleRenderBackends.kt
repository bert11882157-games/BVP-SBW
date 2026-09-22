package com.atsuishio.superbwarfare.client.renderer.vehicle

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.internal.OrderedProviderRegistry
import com.atsuishio.superbwarfare.api.vehicle.pose.VehicleChassisPresentation
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.joml.Matrix3f
import org.joml.Matrix4f
import software.bernie.geckolib.cache.`object`.BakedGeoModel
import java.util.WeakHashMap

/**
 * Complete geometry render request made after Gecko has evaluated animations.
 * Implementations must balance PoseStack pushes and return false without
 * emitting geometry when they want SBW's native renderer to handle the body.
 */
data class VehicleRenderBackendContext(
    val backendId: ResourceLocation,
    val vehicle: VehicleEntity,
    /** Immutable anchor/yaw/full-pose sample shared by every consumer in this render pass. */
    val chassisPresentation: VehicleChassisPresentation,
    val entityYaw: Float,
    val partialTick: Float,
    val poseStack: PoseStack,
    /** Pose at VehicleRenderer.render entry, before SBW applies vehicleAxis. */
    val preVehicleAxisPose: Matrix4f,
    /** Normal matrix paired with preVehicleAxisPose. */
    val preVehicleAxisNormal: Matrix3f,
    val bufferSource: MultiBufferSource,
    val nativeModel: BakedGeoModel,
    val nativeRenderType: RenderType?,
    val nativeVertexConsumer: VertexConsumer?,
    val resolvedTexture: ResourceLocation,
    val isReRender: Boolean,
    val packedLight: Int,
    val packedOverlay: Int,
)

fun interface VehicleRenderBackend {
    /**
     * Returns true after rendering the complete replacement body, or when the backend has
     * deliberately suppressed native cubes during a bounded pending-load interval.
     */
    fun render(context: VehicleRenderBackendContext): Boolean
}

fun interface VehicleRenderBackendProvider {
    /** Creates one backend instance for an SBW entity-renderer instance. */
    fun create(context: EntityRendererProvider.Context): VehicleRenderBackend
}

/** Client-only registry keyed by the namespaced GeometryBackend resource value. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(
    modid = Mod.MODID,
    bus = net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus.MOD,
    value = [Dist.CLIENT],
)
object VehicleRenderBackends {
    private val providers = OrderedProviderRegistry<ResourceLocation, VehicleRenderBackendProvider>()

    @Volatile
    private var generation = 0

    @JvmStatic
    fun register(id: ResourceLocation, provider: VehicleRenderBackendProvider) {
        synchronized(this) {
            providers.register(id, provider)
            generation++
        }
    }

    @JvmStatic
    fun unregister(id: ResourceLocation): Boolean {
        return synchronized(this) {
            val removed = providers.unregister(id)
            if (removed) generation++
            removed
        }
    }

    internal fun provider(id: ResourceLocation): VehicleRenderBackendProvider? = providers[id]

    internal fun generation(): Int = generation

    @SubscribeEvent
    @JvmStatic
    fun onRegisterReloadListeners(event: RegisterClientReloadListenersEvent) {
        event.registerReloadListener(ResourceManagerReloadListener { invalidateInstances() })
    }

    private fun invalidateInstances() {
        synchronized(this) {
            generation++
        }
    }
}

internal class VehicleRenderBackendHost(
    private val rendererContext: EntityRendererProvider.Context,
) {
    private val instances = HashMap<ResourceLocation, VehicleRenderBackend>()
    /** Registration or construction failures are backend-wide until the next resource generation. */
    private val unavailable = HashSet<ResourceLocation>()
    /** Render failures are isolated to the entity that triggered them instead of disabling the fleet. */
    private val renderFailures = WeakHashMap<VehicleEntity, MutableMap<ResourceLocation, Int>>()
    private var observedGeneration = -1

    fun render(context: VehicleRenderBackendContext): Boolean {
        refreshIfNeeded()
        if (context.backendId in unavailable) return false
        if ((renderFailures[context.vehicle]?.get(context.backendId) ?: 0) >= MAX_ENTITY_RENDER_FAILURES) {
            return false
        }

        var backend = instances[context.backendId]
        if (backend == null) {
            val provider = VehicleRenderBackends.provider(context.backendId)
            if (provider == null) {
                unavailable.add(context.backendId)
                Mod.LOGGER.warn(
                    "Vehicle render backend {} is not registered; using native geometry",
                    context.backendId,
                )
                return false
            }
            backend = try {
                provider.create(rendererContext)
            } catch (exception: RuntimeException) {
                unavailable.add(context.backendId)
                Mod.LOGGER.warn("Vehicle render backend {} failed to initialize", context.backendId, exception)
                return false
            }
            instances[context.backendId] = backend
        }

        context.poseStack.pushPose()
        return try {
            backend.render(context).also {
                if (it) renderFailures[context.vehicle]?.remove(context.backendId)
            }
        } catch (exception: RuntimeException) {
            val vehicleFailures = renderFailures.getOrPut(context.vehicle) { HashMap() }
            val failures = (vehicleFailures[context.backendId] ?: 0) + 1
            vehicleFailures[context.backendId] = failures
            if (failures == 1 || failures == MAX_ENTITY_RENDER_FAILURES) {
                Mod.LOGGER.warn(
                    "Vehicle render backend {} failed for {} ({}/{}); using native geometry for this entity",
                    context.backendId,
                    context.vehicle,
                    failures,
                    MAX_ENTITY_RENDER_FAILURES,
                    exception,
                )
            }
            false
        } finally {
            context.poseStack.popPose()
        }
    }

    private fun refreshIfNeeded() {
        val currentGeneration = VehicleRenderBackends.generation()
        if (currentGeneration == observedGeneration) return
        observedGeneration = currentGeneration
        instances.clear()
        unavailable.clear()
        renderFailures.clear()
    }

    private companion object {
        const val MAX_ENTITY_RENDER_FAILURES = 3
    }
}
