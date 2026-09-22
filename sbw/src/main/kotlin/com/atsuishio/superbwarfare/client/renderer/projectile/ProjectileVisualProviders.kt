package com.atsuishio.superbwarfare.client.renderer.projectile

import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import java.util.concurrent.ConcurrentHashMap

fun interface ProjectileVisualProvider {
    /** Returns true only after rendering the complete projectile body. */
    fun render(
        entity: Entity,
        yaw: Float,
        partialTick: Float,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int,
    ): Boolean
}

fun interface ProjectileVisualProviderFactory {
    fun create(context: EntityRendererProvider.Context): ProjectileVisualProvider
}

/** Client-only per-instance renderer extension point keyed by VisualProfile. */
object ProjectileVisualProviders {
    private val factories = ConcurrentHashMap<ResourceLocation, ProjectileVisualProviderFactory>()

    @JvmStatic
    fun register(id: ResourceLocation, factory: ProjectileVisualProviderFactory) {
        factories[id] = factory
    }

    @JvmStatic
    fun unregister(id: ResourceLocation) {
        factories.remove(id)
    }

    internal fun factory(id: ResourceLocation): ProjectileVisualProviderFactory? = factories[id]
}

internal class ProjectileVisualRenderHost(
    private val context: EntityRendererProvider.Context,
) {
    private val providers = HashMap<ResourceLocation, ProjectileVisualProvider>()

    fun render(
        entity: Entity,
        yaw: Float,
        partialTick: Float,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int,
    ): Boolean {
        val visualId = ProjectileProfiles.resolve(entity)?.visualProfileId ?: return false
        val factory = ProjectileVisualProviders.factory(visualId) ?: return false
        val provider = providers.getOrPut(visualId) { factory.create(context) }
        return provider.render(entity, yaw, partialTick, poseStack, buffer, packedLight)
    }
}
