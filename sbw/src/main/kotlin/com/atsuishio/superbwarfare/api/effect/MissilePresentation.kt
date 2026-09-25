package com.atsuishio.superbwarfare.api.effect

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.entity.projectile.*
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.entity.EntityJoinLevelEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber

/** FFA supplies a shared near/far presentation; projectile simulation and damage stay with SBW. */
@EventBusSubscriber(modid = Mod.MODID)
object MissilePresentation {
    private val physicalWireId = net.minecraft.resources.ResourceLocation("berts_vehicle_pack", "physical_wire_v1")
    /** Projectile implementation class and exhaust shape do not imply a physical guidance wire. */
    @JvmStatic fun hasPhysicalWire(entity: Entity): Boolean = physicalWireCount(entity) > 0
    @JvmStatic fun physicalWireCount(entity: Entity): Int = runCatching {
        val profile = com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles.resolve(entity)
            ?: return nativePhysicalWireCount(entity)
        val data = profile.extension(physicalWireId)?.asJsonObject ?: return 0
        val schema = data.get("Schema")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber } ?: return 0
        val enabled = data.get("Enabled")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean } ?: return 0
        if (schema.asDouble != 1.0 || data.keySet().any { it !in setOf("Schema", "Enabled", "WireCount") }) return 0
        val count = data.get("WireCount")
        if (count == null) return if (enabled.asBoolean) 1 else 0 // Existing v1 resource compatibility.
        if (!count.isJsonPrimitive || !count.asJsonPrimitive.isNumber) return 0
        val value = count.asDouble
        if (!enabled.asBoolean || value !in setOf(1.0, 2.0)) 0 else value.toInt()
    }.getOrDefault(0)
    private fun nativePhysicalWireCount(entity: Entity): Int {
        val context = (entity as? WireGuideMissileEntity)?.launcherGuidanceContext() ?: return 0
        val level = entity.level() as? net.minecraft.server.level.ServerLevel ?: return 0
        val launcher = level.getEntity(context.launcherVehicleUUID) ?: return 0
        val type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(launcher.type).toString()
        return nativePhysicalWireCount(type, context.weaponName)
    }
    internal fun nativePhysicalWire(type: String, weapon: String): Boolean = nativePhysicalWireCount(type, weapon) > 0
    internal fun nativePhysicalWireCount(type: String, weapon: String): Int = when {
        weapon != "Missile" -> 0
        type in setOf("superbwarfare:tow", "superbwarfare:sodayo_pick_up_tow",
            "superbwarfare:bradley", "superbwarfare:lav_25") -> 2
        type == "superbwarfare:bmp_2" -> 1
        else -> 0
    }
    data class NozzleOffset(val centerHeight: Double, val rear: Double)
    private var nozzleResolver: java.util.function.Function<Entity, NozzleOffset?>? = null
    /** Optional model attachment; offsets are presentation only and never move the entity. */
    @JvmStatic fun setNozzleResolver(resolver: java.util.function.Function<Entity, NozzleOffset?>) {
        nozzleResolver = resolver
    }
    @JvmStatic fun nozzleOffset(entity: Entity): NozzleOffset {
        val resolved = nozzleResolver?.apply(entity)
        return resolved?.takeIf { it.centerHeight.isFinite() && it.rear.isFinite() &&
            kotlin.math.abs(it.centerHeight) <= 32 && it.rear in 0.0..32.0 }
            ?: NozzleOffset(entity.bbHeight * .5, 0.0)
    }
    @JvmStatic fun nozzlePosition(entity: Entity, partial: Float): Vec3 {
        val offset = nozzleOffset(entity)
        return entity.getPosition(partial).add(0.0, offset.centerHeight, 0.0)
            .subtract(entity.getViewVector(partial).normalize().scale(offset.rear))
    }
    private data class Emission(val tick: Long, val point: Vec3)
    private val emitted = java.util.WeakHashMap<Entity, Emission>()
    private val debug by lazy { runCatching {
        Class.forName("dev.ballistics.InterceptorDebug").getMethod("munition", Entity::class.java, String::class.java)
    }.getOrNull() }
    private val clientVisual by lazy { runCatching {
        Class.forName("dev.ballistics.client.ClientTracks").getMethod("hasVisual", Int::class.javaPrimitiveType)
    }.getOrNull() }
    @JvmStatic fun hasSharedVisual(entity: Entity): Boolean = entity.level().isClientSide && isMissile(entity) &&
        runCatching { clientVisual?.invoke(null, entity.id) == true }.getOrDefault(false)
    private val bridge by lazy { runCatching {
        val type = Class.forName("dev.ballistics.MissileVisualHooks")
        type.getMethod("register", Entity::class.java, Double::class.javaPrimitiveType) to
            type.getMethod("impactAt", Level::class.java, Vec3::class.java, Float::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType)
    }.getOrNull() }
    private val attachedBridge by lazy { runCatching {
        Class.forName("dev.ballistics.MissileVisualHooks").getMethod("registerAttached", Entity::class.java,
            Double::class.javaPrimitiveType, String::class.java, Double::class.javaPrimitiveType,
            Double::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    }.getOrNull() }
    @JvmStatic fun isMissile(entity: Entity?): Boolean = entity is MissileProjectile ||
        entity is SmallRocketEntity || entity is MediumRocketEntity ||
        entity is RpgRocketStandardEntity || entity is RpgRocketTBGEntity
    @SubscribeEvent fun joined(event: EntityJoinLevelEvent) {
        if (event.level.isClientSide || !isMissile(event.entity)) return
        runCatching {
            val entity = event.entity
            val vehicle = (entity as? net.minecraft.world.entity.projectile.Projectile)?.owner?.let {
                (it as? com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity)
                    ?: (it.vehicle as? com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity)
            }
            val airborne = vehicle?.vehicleType in setOf(
                com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType.AIRPLANE,
                com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType.HELICOPTER)
            val kind = if (entity is WireGuideMissileEntity) "external_atgm" else
                if (entity.bbWidth >= .3f) "external_large" else "external_small"
            val offset = nozzleOffset(entity)
            if (attachedBridge != null) attachedBridge!!.invoke(null, entity, entity.bbWidth.toDouble(),
                kind + if (airborne) "_air" else "_ground", offset.centerHeight, offset.rear,
                entity is MissileProjectile, physicalWireCount(entity))
            else bridge?.first?.invoke(null, entity, entity.bbWidth.toDouble())
        }
            .onFailure { Mod.LOGGER.debug("Missile visual registration unavailable", it) }
        runCatching { debug?.invoke(null, event.entity, "missile spawned") }
    }
    @JvmStatic fun impact(level: Level, position: Vec3, source: Entity?, radius: Float): Boolean {
        if (!isMissile(source)) return false
        if (level.isClientSide || source == null) return false
        val previous = emitted[source]
        if (previous?.tick == level.gameTime && previous.point.distanceToSqr(position) < 0.0625) return true
        val profile = com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles.resolve(source)
        val legacyRadius = com.atsuishio.superbwarfare.api.projectile.HeavyWarheadBlastPolicy.from(profile)
            ?.outerRadius?.toFloat()
            ?: (source as? FastThrowableProjectile)?.explosionRadiusValue?.takeIf { it > 0 } ?: radius
        // A TNT-equivalent warhead is presented out to its damaging reach, never smaller than its authored radius.
        val authoredRadius = maxOf(legacyRadius,
            com.atsuishio.superbwarfare.tools.blast.TntBlast.presentationRadius(source).toFloat())
        val accepted = effect(level, position, authoredRadius.coerceIn(.05f, 24f), false)
        if (accepted) {
            emitted[source] = Emission(level.gameTime, position)
            runCatching { debug?.invoke(null, source, "impact radius=$authoredRadius at $position") }
        }
        return accepted
    }
    @JvmStatic fun effect(level: Level, position: Vec3, radius: Float, mushroom: Boolean): Boolean {
        if (level.isClientSide) return false
        return runCatching { bridge?.second?.invoke(null, level, position, radius, mushroom) == true }
            .getOrElse { Mod.LOGGER.debug("Missile impact presentation unavailable", it); false }
    }
}
