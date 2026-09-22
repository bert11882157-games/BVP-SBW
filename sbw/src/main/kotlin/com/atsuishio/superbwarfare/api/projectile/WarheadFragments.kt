package com.atsuishio.superbwarfare.api.projectile

import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleDamageRequest
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.*

/** One bounded damaging ray fan per authored warhead, independent of cosmetic impact fragments. */
data class WarheadFragmentPolicy(val count: Int, val range: Double, val damage: Float, val maxHits: Int) {
    init {
        require(count in 1..256 && range.isFinite() && range in 1.0..32.0)
        require(damage.isFinite() && damage in 0.1f..1000f && maxHits in 1..16)
    }
    fun direction(index: Int, phase: Double): Vec3 {
        require(index in 0 until count && phase.isFinite())
        val y = 1.0 - 2.0 * (index + 0.5) / count
        val r = sqrt(max(0.0, 1.0 - y*y)); val angle = index * Math.PI * (3.0 - sqrt(5.0)) + phase
        return Vec3(cos(angle)*r, y, sin(angle)*r)
    }
    fun damageFor(hits: Int): Float = damage * hits.coerceIn(0, maxHits)
    companion object {
        val ID = ResourceLocation("superbwarfare", "warhead_fragments_v1")
        fun from(profile: ResolvedProjectileProfile?): WarheadFragmentPolicy? = runCatching {
            val j = profile?.extension(ID)?.asJsonObject ?: return null
            val count = j["Count"].asDouble; val hits = j["MaxHitsPerTarget"].asDouble
            require(count == floor(count) && hits == floor(hits))
            WarheadFragmentPolicy(count.toInt(), j["Range"].asDouble, j["Damage"].asFloat, hits.toInt())
        }.getOrNull()
    }
}

object WarheadFragments {
    /** Uses post-event candidates: explosion protections can remove entities before any fragment commit. */
    fun apply(level: ServerLevel, source: Entity?, damageSource: DamageSource, origin: Vec3,
        candidates: List<Entity>, policy: WarheadFragmentPolicy, damageOwnedByBlast: (Entity) -> Boolean = { false }): Boolean {
        if (source == null || source.persistentData.getBoolean("SbwWarheadFragmentsCommitted")) return false
        source.persistentData.putBoolean("SbwWarheadFragmentsCommitted", true)
        val targets = candidates.asSequence().filter {
            it !== source && it.isAlive && !it.isRemoved && !it.ignoreExplosion() &&
                (it is LivingEntity || it is VehicleEntity) && it.boundingBox.distanceToSqr(origin) <= policy.range*policy.range
        }.sortedBy { it.boundingBox.distanceToSqr(origin) }.take(128).toList()
        val hits = linkedMapOf<Entity, Int>()
        val phase = (source.uuid.leastSignificantBits and 0xffff).toDouble() / 65536.0 * Math.PI * 2
        for (index in 0 until policy.count) {
            val direction = policy.direction(index, phase)
            val start = origin.add(direction.scale(0.03)); val end = origin.add(direction.scale(policy.range))
            // Refuse a ray through unloaded terrain; no fragment creates a chunk ticket.
            var loaded = true
            var distance = 0.0
            while (distance <= policy.range) {
                if (!level.hasChunkAt(net.minecraft.core.BlockPos.containing(origin.add(direction.scale(distance))))) { loaded = false; break }
                distance += 4.0
            }
            if (!loaded || !level.hasChunkAt(net.minecraft.core.BlockPos.containing(end))) continue
            val wall = level.clip(ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, source))
            var closest = if (wall.type == HitResult.Type.MISS) start.distanceToSqr(end) else start.distanceToSqr(wall.location)
            var struck: Entity? = null
            for (target in targets) {
                val point = if (target.boundingBox.contains(start)) start else target.boundingBox.clip(start, end).orElse(null) ?: continue
                val distanceSquared = start.distanceToSqr(point)
                if (distanceSquared < closest) { closest = distanceSquared; struck = target }
            }
            struck?.let { hits[it] = (hits[it] ?: 0) + 1 }
        }
        var accepted = false
        for ((target, count) in hits) {
            // Covered vehicles still stop rays; the blast alone owns their requested health result.
            if (damageOwnedByBlast(target)) continue
            val amount = policy.damageFor(count)
            // The finite fragment budget is separate from the radial blast's lethal inner zone.
            val applied = if (target is VehicleEntity)
                target.applyResolvedDamage(ResolvedVehicleDamageRequest(damageSource, amount)).accepted
                else target.hurt(damageSource, amount)
            accepted = accepted || applied
        }
        return accepted
    }
}
