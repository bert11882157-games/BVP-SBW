package com.atsuishio.superbwarfare.api.vehicle.action

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation

/**
 * Delta-only action transaction. Rollback subtracts only gains made by this action, preserving
 * damage and independent repairs that occur while the segment is running.
 */
class VehicleActionTransactionJournal {
    private data class ModuleGain(var gain: Float, var destroyedBefore: Boolean)

    private var hullGain = 0F
    private val moduleGains = LinkedHashMap<ResourceLocation, ModuleGain>()

    fun recordHullGain(gain: Float) {
        if (gain.isFinite() && gain > 0F) hullGain += gain
    }

    fun recordModuleGain(id: ResourceLocation, gain: Float, destroyedBefore: Boolean) {
        val boundedGain = gain.takeIf { it.isFinite() && it > 0F } ?: 0F
        val existing = moduleGains[id]
        if (existing == null) {
            moduleGains[id] = ModuleGain(boundedGain, destroyedBefore)
        } else {
            existing.gain += boundedGain
            existing.destroyedBefore = existing.destroyedBefore || destroyedBefore
        }
    }

    fun isEmpty(): Boolean = hullGain <= 0F && moduleGains.isEmpty()

    /** Commits the current segment and starts a fresh empty transaction. */
    fun commit() {
        hullGain = 0F
        moduleGains.clear()
    }

    /** Rolls back the current segment by delta, then starts a fresh empty transaction. */
    fun rollback(vehicle: VehicleEntity) {
        if (!vehicle.level().isClientSide) {
            if (hullGain > 0F) {
                vehicle.health = (vehicle.health - hullGain).coerceAtLeast(0F)
            }
            for ((id, repair) in moduleGains) {
                val current = vehicle.getVehicleModuleState(id) ?: continue
                vehicle.setVehicleModuleState(
                    id,
                    (current.health - repair.gain).coerceAtLeast(0F).toDouble(),
                    current.destroyed || repair.destroyedBefore,
                )
            }
        }
        commit()
    }

    fun save(): CompoundTag {
        val tag = CompoundTag()
        tag.putFloat(HULL_GAIN_TAG, hullGain.coerceAtLeast(0F))
        if (moduleGains.isNotEmpty()) {
            val modules = CompoundTag()
            for ((id, repair) in moduleGains) {
                val entry = CompoundTag()
                entry.putFloat(GAIN_TAG, repair.gain.coerceAtLeast(0F))
                entry.putBoolean(DESTROYED_BEFORE_TAG, repair.destroyedBefore)
                modules.put(id.toString(), entry)
            }
            tag.put(MODULE_GAINS_TAG, modules)
        }
        return tag
    }

    companion object {
        private const val HULL_GAIN_TAG = "HullGain"
        private const val MODULE_GAINS_TAG = "ModuleGains"
        private const val GAIN_TAG = "Gain"
        private const val DESTROYED_BEFORE_TAG = "DestroyedBefore"

        @JvmStatic
        fun load(tag: CompoundTag): VehicleActionTransactionJournal {
            val journal = VehicleActionTransactionJournal()
            journal.hullGain = tag.getFloat(HULL_GAIN_TAG).takeIf { it.isFinite() && it > 0F } ?: 0F
            if (!tag.contains(MODULE_GAINS_TAG)) return journal

            val modules = tag.getCompound(MODULE_GAINS_TAG)
            for (rawId in modules.allKeys) {
                val id = ResourceLocation.tryParse(rawId) ?: continue
                val entry = modules.getCompound(rawId)
                journal.recordModuleGain(
                    id,
                    entry.getFloat(GAIN_TAG),
                    entry.getBoolean(DESTROYED_BEFORE_TAG),
                )
            }
            return journal
        }
    }
}
