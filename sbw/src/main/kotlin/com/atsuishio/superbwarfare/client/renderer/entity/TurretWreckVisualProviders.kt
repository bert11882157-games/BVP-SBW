package com.atsuishio.superbwarfare.client.renderer.entity

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.internal.OrderedProviderRegistry
import com.atsuishio.superbwarfare.entity.vehicle.TurretWreckEntity
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

/** One already-oriented per-instance turret-wreck render request. */
data class TurretWreckVisualContext(
    val visualId: ResourceLocation,
    val wreckEntity: TurretWreckEntity,
    val poseStack: PoseStack,
    val bufferSource: MultiBufferSource,
    val partialTick: Float,
    val packedLight: Int,
    val packedOverlay: Int,
)

fun interface TurretWreckVisualProvider {
    /** Returns true only after rendering the complete wreck visual. */
    fun render(context: TurretWreckVisualContext): Boolean
}

fun interface TurretWreckVisualProviderFactory {
    fun create(): TurretWreckVisualProvider
}

/** Ordered client provider registry; the visual ID remains server-authored and synchronized. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(
    modid = Mod.MODID,
    bus = net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus.MOD,
    value = [Dist.CLIENT],
)
object TurretWreckVisualProviders {
    private val factories = OrderedProviderRegistry<ResourceLocation, TurretWreckVisualProviderFactory>()

    @Volatile
    private var generation = 0

    @JvmStatic
    fun register(id: ResourceLocation, factory: TurretWreckVisualProviderFactory) {
        synchronized(this) {
            factories.register(id, factory)
            generation++
        }
    }

    @JvmStatic
    fun unregister(id: ResourceLocation): Boolean {
        return synchronized(this) {
            if (!factories.unregister(id)) return@synchronized false
            generation++
            true
        }
    }

    internal fun snapshot(): Set<Map.Entry<ResourceLocation, TurretWreckVisualProviderFactory>> =
        factories.snapshot().entries

    internal fun generation(): Int = generation

    @SubscribeEvent
    @JvmStatic
    fun onRegisterReloadListeners(event: RegisterClientReloadListenersEvent) {
        event.registerReloadListener(ResourceManagerReloadListener {
            synchronized(this) { generation++ }
        })
    }
}

internal class TurretWreckVisualHost {
    private val instances = HashMap<ResourceLocation, TurretWreckVisualProvider>()
    private val failed = HashSet<ResourceLocation>()
    private var observedGeneration = -1

    fun render(context: TurretWreckVisualContext): Boolean {
        refreshIfNeeded()
        for ((providerId, factory) in TurretWreckVisualProviders.snapshot()) {
            if (providerId in failed) continue
            val provider = instances[providerId] ?: try {
                factory.create().also { instances[providerId] = it }
            } catch (exception: RuntimeException) {
                failed.add(providerId)
                Mod.LOGGER.warn("Turret-wreck visual provider {} failed to initialize", providerId, exception)
                continue
            }

            context.poseStack.pushPose()
            val handled = try {
                provider.render(context)
            } catch (exception: RuntimeException) {
                failed.add(providerId)
                Mod.LOGGER.warn(
                    "Turret-wreck visual provider {} failed for {}",
                    providerId,
                    context.wreckEntity,
                    exception,
                )
                false
            } finally {
                context.poseStack.popPose()
            }
            if (handled) return true
        }
        return false
    }

    private fun refreshIfNeeded() {
        val currentGeneration = TurretWreckVisualProviders.generation()
        if (currentGeneration == observedGeneration) return
        observedGeneration = currentGeneration
        instances.clear()
        failed.clear()
    }
}
