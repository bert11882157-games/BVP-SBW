package com.atsuishio.superbwarfare.tools.blast

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.config.server.BlastConfig
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.tools.CustomExplosion
import com.google.gson.JsonObject
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.entity.EntityJoinLevelEvent
import net.minecraftforge.event.entity.EntityLeaveLevelEvent
import net.minecraftforge.event.level.ExplosionEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber

/**
 * TNT blasts for aircraft missiles simulated by the optional external FFA mod (`dev.ballistics`). FFA's impact
 * and damage code is not part of this repository and never reaches [CustomExplosion] or
 * `MissilePresentation.impact`, so SBW attaches the charge from outside:
 *
 * 1. [launch] wraps the synchronous FFA launch call; the first projectile-like entity that joins the level
 *    during it is stamped with the store's charge (`Flight.TntEquivalentKg`, else the store's top-level
 *    `TntEquivalentKg`) and marked as an external munition.
 * 2. If FFA detonates it through a vanilla/Forge [net.minecraft.world.level.Explosion], that explosion's entity
 *    and block effects are cleared and the TNT blast runs at its centre instead.
 * 3. Otherwise, when the marked entity is killed or discarded, the TNT blast runs where it was removed
 *    (FFA's own direct damage, if any, is left as it is).
 *
 * Disabled by `tnt_blast.external_munition_blasts`. If FFA spawns its missile asynchronously nothing is stamped
 * and FFA's own blast stays unchanged.
 */
@EventBusSubscriber(bus = EventBusSubscriber.Bus.FORGE)
object ExternalMunitionBlasts {
    private const val EXTERNAL_KEY = "SbwExternalMunition"
    private const val DETONATED_KEY = "SbwExternalMunitionDetonated"
    private val capture = ThreadLocal<MutableList<Entity>?>()

    /** Store charge for FFA-launched stores: `Flight.TntEquivalentKg`, else the store's `TntEquivalentKg`. */
    @JvmStatic
    fun storeCharge(store: JsonObject): Double {
        val flight = store.getAsJsonObject("Flight")?.get("TntEquivalentKg")
        val value = flight ?: store.get("TntEquivalentKg")
        return runCatching { value?.asDouble ?: 0.0 }.getOrDefault(0.0).let(TntEquivalents::sanitize)
    }

    /** Runs [action] (a synchronous external launch) and stamps the munition it spawned with [kg]. */
    @JvmStatic
    fun <T> launch(level: Level, kg: Double, action: () -> T): T {
        if (level.isClientSide || !TntBlast.active(kg) || !BlastConfig.externalMunitionBlasts()) return action()
        val previous = capture.get()
        val spawned = ArrayList<Entity>(2)
        capture.set(spawned)
        try {
            return action()
        } finally {
            capture.set(previous)
            val munition = spawned.firstOrNull { it is Projectile } ?: spawned.firstOrNull()
            if (munition != null && munition.isAlive) {
                TntEquivalents.set(munition, kg)
                munition.persistentData.putBoolean(EXTERNAL_KEY, true)
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun onJoin(event: EntityJoinLevelEvent) {
        val spawned = capture.get() ?: return
        if (event.level.isClientSide || event.loadedFromDisk() || event.isCanceled) return
        val entity = event.entity
        if (entity is Player || entity is VehicleEntity || entity is ItemEntity || entity is ExperienceOrb) return
        spawned.add(entity)
    }

    @SubscribeEvent
    fun onDetonate(event: ExplosionEvent.Detonate) {
        val explosion = event.explosion
        if (explosion is CustomExplosion || event.level.isClientSide) return
        val source = explosion.directSourceEntity ?: explosion.exploder ?: return
        if (!claim(source)) return
        val kg = TntEquivalents.resolve(source)
        if (!TntBlast.active(kg)) return
        // The TNT model owns entity damage and block destruction for this detonation.
        event.affectedEntities.clear()
        event.affectedBlocks.clear()
        schedule(source, explosion.position, kg)
    }

    @SubscribeEvent
    fun onLeave(event: EntityLeaveLevelEvent) {
        val entity = event.entity
        if (event.level.isClientSide) return
        val reason = entity.removalReason ?: return
        if (reason != Entity.RemovalReason.KILLED && reason != Entity.RemovalReason.DISCARDED) return
        if (!claim(entity)) return
        val kg = TntEquivalents.resolve(entity)
        if (TntBlast.active(kg)) schedule(entity, entity.position(), kg)
    }

    /** Whether [entity] is a munition launched through the external FFA integration. */
    @JvmStatic
    fun isExternal(entity: Entity?): Boolean = entity?.persistentData?.getBoolean(EXTERNAL_KEY) == true

    /** True once per marked external munition. */
    private fun claim(entity: Entity): Boolean {
        val data = entity.persistentData
        if (!data.getBoolean(EXTERNAL_KEY) || data.getBoolean(DETONATED_KEY)) return false
        data.putBoolean(DETONATED_KEY, true)
        return true
    }

    /** Runs at the end of the server tick, outside the removal callback or the foreign explosion. */
    private fun schedule(source: Entity, at: Vec3, kg: Double) {
        if (source.level() !is ServerLevel) return
        val attacker = (source as? Projectile)?.owner
        Mod.queueServerWork(0) {
            CustomExplosion.Builder(source)
                .attacker(attacker ?: source)
                .position(at)
                .damage(0f)
                .radius(0f)
                .tntEquivalent(kg)
                // FFA presents its own impact; only the gameplay blast (and a >= 1 t shockwave) is added.
                .emitFx(false)
                .explode()
        }
    }
}
