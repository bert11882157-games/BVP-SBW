package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.aircraft.AircraftProjectileDamage
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactDamagePolicy
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleDamageRequest
import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleDamageResult
import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleModulePolicy
import com.atsuishio.superbwarfare.api.vehicle.destruction.VehicleDestructionContext
import com.atsuishio.superbwarfare.entity.mixin.OBBHitter
import com.atsuishio.superbwarfare.init.ModDamageTypes
import com.atsuishio.superbwarfare.init.ModTags
import com.atsuishio.superbwarfare.tools.DamageTypeTool
import com.atsuishio.superbwarfare.tools.OBB.Part.MAIN_ENGINE
import com.atsuishio.superbwarfare.tools.OBB.Part.SUB_ENGINE
import com.atsuishio.superbwarfare.tools.OBB.Part.TURRET
import com.atsuishio.superbwarfare.tools.OBB.Part.WHEEL_LEFT
import com.atsuishio.superbwarfare.tools.OBB.Part.WHEEL_RIGHT
import com.atsuishio.superbwarfare.tools.SeekTool
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.tags.DamageTypeTags
import com.atsuishio.superbwarfare.tools.OBB

/** Adapts source policy, native module damage, and world effects to the damage transaction. */
internal class VehicleDamageLifecycleService(
    private val vehicle: VehicleEntity,
) {
    private val transaction = VehicleDamageTransaction(object : VehicleDamageAccess<DamageSource, VehicleDestructionContext> {
        override val isServerAuthority: Boolean get() = vehicle.level() is ServerLevel
        override val isWreck: Boolean get() = vehicle.isWreck
        override val isAlive: Boolean get() = vehicle.isAlive
        override val health: Float get() = vehicle.health
        override val maxHealth: Float get() = vehicle.getMaxHealth()

        override fun acceptsSource(source: DamageSource): Boolean = vehicle.acceptsDamageSource(source)

        override fun reportDebug(source: DamageSource, amount: Float) =
            vehicle.reportDamageDebug(source, amount)

        override fun computeAfterModifiers(source: DamageSource, amount: Float): Float =
            vehicle.computeVehicleDamageAfterModifiers(source, amount)

        override fun commit(
            source: DamageSource,
            amount: Float,
            modulePolicy: ResolvedVehicleModulePolicy,
            feedback: Boolean,
        ) = this@VehicleDamageLifecycleService.commit(source, amount, modulePolicy, feedback)

        override fun invokeVanillaHurt(source: DamageSource, amount: Float): Boolean =
            vehicle.invokeVanillaHurt(source, amount)

        override fun defaultDestructionContext(): VehicleDestructionContext =
            vehicle.defaultDestructionContext()

        override fun destroy(context: VehicleDestructionContext) = vehicle.destroy(context)
    })

    fun acceptsSource(source: DamageSource): Boolean {
        if (source.`is`(ModTags.DamageTypes.VEHICLE_IMMUNE)) return false

        if (DamageTypeTool.isGunDamage(source) && source.entity != null && source.entity!!.vehicle === vehicle) {
            return false
        }

        val lastDriver = vehicle.lastDriver
        if (source.entity != null && lastDriver != null && SeekTool.IS_FRIENDLY.test(lastDriver, source.entity)
            && lastDriver.team != null && source.entity!!.team != null && source.entity!!.team === lastDriver.team
            && !source.entity!!.team!!.isAllowFriendlyFire
            && (source.entity === lastDriver && !source.`is`(ModDamageTypes.VEHICLE_STRIKE))
        ) {
            return false
        }

        return true
    }

    fun hurt(source: DamageSource, amount: Float): Boolean =
        com.atsuishio.superbwarfare.api.vehicle.damage.LightPlatformProjectileDamage.handleNativeDamage(vehicle, source)
            ?: AircraftProjectileDamage.handleNativeDamage(vehicle, source) ?: transaction.hurt(source, amount)

    fun applyResolved(request: ResolvedVehicleDamageRequest): ResolvedVehicleDamageResult =
        transaction.applyResolved(
            request.source,
            request.amount,
            request.lethal,
            request.modulePolicy,
            request.feedback,
            request.destructionContext,
        )

    fun computeAfterModifiers(source: DamageSource, amount: Float): Float =
        if (source.`is`(ModTags.DamageTypes.BYPASSES_VEHICLE)) amount
        else vehicle.getDamageModifier().compute(source, amount)

    private fun commit(
        source: DamageSource,
        amount: Float,
        modulePolicy: ResolvedVehicleModulePolicy,
        feedback: Boolean,
    ) {
        vehicle.crash = source.`is`(ModDamageTypes.VEHICLE_STRIKE)

        if (source.entity != null) {
            vehicle.lastAttackerUUID = source.entity!!.stringUUID
        }

        val projectile = source.directEntity
        val suppressNativeModuleDamage = projectile is ProjectileImpactDamagePolicy &&
            projectile.suppressesNativeVehicleModuleDamage()
        var appliedPart = OBB.Part.EMPTY
        val leftBefore = vehicle.leftWheelHealth
        val rightBefore = vehicle.rightWheelHealth
        if (modulePolicy == ResolvedVehicleModulePolicy.APPLY_NATIVE &&
            !suppressNativeModuleDamage && projectile is Projectile
        ) {
            val accessor = OBBHitter.getInstance(projectile)
            val part = accessor.`sbw$getProjectileContact`()?.partFor(
                vehicle.uuid, vehicle.level().gameTime, source.`is`(DamageTypeTags.IS_EXPLOSION))
            appliedPart = part ?: OBB.Part.EMPTY

            if (part != null) {
                when (part) {
                    TURRET -> vehicle.turretHealth -= amount
                    WHEEL_LEFT -> vehicle.leftWheelHealth -= amount
                    WHEEL_RIGHT -> vehicle.rightWheelHealth -= amount
                    MAIN_ENGINE -> vehicle.mainEngineHealth -= amount
                    SUB_ENGINE -> vehicle.subEngineHealth -= amount
                    else -> Unit
                }
            }
        }

        vehicle.lastDamageSource = source
        vehicle.lastDamageStamp = vehicle.level().gameTime
        val healthBefore = vehicle.health
        vehicle.onHurt(amount, source.entity, feedback)
        com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleSimulationPolicy.wakeAfterDamage(vehicle, amount)
        if (EliteDiagnostics.isEnabled(vehicle.level())) {
            EliteDiagnostics.record(vehicle, "damage", "hull_commit",
                "source", source.msgId, "owner", source.entity?.uuid,
                "direct_entity", source.directEntity?.uuid, "requested_damage", amount,
                "health_before", healthBefore, "health_after", vehicle.health,
                "contact_part", appliedPart, "left_track_before", leftBefore,
                "left_track_after", vehicle.leftWheelHealth, "right_track_before", rightBefore,
                "right_track_after", vehicle.rightWheelHealth,
                "module_policy", modulePolicy, "native_modules_suppressed", suppressNativeModuleDamage)
        }
    }
}
