package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.module.VehicleModuleAdapter
import com.atsuishio.superbwarfare.api.vehicle.module.VehicleModuleDefinition
import com.atsuishio.superbwarfare.api.vehicle.module.VehicleModuleIds
import com.atsuishio.superbwarfare.api.vehicle.module.VehicleModuleState
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation

/**
 * Sole owner of generic module health, persistence, and snapshot invalidation. Legacy storage
 * and addon overrides are reached only through the module access contract, never the entity.
 */
internal class VehicleModuleStateService(private val access: VehicleModuleStateAccess) {
    private val states = LinkedHashMap<ResourceLocation, StoredState>()
    private var snapshotCache: String? = null

    fun getDefinition(id: ResourceLocation): VehicleModuleDefinition? {
        access.providedDefinition(id)?.let { return it }
        val adapter = LEGACY_MODULES[id]
        if (adapter != null) {
            return VehicleModuleDefinition(id, maximum(access.legacyMaximum(adapter)), adapter)
        }
        return states[id]?.let { VehicleModuleDefinition(id, maximum(it.maxHealth)) }
    }

    fun getState(id: ResourceLocation): VehicleModuleState? {
        ensureClientSnapshot()
        val definition = access.definition(id) ?: return null
        if (definition.adapter != VehicleModuleAdapter.GENERIC) return legacyState(definition)
        val stored = states[id]
        return if (stored == null) {
            val maxHealth = definition.maxHealth
            VehicleModuleState(id, maxHealth, maxHealth, false)
        } else {
            VehicleModuleState(id, stored.maxHealth, stored.health.coerceIn(0f, stored.maxHealth),
                stored.destroyed || stored.health <= 0f)
        }
    }

    fun getStates(): List<VehicleModuleState> {
        ensureClientSnapshot()
        val result = ArrayList<VehicleModuleState>(states.size + LEGACY_MODULES.size)
        LEGACY_MODULES.keys.mapNotNullTo(result, access::state)
        states.keys.mapNotNullTo(result, access::state)
        return result.toList()
    }

    fun damage(id: ResourceLocation, damage: Double): VehicleModuleState? {
        val previous = access.state(id) ?: return null
        if (access.isClientSide || previous.destroyed) return previous
        val boundedDamage = if (damage.isFinite()) damage.coerceAtLeast(0.0) else Double.MAX_VALUE
        val nextHealth = (previous.health - boundedDamage).coerceAtLeast(0.0).toFloat()
        return access.setState(id, nextHealth.toDouble(), previous.destroyed || nextHealth <= 0f)
    }

    fun setHealth(id: ResourceLocation, health: Double): VehicleModuleState? {
        val previous = access.state(id) ?: return null
        val boundedHealth = boundedHealth(health,
            previous.maxHealth.takeIf { it.isFinite() && it >= 0f } ?: 1f)
        val destroyed = if (boundedHealth >= previous.maxHealth) false
            else previous.destroyed || boundedHealth <= 0f
        return access.setState(id, boundedHealth.toDouble(), destroyed)
    }

    fun setState(id: ResourceLocation, health: Double, destroyed: Boolean): VehicleModuleState? {
        val definition = access.definition(id) ?: return null
        val previous = access.state(id) ?: return null
        if (access.isClientSide) return previous
        val maxHealth = maximum(previous.maxHealth)
        val healthValue = boundedHealth(health, maxHealth)
        val effectiveDestroyed = healthValue <= 0f || destroyed && healthValue < maxHealth
        if (definition.adapter == VehicleModuleAdapter.GENERIC) {
            states[id] = StoredState(maxHealth, healthValue, effectiveDestroyed)
            syncGenericStates()
        } else {
            val rawMaximum = maximum(access.legacyMaximum(definition.adapter))
            val rawHealth = (healthValue / maximum(definition.maxHealth) * rawMaximum)
                .coerceIn(0f, rawMaximum)
            access.writeLegacyState(definition.adapter, rawHealth, effectiveDestroyed)
        }
        return access.state(id)
    }

    fun invalidateClientSnapshot() {
        snapshotCache = null
    }

    fun readAdditionalSaveData(compound: CompoundTag) {
        states.clear()
        if (compound.contains(MODULE_STATES_TAG)) {
            val savedStates = compound.getCompound(MODULE_STATES_TAG)
            for (rawId in savedStates.allKeys) {
                val id = ResourceLocation.tryParse(rawId) ?: continue
                val tag = savedStates.getCompound(rawId)
                val maxHealth = (tag.getFloat(MODULE_MAX_HEALTH_TAG)
                    .takeIf { it.isFinite() && it > 0f }
                    ?: access.definition(id)?.maxHealth ?: 1f).takeIf { it.isFinite() && it > 0f } ?: 1f
                val healthValue = boundedHealth(tag.getFloat(MODULE_HEALTH_TAG).toDouble(), maxHealth)
                states[id] = StoredState(maxHealth, healthValue,
                    tag.getBoolean(MODULE_DESTROYED_TAG) || healthValue <= 0f)
            }
        }
        syncGenericStates()
    }

    fun writeAdditionalSaveData(compound: CompoundTag) {
        if (states.isEmpty()) return
        val savedStates = CompoundTag()
        for ((id, state) in states) {
            if (state.health >= state.maxHealth && !state.destroyed) continue
            val tag = CompoundTag()
            tag.putFloat(MODULE_MAX_HEALTH_TAG, state.maxHealth)
            tag.putFloat(MODULE_HEALTH_TAG, state.health)
            tag.putBoolean(MODULE_DESTROYED_TAG, state.destroyed)
            savedStates.put(id.toString(), tag)
        }
        if (!savedStates.isEmpty) compound.put(MODULE_STATES_TAG, savedStates)
    }

    private fun legacyState(definition: VehicleModuleDefinition): VehicleModuleState {
        val raw = access.legacyState(definition.adapter)
        val rawMaximum = maximum(access.legacyMaximum(definition.adapter))
        val logicalMaximum = definition.maxHealth
        val logicalHealth = (boundedHealth(raw.health.toDouble(), rawMaximum) / rawMaximum * logicalMaximum)
            .coerceIn(0f, logicalMaximum)
        return VehicleModuleState(definition.id, logicalMaximum, logicalHealth,
            raw.destroyed || logicalHealth <= 0f)
    }

    private fun ensureClientSnapshot() {
        if (!access.isClientSide) return
        val payload = access.snapshot()
        if (snapshotCache == payload) return
        states.clear()
        if (payload.isNotBlank()) for (encoded in payload.split(';')) {
            val fields = encoded.split(',', limit = 4)
            if (fields.size != 4) continue
            val id = ResourceLocation.tryParse(fields[0]) ?: continue
            val maxHealth = fields[1].toFloatOrNull()?.takeIf { it.isFinite() && it > 0f } ?: continue
            val healthValue = fields[2].toFloatOrNull()?.takeIf { it.isFinite() }
                ?.coerceIn(0f, maxHealth) ?: continue
            states[id] = StoredState(maxHealth, healthValue, fields[3] == "1" || healthValue <= 0f)
        }
        snapshotCache = payload
    }

    private fun syncGenericStates() {
        if (access.isClientSide) return
        val payload = states.entries.sortedBy { it.key.toString() }.joinToString(";") { (id, state) ->
            "$id,${state.maxHealth},${state.health},${if (state.destroyed) 1 else 0}"
        }
        snapshotCache = payload
        access.publishSnapshot(payload)
    }

    private fun maximum(value: Float): Float = if (value.isFinite()) value.coerceAtLeast(1f) else 1f

    private fun boundedHealth(value: Double, maximum: Float): Float =
        if (value.isFinite()) value.coerceIn(0.0, maximum.toDouble()).toFloat() else 0f

    private data class StoredState(val maxHealth: Float, val health: Float, val destroyed: Boolean)

    companion object {
        const val MODULE_STATES_TAG = "SBWModuleStates"
        const val MODULE_MAX_HEALTH_TAG = "MaxHealth"
        const val MODULE_HEALTH_TAG = "Health"
        const val MODULE_DESTROYED_TAG = "Destroyed"

        private val LEGACY_MODULES = linkedMapOf(
            VehicleModuleIds.TURRET to VehicleModuleAdapter.LEGACY_TURRET,
            VehicleModuleIds.RUNNING_GEAR_LEFT to VehicleModuleAdapter.LEGACY_RUNNING_GEAR_LEFT,
            VehicleModuleIds.RUNNING_GEAR_RIGHT to VehicleModuleAdapter.LEGACY_RUNNING_GEAR_RIGHT,
            VehicleModuleIds.ENGINE_MAIN to VehicleModuleAdapter.LEGACY_ENGINE_MAIN,
            VehicleModuleIds.ENGINE_SUB to VehicleModuleAdapter.LEGACY_ENGINE_SUB,
        )
    }
}
