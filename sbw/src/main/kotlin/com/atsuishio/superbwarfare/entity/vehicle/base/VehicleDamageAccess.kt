package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleModulePolicy

/**
 * Damage-only access to live state and compatibility callbacks. Reads are deliberately live:
 * admission, diagnostics, damage callbacks, and destruction may change the values observed
 * by later phases. Source and destruction context are opaque to the transaction.
 */
internal interface VehicleDamageAccess<Source : Any, Destruction : Any> {
    val isServerAuthority: Boolean
    val isWreck: Boolean
    val isAlive: Boolean
    val health: Float
    val maxHealth: Float

    fun acceptsSource(source: Source): Boolean
    fun reportDebug(source: Source, amount: Float)
    fun computeAfterModifiers(source: Source, amount: Float): Float

    /** Commits native module policy, attribution, and hull damage in that order. */
    fun commit(source: Source, amount: Float, modulePolicy: ResolvedVehicleModulePolicy, feedback: Boolean)

    fun invokeVanillaHurt(source: Source, amount: Float): Boolean
    fun defaultDestructionContext(): Destruction
    fun destroy(context: Destruction)
}
