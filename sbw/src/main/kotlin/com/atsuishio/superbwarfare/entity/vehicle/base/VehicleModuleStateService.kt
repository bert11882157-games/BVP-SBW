package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.module.VehicleModuleAdapter
import com.atsuishio.superbwarfare.api.vehicle.module.VehicleModuleDefinition
import com.atsuishio.superbwarfare.api.vehicle.module.VehicleModuleIds
import com.atsuishio.superbwarfare.api.vehicle.module.VehicleModuleProviders
import com.atsuishio.superbwarfare.api.vehicle.module.VehicleModuleState
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation

/** Owns generic module adaptation, persistence and synchronization behind VehicleEntity's ABI. */
internal class VehicleModuleStateService(
    private val vehicle: VehicleEntity,
    private val owner: VehicleCombatStateOwner,
) {
    fun getDefinition(id: ResourceLocation): VehicleModuleDefinition? {
        VehicleModuleProviders.resolve(vehicle, id)?.let { return it }
        return when (id) {
            VehicleModuleIds.TURRET -> VehicleModuleDefinition(
                id,
                vehicle.getTurretMaxHealth().coerceAtLeast(1f),
                VehicleModuleAdapter.LEGACY_TURRET,
            )
            VehicleModuleIds.RUNNING_GEAR_LEFT -> VehicleModuleDefinition(
                id,
                vehicle.getWheelMaxHealth().coerceAtLeast(1f),
                VehicleModuleAdapter.LEGACY_RUNNING_GEAR_LEFT,
            )
            VehicleModuleIds.RUNNING_GEAR_RIGHT -> VehicleModuleDefinition(
                id,
                vehicle.getWheelMaxHealth().coerceAtLeast(1f),
                VehicleModuleAdapter.LEGACY_RUNNING_GEAR_RIGHT,
            )
            VehicleModuleIds.ENGINE_MAIN -> VehicleModuleDefinition(
                id,
                vehicle.getEngineMaxHealth().coerceAtLeast(1f),
                VehicleModuleAdapter.LEGACY_ENGINE_MAIN,
            )
            VehicleModuleIds.ENGINE_SUB -> VehicleModuleDefinition(
                id,
                vehicle.getEngineMaxHealth().coerceAtLeast(1f),
                VehicleModuleAdapter.LEGACY_ENGINE_SUB,
            )
            else -> owner.genericModuleStates[id]?.let {
                VehicleModuleDefinition(id, it.maxHealth.coerceAtLeast(1f))
            }
        }
    }

    fun getState(id: ResourceLocation): VehicleModuleState? {
        ensureClientSnapshot()
        val definition = vehicle.getVehicleModuleDefinition(id) ?: return null
        return when (definition.adapter) {
            VehicleModuleAdapter.GENERIC -> {
                val stored = owner.genericModuleStates[id]
                if (stored == null) {
                    VehicleModuleState(id, definition.maxHealth, definition.maxHealth, false)
                } else {
                    VehicleModuleState(
                        id,
                        stored.maxHealth,
                        stored.health.coerceIn(0f, stored.maxHealth),
                        stored.destroyed || stored.health <= 0f,
                    )
                }
            }
            else -> legacyState(definition)
        }
    }

    fun getStates(): List<VehicleModuleState> {
        ensureClientSnapshot()
        val states = ArrayList<VehicleModuleState>(owner.genericModuleStates.size + LEGACY_MODULE_IDS.size)
        LEGACY_MODULE_IDS.mapNotNullTo(states, vehicle::getVehicleModuleState)
        owner.genericModuleStates.keys.mapNotNullTo(states, vehicle::getVehicleModuleState)
        return states.toList()
    }

    fun damage(id: ResourceLocation, damage: Double): VehicleModuleState? {
        val previous = vehicle.getVehicleModuleState(id) ?: return null
        if (vehicle.level().isClientSide || previous.destroyed) return previous
        val boundedDamage = if (damage.isFinite()) damage.coerceAtLeast(0.0) else Double.MAX_VALUE
        val nextHealth = (previous.health - boundedDamage).coerceAtLeast(0.0).toFloat()
        return vehicle.setVehicleModuleState(id, nextHealth.toDouble(), previous.destroyed || nextHealth <= 0f)
    }

    fun setHealth(id: ResourceLocation, health: Double): VehicleModuleState? {
        val previous = vehicle.getVehicleModuleState(id) ?: return null
        val boundedHealth = if (health.isFinite()) {
            health.coerceIn(0.0, previous.maxHealth.toDouble()).toFloat()
        } else {
            0f
        }
        val destroyed = if (boundedHealth >= previous.maxHealth) {
            false
        } else {
            previous.destroyed || boundedHealth <= 0f
        }
        return vehicle.setVehicleModuleState(id, boundedHealth.toDouble(), destroyed)
    }

    fun setState(id: ResourceLocation, health: Double, destroyed: Boolean): VehicleModuleState? {
        val definition = vehicle.getVehicleModuleDefinition(id) ?: return null
        val previous = vehicle.getVehicleModuleState(id) ?: return null
        if (vehicle.level().isClientSide) return previous
        val maxHealth = previous.maxHealth.coerceAtLeast(1f)
        val boundedHealth = if (health.isFinite()) {
            health.coerceIn(0.0, maxHealth.toDouble()).toFloat()
        } else {
            0f
        }
        val effectiveDestroyed = boundedHealth <= 0f || destroyed && boundedHealth < maxHealth

        when (definition.adapter) {
            VehicleModuleAdapter.GENERIC -> {
                owner.genericModuleStates[id] = StoredVehicleModuleState(maxHealth, boundedHealth, effectiveDestroyed)
                syncGenericStates()
            }
            else -> writeLegacyState(definition, boundedHealth, effectiveDestroyed)
        }

        return vehicle.getVehicleModuleState(id)
    }

    fun invalidateClientSnapshot() {
        owner.genericModuleSnapshotCache = null
    }

    fun readAdditionalSaveData(compound: CompoundTag) {
        owner.genericModuleStates.clear()
        if (compound.contains(MODULE_STATES_TAG)) {
            val states = compound.getCompound(MODULE_STATES_TAG)
            for (rawId in states.allKeys) {
                val id = ResourceLocation.tryParse(rawId) ?: continue
                val stateTag = states.getCompound(rawId)
                val maxHealth = stateTag.getFloat(MODULE_MAX_HEALTH_TAG).takeIf { it.isFinite() && it > 0f }
                    ?: vehicle.getVehicleModuleDefinition(id)?.maxHealth
                    ?: 1f
                val health = stateTag.getFloat(MODULE_HEALTH_TAG).coerceIn(0f, maxHealth)
                val destroyed = stateTag.getBoolean(MODULE_DESTROYED_TAG) || health <= 0f
                owner.genericModuleStates[id] = StoredVehicleModuleState(maxHealth, health, destroyed)
            }
        }
        syncGenericStates()
    }

    fun writeAdditionalSaveData(compound: CompoundTag) {
        if (owner.genericModuleStates.isEmpty()) return
        val states = CompoundTag()
        for ((id, state) in owner.genericModuleStates) {
            if (state.health >= state.maxHealth && !state.destroyed) continue
            val stateTag = CompoundTag()
            stateTag.putFloat(MODULE_MAX_HEALTH_TAG, state.maxHealth)
            stateTag.putFloat(MODULE_HEALTH_TAG, state.health)
            stateTag.putBoolean(MODULE_DESTROYED_TAG, state.destroyed)
            states.put(id.toString(), stateTag)
        }
        if (!states.isEmpty) compound.put(MODULE_STATES_TAG, states)
    }

    private fun legacyState(definition: VehicleModuleDefinition): VehicleModuleState {
        val (rawHealth, rawMaxHealth, destroyed) = when (definition.adapter) {
            VehicleModuleAdapter.LEGACY_TURRET -> Triple(
                vehicle.turretHealth,
                vehicle.getTurretMaxHealth(),
                vehicle.turretDamaged,
            )
            VehicleModuleAdapter.LEGACY_RUNNING_GEAR_LEFT -> Triple(
                vehicle.leftWheelHealth,
                vehicle.getWheelMaxHealth(),
                vehicle.leftWheelDamaged,
            )
            VehicleModuleAdapter.LEGACY_RUNNING_GEAR_RIGHT -> Triple(
                vehicle.rightWheelHealth,
                vehicle.getWheelMaxHealth(),
                vehicle.rightWheelDamaged,
            )
            VehicleModuleAdapter.LEGACY_ENGINE_MAIN -> Triple(
                vehicle.mainEngineHealth,
                vehicle.getEngineMaxHealth(),
                vehicle.mainEngineDamaged,
            )
            VehicleModuleAdapter.LEGACY_ENGINE_SUB -> Triple(
                vehicle.subEngineHealth,
                vehicle.getEngineMaxHealth(),
                vehicle.subEngineDamaged,
            )
            VehicleModuleAdapter.GENERIC -> Triple(definition.maxHealth, definition.maxHealth, false)
        }
        val boundedRawMax = rawMaxHealth.coerceAtLeast(1f)
        val logicalHealth = (rawHealth.coerceIn(0f, boundedRawMax) / boundedRawMax * definition.maxHealth)
            .coerceIn(0f, definition.maxHealth)
        return VehicleModuleState(
            definition.id,
            definition.maxHealth,
            logicalHealth,
            destroyed || logicalHealth <= 0f,
        )
    }

    private fun writeLegacyState(
        definition: VehicleModuleDefinition,
        logicalHealth: Float,
        destroyed: Boolean,
    ) {
        val rawMaxHealth = when (definition.adapter) {
            VehicleModuleAdapter.LEGACY_TURRET -> vehicle.getTurretMaxHealth()
            VehicleModuleAdapter.LEGACY_RUNNING_GEAR_LEFT,
            VehicleModuleAdapter.LEGACY_RUNNING_GEAR_RIGHT -> vehicle.getWheelMaxHealth()
            VehicleModuleAdapter.LEGACY_ENGINE_MAIN,
            VehicleModuleAdapter.LEGACY_ENGINE_SUB -> vehicle.getEngineMaxHealth()
            VehicleModuleAdapter.GENERIC -> definition.maxHealth
        }.coerceAtLeast(1f)
        val rawHealth = (logicalHealth / definition.maxHealth.coerceAtLeast(1f) * rawMaxHealth)
            .coerceIn(0f, rawMaxHealth)
        when (definition.adapter) {
            VehicleModuleAdapter.LEGACY_TURRET -> {
                vehicle.turretHealth = rawHealth
                vehicle.turretDamaged = destroyed
            }
            VehicleModuleAdapter.LEGACY_RUNNING_GEAR_LEFT -> {
                vehicle.leftWheelHealth = rawHealth
                vehicle.leftWheelDamaged = destroyed
            }
            VehicleModuleAdapter.LEGACY_RUNNING_GEAR_RIGHT -> {
                vehicle.rightWheelHealth = rawHealth
                vehicle.rightWheelDamaged = destroyed
            }
            VehicleModuleAdapter.LEGACY_ENGINE_MAIN -> {
                vehicle.mainEngineHealth = rawHealth
                vehicle.mainEngineDamaged = destroyed
            }
            VehicleModuleAdapter.LEGACY_ENGINE_SUB -> {
                vehicle.subEngineHealth = rawHealth
                vehicle.subEngineDamaged = destroyed
            }
            VehicleModuleAdapter.GENERIC -> Unit
        }
    }

    private fun ensureClientSnapshot() {
        if (!vehicle.level().isClientSide) return
        val payload = vehicle.entityData.get(VehicleEntity.MODULE_STATE_SNAPSHOT)
        if (owner.genericModuleSnapshotCache == payload) return
        owner.genericModuleStates.clear()
        decodeSnapshot(payload, owner.genericModuleStates)
        owner.genericModuleSnapshotCache = payload
    }

    private fun syncGenericStates() {
        if (vehicle.level().isClientSide) return
        val payload = encodeSnapshot(owner.genericModuleStates)
        owner.genericModuleSnapshotCache = payload
        vehicle.publishModuleStateSnapshot(payload)
    }

    private fun encodeSnapshot(states: Map<ResourceLocation, StoredVehicleModuleState>): String =
        states.entries
            .sortedBy { it.key.toString() }
            .joinToString(";") { (id, state) ->
                "$id,${state.maxHealth},${state.health},${if (state.destroyed) 1 else 0}"
            }

    private fun decodeSnapshot(
        payload: String,
        target: MutableMap<ResourceLocation, StoredVehicleModuleState>,
    ) {
        if (payload.isBlank()) return
        for (encoded in payload.split(';')) {
            val fields = encoded.split(',', limit = 4)
            if (fields.size != 4) continue
            val id = ResourceLocation.tryParse(fields[0]) ?: continue
            val maxHealth = fields[1].toFloatOrNull()?.takeIf { it.isFinite() && it > 0f } ?: continue
            val health = fields[2].toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(0f, maxHealth) ?: continue
            val destroyed = fields[3] == "1" || health <= 0f
            target[id] = StoredVehicleModuleState(maxHealth, health, destroyed)
        }
    }

    companion object {
        const val MODULE_STATES_TAG = "SBWModuleStates"
        const val MODULE_MAX_HEALTH_TAG = "MaxHealth"
        const val MODULE_HEALTH_TAG = "Health"
        const val MODULE_DESTROYED_TAG = "Destroyed"

        private val LEGACY_MODULE_IDS = listOf(
            VehicleModuleIds.TURRET,
            VehicleModuleIds.RUNNING_GEAR_LEFT,
            VehicleModuleIds.RUNNING_GEAR_RIGHT,
            VehicleModuleIds.ENGINE_MAIN,
            VehicleModuleIds.ENGINE_SUB,
        )
    }
}
