package com.atsuishio.superbwarfare.api.vehicle.weapon.prediction

import net.minecraft.resources.ResourceLocation
import java.util.concurrent.ConcurrentHashMap

/** Registry of projectile kinds whose live motion is exactly represented by the nominal kernel. */
object NominalProjectileModels {
    internal data class Model(
        val collision: NominalBlockCollisionModel,
        val motion: NominalMotionModel,
        val ownerDistanceLimitBlocks: Double? = null,
    )

    private val models = ConcurrentHashMap<ResourceLocation, Model>()

    init {
        registerLinearGravity(ResourceLocation("superbwarfare", "projectile"), NominalBlockCollisionModel.SBW_PROJECTILE)
        registerFastThrowableLinearGravity(ResourceLocation("superbwarfare", "small_rocket"), NominalBlockCollisionModel.STANDARD_PROJECTILE)
        registerFastThrowableLinearGravity(ResourceLocation("superbwarfare", "medium_rocket"), NominalBlockCollisionModel.STANDARD_PROJECTILE)
        registerFastThrowableLinearGravity(ResourceLocation("superbwarfare", "cannon_shell"), NominalBlockCollisionModel.STANDARD_PROJECTILE)
        register(
            ResourceLocation("superbwarfare", "small_cannon_shell"),
            Model(
                NominalBlockCollisionModel.STANDARD_PROJECTILE,
                NominalMotionModel.FAST_THROWABLE_LINEAR_GRAVITY_AIR,
                ownerDistanceLimitBlocks = 1024.0,
            ),
        )
    }

    @JvmStatic
    fun registerLinearGravity(projectileTypeId: ResourceLocation, collisionModel: NominalBlockCollisionModel) {
        register(projectileTypeId, Model(collisionModel, NominalMotionModel.DIRECT_LINEAR_GRAVITY))
    }

    @JvmStatic
    fun registerFastThrowableLinearGravity(
        projectileTypeId: ResourceLocation,
        collisionModel: NominalBlockCollisionModel,
    ) {
        register(projectileTypeId, Model(collisionModel, NominalMotionModel.FAST_THROWABLE_LINEAR_GRAVITY_AIR))
    }

    internal fun model(projectileTypeId: ResourceLocation): Model? =
        models[projectileTypeId]

    private fun register(projectileTypeId: ResourceLocation, model: Model) {
        val existing = models.putIfAbsent(projectileTypeId, model)
        require(existing == null || existing == model) { "Conflicting nominal projectile model: $projectileTypeId" }
    }
}
