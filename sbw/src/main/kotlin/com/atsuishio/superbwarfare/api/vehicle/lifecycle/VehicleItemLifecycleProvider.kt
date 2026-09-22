package com.atsuishio.superbwarfare.api.vehicle.lifecycle

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

/**
 * Bounded bridge between the shared vehicle authority and an add-on's real vehicle item.
 *
 * Providers own the item type, display, placement collision/spawn transaction, and any
 * add-on-specific durable state.  The shared entity only asks for a single stack on recovery
 * and exposes a fresh entity plus validated durable state on placement.
 */
interface VehicleItemLifecycleProvider {
    /** Returns exactly one item, or null when this provider does not own [vehicle]. */
    fun createFromEntity(vehicle: VehicleEntity): ItemStack?

    /** Creates an unspawned fresh entity for a placement transaction. */
    fun createFromType(type: EntityType<*>, level: Level): VehicleEntity?

    /** Restores only validated durable state onto a fresh entity. */
    fun restorePlacementState(vehicle: VehicleEntity, state: CompoundTag): Boolean
}

/** Ordered common-side registration point for BVP and other vehicle-item owners. */
object VehicleItemLifecycleProviders {
    private val providers =
        com.atsuishio.superbwarfare.api.internal.OrderedProviderRegistry<ResourceLocation, VehicleItemLifecycleProvider>()

    @JvmStatic
    fun register(id: ResourceLocation, provider: VehicleItemLifecycleProvider) {
        providers.register(id, provider)
    }

    @JvmStatic
    fun unregister(id: ResourceLocation): Boolean = providers.unregister(id)

    /**
     * Resolves the first provider that owns the entity.  A provider returns null when it does
     * not own the entity; a non-null empty/invalid stack claims ownership but fails closed, so a
     * BVP entity can never silently fall back to a legacy container after a codec failure.
     */
    @JvmStatic
    fun createFromEntity(vehicle: VehicleEntity): ItemStack? {
        for ((id, provider) in providers.snapshot()) {
            try {
                val stack = provider.createFromEntity(vehicle) ?: continue
                return if (!stack.isEmpty && stack.count == 1) stack else ItemStack.EMPTY
            } catch (exception: RuntimeException) {
                com.atsuishio.superbwarfare.Mod.LOGGER.warn(
                    "Vehicle item provider {} failed to recover {}",
                    id,
                    vehicle,
                    exception,
                )
                return ItemStack.EMPTY
            }
        }
        return null
    }

    @JvmStatic
    fun createFromType(
        providerId: ResourceLocation,
        type: EntityType<*>,
        level: Level,
    ): VehicleEntity? {
        val provider = providers[providerId] ?: return null
        return try {
            provider.createFromType(type, level)
        } catch (exception: RuntimeException) {
            com.atsuishio.superbwarfare.Mod.LOGGER.warn(
                "Vehicle item provider {} failed to create {}",
                providerId,
                type,
                exception,
            )
            null
        }
    }

    @JvmStatic
    fun restorePlacementState(
        providerId: ResourceLocation,
        vehicle: VehicleEntity,
        state: CompoundTag,
    ): Boolean {
        val provider = providers[providerId] ?: return false
        return try {
            provider.restorePlacementState(vehicle, state)
        } catch (exception: RuntimeException) {
            com.atsuishio.superbwarfare.Mod.LOGGER.warn(
                "Vehicle item provider {} failed to restore {}",
                providerId,
                vehicle,
                exception,
            )
            false
        }
    }
}

/** Stable state envelope names shared by a vehicle item and its provider. */
object VehicleItemLifecycleCodec {
    const val SCHEMA_TAG: String = "Schema"
    const val ENTITY_TYPE_TAG: String = "EntityType"
    const val SCHEMA_VERSION: Int = 1
}
