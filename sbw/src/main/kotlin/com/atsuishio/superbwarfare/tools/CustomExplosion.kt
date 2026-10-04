package com.atsuishio.superbwarfare.tools

import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles
import com.atsuishio.superbwarfare.api.projectile.GroundVehicleBlastPolicy
import com.atsuishio.superbwarfare.api.projectile.HeavyWarheadBlastPolicy
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import com.atsuishio.superbwarfare.config.server.ExplosionConfig
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModDamageTypes
import com.atsuishio.superbwarfare.init.ModSounds
import com.atsuishio.superbwarfare.network.message.receive.ClientIndicatorMessage
import com.atsuishio.superbwarfare.network.message.receive.ShakeClientMessage.Companion.sendToNearbyPlayers
import com.atsuishio.superbwarfare.tools.DamageHandler.doDamage
import com.atsuishio.superbwarfare.network.message.receive.FireballMessage
import com.atsuishio.superbwarfare.network.message.receive.ShockwaveMessage
import com.atsuishio.superbwarfare.tools.blast.BlastCylinder
import com.atsuishio.superbwarfare.tools.blast.BlastExposure
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.tools.blast.BlastModel
import com.atsuishio.superbwarfare.tools.blast.BlastPush
import com.atsuishio.superbwarfare.tools.blast.TntBlast
import com.atsuishio.superbwarfare.tools.blast.TntEquivalents
import com.mojang.datafixers.util.Pair
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.item.PrimedTnt
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.ProtectionEnchantment
import net.minecraft.world.level.Explosion
import net.minecraft.world.level.ExplosionDamageCalculator
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.ForgeEventFactory
import java.util.function.Supplier
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

open class CustomExplosion(
    private val level: Level,
    private val pSource: Entity?,
    source: DamageSource?,
    pDamageCalculator: ExplosionDamageCalculator?,
    private val damage: Float,
    private val x: Double,
    private val y: Double,
    private val z: Double,
    private val radius: Float,
    pBlockInteraction: BlockInteraction
) : Explosion(
    level,
    pSource, source, null,
    x,
    y,
    z,
    radius, false, pBlockInteraction
) {
    private val damageSource: DamageSource
    private val damageCalculator: ExplosionDamageCalculator
    private var fireTime = 0
    private var damageMultiplier = 1f
    private var activeGroundVehicleBlast: GroundVehicleBlastPolicy? = null
    private var activeHeavyWarheadBlast: HeavyWarheadBlastPolicy? = null
    /** Non-null selects the Hopkinson-Cranz TNT-equivalent model for this explosion. */
    private var tntPlan: TntBlast.Plan? = null

    /** The Forge vehicle listener leaves these targets to this explosion's resolved damage pass. */
    fun ownsGroundVehicleBlast(entity: VehicleEntity): Boolean =
        tntPlan != null || activeHeavyWarheadBlast != null || activeGroundVehicleBlast?.appliesTo(entity.vehicleType) == true

    /** True when this explosion uses the TNT-equivalent model; the legacy vehicle listener then stays out. */
    fun usesTntModel(): Boolean = tntPlan != null

    /** TNT equivalent (kg) of this explosion, 0 for a legacy blast. */
    fun tntEquivalentKg(): Double = tntPlan?.kg ?: 0.0

    fun setTntPlan(plan: TntBlast.Plan?): CustomExplosion {
        this.tntPlan = plan
        return this
    }

    /** The legacy Forge vehicle listener must not turn a visual-only blast radius into damage. */
    fun hasLegacyVehicleBlastDamage(): Boolean = damage.isFinite() && damage > 0f

    init {
        this.damageSource = source ?: level.damageSources().explosion(this)
        this.damageCalculator = pDamageCalculator ?: ExplosionDamageCalculator()
    }

    constructor(
        pLevel: Level,
        pSource: Entity?,
        damage: Float,
        pToBlowX: Double,
        pToBlowY: Double,
        pToBlowZ: Double,
        pRadius: Float,
        pBlockInteraction: BlockInteraction
    ) : this(pLevel, pSource, null, null, damage, pToBlowX, pToBlowY, pToBlowZ, pRadius, pBlockInteraction)

    constructor(
        pLevel: Level,
        pSource: Entity?,
        source: DamageSource?,
        damage: Float,
        pToBlowX: Double,
        pToBlowY: Double,
        pToBlowZ: Double,
        pRadius: Float,
        pBlockInteraction: BlockInteraction
    ) : this(pLevel, pSource, source, null, damage, pToBlowX, pToBlowY, pToBlowZ, pRadius, pBlockInteraction) {
        sendToNearbyPlayers(
            level,
            pToBlowX,
            pToBlowY,
            pToBlowZ,
            (4 * radius).toDouble(),
            20 + 0.2 * radius,
            50 + 0.5 * radius
        )
    }

    constructor(
        pLevel: Level,
        pSource: Entity?,
        source: DamageSource?,
        damage: Float,
        pToBlowX: Double,
        pToBlowY: Double,
        pToBlowZ: Double,
        pRadius: Float
    ) : this(pLevel, pSource, source, null, damage, pToBlowX, pToBlowY, pToBlowZ, pRadius, BlockInteraction.KEEP) {
        sendToNearbyPlayers(level, pToBlowX, pToBlowY, pToBlowZ, radius.toDouble(), 5 + 0.2 * radius, 2 + 0.02 * radius)
    }

    fun setFireTime(fireTime: Int): CustomExplosion {
        this.fireTime = fireTime
        return this
    }

    fun setDamageMultiplier(damageMultiplier: Float): CustomExplosion {
        this.damageMultiplier = damageMultiplier
        return this
    }

    override fun explode() {
        tntPlan?.let {
            explodeTnt(it)
            return
        }
        if (ExplosionConfig.explosionsBreakBlocks()) {
            this.level.gameEvent(this.pSource, GameEvent.EXPLODE, Vec3(this.x, this.y, this.z))
            val set = hashSetOf<BlockPos>()

            val center = Vec3(this.x, this.y, this.z)
            val random = level.random

            val aabb = AABB(
                x - 0.5 * radius,
                y - 0.5 * radius,
                z - 0.5 * radius,
                x + 0.5 * radius,
                y + 0.5 * radius,
                z + 0.5 * radius
            )

            val minPos = BlockPos(
                floor(aabb.minX).toInt(),
                floor(aabb.minY).toInt(),
                floor(aabb.minZ).toInt()
            )

            val maxPos = BlockPos(
                floor(aabb.maxX).toInt(),
                floor(aabb.maxY).toInt(),
                floor(aabb.maxZ).toInt()
            )

            BlockPos.betweenClosedStream(minPos, maxPos).forEach { blockpos ->
                var effectiveRadius = 0.4 * radius
                val distanceSqr = blockpos.center.distanceToSqr(center).toFloat()
                var force = this.radius * (0.25f + random.nextFloat() * 0.15f) * 0.02f * damage

                if (distanceSqr > radius * radius * 0.15) {
                    effectiveRadius += (random.nextDouble() - 0.5) * radius * 0.2
                }
                if (level.isInWorldBounds(blockpos) &&
                    blockpos.center.distanceToSqr(center) <= effectiveRadius * effectiveRadius
                ) {
                    val blockState = this.level.getBlockState(blockpos)
                    var resistance = blockState.block.defaultDestroyTime()
                    if (blockState.soundType === SoundType.METAL || blockState.soundType === SoundType.COPPER || blockState.soundType === SoundType.NETHERITE_BLOCK) {
                        resistance *= 3f
                    }
                    force *= (1 - (distanceSqr / (effectiveRadius * effectiveRadius))).toFloat()

                    if (resistance != -1f && force > resistance && this.damageCalculator.shouldBlockExplode(
                            this,
                            this.level,
                            blockpos,
                            blockState,
                            force
                        )
                    ) {
                        set.add(blockpos.immutable())
                    }
                }
            }

            this.toBlow.addAll(set)
        }

        val diagnosticSubject = pSource ?: damageSource.directEntity ?: damageSource.entity
        val diagnostics = diagnosticSubject != null && EliteDiagnostics.isEnabled(level)
        val blastProfile = pSource?.let(ProjectileProfiles::resolve)
        var policyFailure: Throwable? = null
        val vehicleBlast = if (diagnostics) runCatching { GroundVehicleBlastPolicy.parse(blastProfile) }
            .onFailure { policyFailure = it }.getOrNull() else GroundVehicleBlastPolicy.from(blastProfile)
        activeGroundVehicleBlast = vehicleBlast
        val heavyBlast = HeavyWarheadBlastPolicy.from(blastProfile)
        activeHeavyWarheadBlast = heavyBlast
        if (diagnostics) EliteDiagnostics.record(diagnosticSubject!!, "ground_vehicle_blast", "POLICY",
            "source_uuid", pSource?.uuid, "damage_direct_uuid", damageSource.directEntity?.uuid,
            "attacker_uuid", damageSource.entity?.uuid, "profile", blastProfile?.id,
            "extension", blastProfile?.extension(GroundVehicleBlastPolicy.ID)?.toString()?.take(1024),
            "policy_resolved", vehicleBlast != null, "inner", vehicleBlast?.innerRadius,
            "outer", vehicleBlast?.outerRadius, "edge_damage", vehicleBlast?.edgeDamage,
            "parser_exception", policyFailure?.javaClass?.name,
            "parser_message", policyFailure?.message?.take(512),
            "raw_damage", damage, "raw_radius", radius, "position", Vec3(x, y, z))
        val fragmentPolicy = com.atsuishio.superbwarfare.api.projectile.WarheadFragmentPolicy.from(blastProfile)
        val diameter = maxOf(this.radius * 2, vehicleBlast?.outerRadius?.toFloat() ?: 0f, heavyBlast?.outerRadius?.toFloat() ?: 0f)
        val queryRadius = maxOf(diameter.toDouble(), fragmentPolicy?.range ?: 0.0)
        val x0 = Mth.floor(this.x - queryRadius - 1)
        val x1 = Mth.floor(this.x + queryRadius + 1)
        val y0 = Mth.floor(this.y - queryRadius - 1)
        val y1 = Mth.floor(this.y + queryRadius + 1)
        val z0 = Mth.floor(this.z - queryRadius - 1)
        val z1 = Mth.floor(this.z + queryRadius + 1)
        val list = this.level.getEntities(
            this.pSource,
            AABB(x0.toDouble(), y0.toDouble(), z0.toDouble(), x1.toDouble(), y1.toDouble(), z1.toDouble())
        )
        val candidatesBeforeEvent = if (diagnostics) list.size else 0
        ForgeEventFactory.onExplosionDetonate(this.level, this, list, queryRadius)
        val position = Vec3(this.x, this.y, this.z)
        if (diagnostics) EliteDiagnostics.record(diagnosticSubject!!, "ground_vehicle_blast", "QUERY",
            "source_uuid", pSource?.uuid, "profile", blastProfile?.id, "query_radius", diameter,
            "candidates_before_event", candidatesBeforeEvent, "candidates_after_event", list.size)

        var hit = if (fragmentPolicy != null && level is net.minecraft.server.level.ServerLevel)
            com.atsuishio.superbwarfare.api.projectile.WarheadFragments.apply(level, pSource, damageSource, position, list, fragmentPolicy) {
                it is VehicleEntity && heavyBlast?.affects(sqrt(it.boundingBox.distanceToSqr(position))) == true
            } else false

        for (entity in list) {
            val ignoresExplosion = entity.ignoreExplosion()
            if (diagnostics && entity is VehicleEntity) {
                val bounds = entity.boundingBox
                val nearest = Vec3(position.x.coerceIn(bounds.minX, bounds.maxX),
                    position.y.coerceIn(bounds.minY, bounds.maxY), position.z.coerceIn(bounds.minZ, bounds.maxZ))
                EliteDiagnostics.record(entity,
                "ground_vehicle_blast", "TARGET_ROUTE", "source_uuid", pSource?.uuid,
                "profile", blastProfile?.id, "policy_resolved", vehicleBlast != null,
                "vehicle_type", entity.vehicleType, "bounds", bounds,
                "nearest", nearest, "distance", nearest.distanceTo(position),
                "route", when {
                    ignoresExplosion -> "IGNORES_EXPLOSION"
                    vehicleBlast == null -> "NATIVE_NO_POLICY"
                    entity.vehicleType == com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType.AIRPLANE ||
                        entity.vehicleType == com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType.HELICOPTER -> "NATIVE_AIRCRAFT"
                    else -> "RESOLVED_GROUND"
                })
            }
            if (!ignoresExplosion) {
                if (heavyBlast != null && entity is VehicleEntity) {
                    val distance = sqrt(entity.boundingBox.distanceToSqr(position))
                    val resolved = heavyBlast.damage(distance, entity.vehicleType, entity.isLightlyArmored(), entity.health, entity.getMaxHealth())
                    if (resolved > 0) {
                        val result = entity.applyResolvedDamage(com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleDamageRequest(
                            damageSource, resolved, lethal = heavyBlast.lethal(distance, entity.vehicleType)))
                        hit = hit || result.accepted
                    }
                    continue
                }
                if (vehicleBlast != null && entity is VehicleEntity && vehicleBlast.appliesTo(entity.vehicleType)) {
                    val box = entity.boundingBox
                    val nearest = Vec3(position.x.coerceIn(box.minX, box.maxX),
                        position.y.coerceIn(box.minY, box.maxY), position.z.coerceIn(box.minZ, box.maxZ))
                    val distance = nearest.distanceTo(position)
                    val lethal = vehicleBlast.lethal(distance)
                    val resolved = if (lethal) maxOf(entity.health, entity.getMaxHealth()) else vehicleBlast.damage(distance)
                    if (resolved > 0) {
                        val healthBefore = entity.health
                        val result = entity.applyResolvedDamage(
                            com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleDamageRequest(
                                damageSource, resolved, lethal = lethal))
                        hit = hit || result.accepted
                        if (diagnostics) EliteDiagnostics.record(entity, "ground_vehicle_blast", "RESOLVED_RESULT",
                            "source_uuid", pSource?.uuid, "profile", blastProfile?.id, "nearest", nearest,
                            "distance", distance, "lethal", lethal, "resolved_damage", resolved,
                            "health_before", healthBefore, "health_after", entity.health,
                            "accepted", result.accepted, "applied_damage", result.appliedDamage,
                            "destroyed", result.destroyed, "rejection", result.rejection)
                    } else if (diagnostics) {
                        EliteDiagnostics.record(entity, "ground_vehicle_blast", "ZERO_OUTSIDE_RADIUS",
                            "source_uuid", pSource?.uuid, "profile", blastProfile?.id,
                            "nearest", nearest, "distance", distance, "resolved_damage", resolved)
                    }
                    continue
                }
                val distanceRate = sqrt(entity.distanceToSqr(position)) / diameter.toDouble()
                if (distanceRate <= 1) {
                    val xDistance = entity.x - this.x
                    val yDistance = (if (entity is PrimedTnt) entity.y else entity.eyeY) - this.y
                    val zDistance = entity.z - this.z
                    val distance = sqrt(xDistance * xDistance + yDistance * yDistance + zDistance * zDistance)

                    if (distance != 0.0) {
                        val seenPercent =
                            getSeenPercent(position, entity).toDouble().coerceIn(
                                0.01 * ExplosionConfig.EXPLOSION_PENETRATION_RATIO.get(),
                                Double.POSITIVE_INFINITY
                            )
                        val damagePercent = (1 - distanceRate) * seenPercent
                        val damageFinal = (damagePercent * damagePercent + damagePercent) / 2 * damage
                        if (diagnostics && entity is VehicleEntity) EliteDiagnostics.record(entity,
                            "ground_vehicle_blast", "NATIVE_DAMAGE", "source_uuid", pSource?.uuid,
                            "profile", blastProfile?.id, "distance_rate", distanceRate,
                            "seen_percent", seenPercent, "raw_damage", damage, "requested_damage", damageFinal)

                        if (entity is Monster) {
                            doDamage(
                                entity,
                                this.damageSource,
                                damageFinal.toFloat() * (1 + 0.2f * this.damageMultiplier)
                            )
                        } else {
                            doDamage(entity, this.damageSource, damageFinal.toFloat())
                        }

                        if (entity is LivingEntity) {
                            var force = damageFinal * 0.015
                            force = ProtectionEnchantment.getExplosionKnockbackAfterDampener(entity, force)
                            val vec31 = position.vectorTo(entity.boundingBox.center).normalize()
                            if (entity is Player && !entity.isCreative && !entity.isSpectator) {
                                entity.deltaMovement = entity.deltaMovement.add(vec31.scale(force))
                            } else {
                                entity.deltaMovement = entity.deltaMovement.add(vec31.scale(force))
                            }
                        }

                        if (entity is LivingEntity || entity is VehicleEntity) {
                            hit = true
                        }

                        entity.invulnerableTime = 1

                        if (fireTime > 0) {
                            entity.setSecondsOnFire(fireTime)
                        }
                    }
                }
            }
        }

        if (hit) {
            val player = this.damageSource.entity
            if (player is ServerPlayer) {
                SoundTool.playLocalSound(player, ModSounds.INDICATION.get())
                player.sendPacket(ClientIndicatorMessage(0, 5))
            }
        }
    }

    /**
     * Hopkinson-Cranz TNT-equivalent blast. The severe-collapse sphere is the infantry (and soft-vehicle)
     * damage zone with occlusion; vehicles near the fireball take true damage for charges >= 25 kg; blocks
     * break inside the fireball. Penetrator bombs push the fireball volume forward as a cylinder that ignores
     * occlusion and breaks every breakable block it covers. Warhead fragments are unchanged; the legacy
     * vehicle listener and the heavy-warhead / ground-vehicle policies do not run.
     */
    private fun explodeTnt(plan: TntBlast.Plan) {
        val center = Vec3(this.x, this.y, this.z)
        val parameters = plan.parameters
        val radii = plan.radii
        val cylinder = plan.penetratorDirection?.let {
            BlastModel.penetratorCylinder(x, y, z, it.x, it.y, it.z, radii.fireball, parameters)
        }

        if (ExplosionConfig.explosionsBreakBlocks()) {
            this.level.gameEvent(this.pSource, GameEvent.EXPLODE, center)
            if (cylinder != null) collectCylinderBlocks(cylinder, plan) else collectFireballBlocks(plan)
        }

        val diagnosticSubject = pSource ?: damageSource.directEntity ?: damageSource.entity
        val diagnostics = diagnosticSubject != null && EliteDiagnostics.isEnabled(level)
        val blastProfile = pSource?.let(ProjectileProfiles::resolve)
        val fragmentPolicy = com.atsuishio.superbwarfare.api.projectile.WarheadFragmentPolicy.from(blastProfile)
        val reach = maxOf(BlastModel.queryRadius(radii, parameters, cylinder), fragmentPolicy?.range ?: 0.0)
        // Vehicle hull boxes reach past their entity box: widen the query, then measure to the hull (below).
        val queryReach = reach + 1 + com.atsuishio.superbwarfare.tools.blast.BlastDistance.VEHICLE_QUERY_MARGIN
        val list = this.level.getEntities(
            this.pSource,
            AABB(x - queryReach, y - queryReach, z - queryReach, x + queryReach, y + queryReach, z + queryReach)
        )
        ForgeEventFactory.onExplosionDetonate(this.level, this, list, reach)
        if (diagnostics) EliteDiagnostics.record(diagnosticSubject!!, "tnt_blast", "PLAN",
            "source_uuid", pSource?.uuid, "profile", blastProfile?.id, "tnt_kg", plan.kg,
            "fireball", radii.fireball, "severe", radii.severe, "moderate", radii.moderate,
            "penetrator", cylinder != null, "cylinder_length", cylinder?.length, "position", center,
            "candidates", list.size, "blocks", toBlow.size)

        var hit = if (fragmentPolicy != null && level is ServerLevel)
            com.atsuishio.superbwarfare.api.projectile.WarheadFragments.apply(
                level, pSource, damageSource, center, list, fragmentPolicy) else false

        // High-explosive gun and mortar shells are anti-personnel rounds first: harder hitting and a little wider
        // against infantry than a bomb or warhead of the same charge.
        // Unguided rockets too (owner 2026-09-29: rockets felt harmless outside a direct hit).
        val heShell = pSource is com.atsuishio.superbwarfare.entity.projectile.CannonShellEntity ||
            pSource is com.atsuishio.superbwarfare.entity.projectile.SmallCannonShellEntity ||
            pSource is com.atsuishio.superbwarfare.entity.projectile.MortarShellEntity ||
            pSource is com.atsuishio.superbwarfare.entity.projectile.SmallRocketEntity ||
            pSource is com.atsuishio.superbwarfare.entity.projectile.MediumRocketEntity
        val munitionBlast = pSource is net.minecraft.world.entity.projectile.Projectile
        val heShellInfantry = if (heShell) HE_SHELL_INFANTRY_DAMAGE else 1.0
        val infantryRadii = if (heShell) radii.copy(severe = minOf(radii.severe * HE_SHELL_INFANTRY_REACH, radii.moderate))
            else radii
        for (entity in list) {
            if (entity.ignoreExplosion()) continue
            val box = entity.boundingBox
            // Munitions measure to the vehicle's hull boxes; vehicle death explosions keep the entity box, so a wreck
            // going up does not reach further into its neighbours than before.
            val distance = if (entity is VehicleEntity && munitionBlast)
                com.atsuishio.superbwarfare.tools.blast.BlastDistance.toVehicle(entity, center)
            else sqrt(box.distanceToSqr(center))
            val inCylinder = cylinder?.intersectsBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ) == true
            if (entity is VehicleEntity) {
                hit = applyTntToVehicle(entity, plan, center, distance, cylinder, inCylinder, diagnostics) || hit
                continue
            }
            // Enclosed crew is shielded by the hull: the vehicle rules above own that damage (enclosed-seat
            // damage would otherwise be redirected into the hull and bypass the armored-vehicle threshold).
            val mount = entity.vehicle
            if (mount is VehicleEntity && mount.isEnclosed(entity)) continue
            val damage = when {
                // Inside the penetrator cylinder the fireball itself reaches the target: no occlusion.
                inCylinder -> parameters.infantryCentreDamage * BlastModel.infantryPeakScale(plan.kg)
                distance < infantryRadii.severe -> heShellInfantry * BlastModel.infantryDamageFor(plan.kg,
                    distance, BlastExposure.seenFraction(level, center, entity), infantryRadii, parameters)
                else -> 0.0
            }
            if (damage <= 0.0) continue
            applyBlastDamage(entity, center, damage)
            if (entity is LivingEntity) hit = true
        }

        if (hit) {
            val player = this.damageSource.entity
            if (player is ServerPlayer) {
                SoundTool.playLocalSound(player, ModSounds.INDICATION.get())
                player.sendPacket(ClientIndicatorMessage(0, 5))
            }
        }
    }

    /**
     * A classed vehicle (DamageClass, owner direction 2026-09-28) takes 3 x kg x falloff x its class multiplier,
     * with no charge threshold, except from the munition that hit it directly. An aircraft near an air-to-air or
     * surface-to-air missile takes the missile rule scaled by proximity instead, once per missile; blast damage
     * on an airframe also wears the nearer wing. Vehicles without a class keep the legacy true-damage rules.
     */
    private fun applyTntToVehicle(
        vehicle: VehicleEntity, plan: TntBlast.Plan, center: Vec3, distance: Double,
        cylinder: BlastCylinder?, inCylinder: Boolean, diagnostics: Boolean,
    ): Boolean {
        val damageClass = com.atsuishio.superbwarfare.tools.blast.VehicleDamageClass.of(vehicle)
            ?: return applyLegacyTntToVehicle(vehicle, plan, center, distance, cylinder, inCylinder, diagnostics)
        val parameters = plan.parameters
        val radii = plan.radii
        var hit = false
        val healthBefore = vehicle.health
        val direct = com.atsuishio.superbwarfare.tools.blast.StruckVehicles.directDamage(pSource, vehicle)
        val struck = direct != null
        val missile = damageClass.aircraft && missileSource(pSource)
        var rule: String
        val blast = when {
            missile -> {
                rule = "missile_proximity"
                val proximity = com.atsuishio.superbwarfare.api.aircraft.AircraftHitRules.proximity(distance, radii.severe)
                if (proximity > 0.0) com.atsuishio.superbwarfare.api.aircraft.AircraftHitRules.missile(
                    plan.kg, vehicle.getMaxHealth().toDouble(), proximity) else 0.0
            }
            cylinder != null && inCylinder -> { rule = "penetrator"; parameters.vehicleDamagePerKg * plan.kg * damageClass.blastMultiplier }
            // A penetrator's fireball also reaches what stands beside the crater (was 0 outside the cylinder).
            cylinder != null -> { rule = "outside_penetrator"; BlastModel.classBlastDamage(plan.kg, distance, radii, damageClass.blastMultiplier, parameters) }
            else -> { rule = "class_blast"; BlastModel.classBlastDamage(plan.kg, distance, radii, damageClass.blastMultiplier, parameters) }
        }
        // The munition that hit this vehicle directly only adds what its blast does beyond that hit.
        val damage = com.atsuishio.superbwarfare.tools.blast.StruckVehicles.remainingBlast(blast, direct)
        if (struck) rule = if (damage > 0.0) "$rule+struck" else "struck_directly"
        if (damage > 0.0) {
            val result = vehicle.applyResolvedDamage(
                com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleDamageRequest(
                    damageSource, damage.toFloat(),
                    com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleModulePolicy.SKIP_NATIVE
                )
            )
            hit = result.accepted
            if (result.accepted && missile) com.atsuishio.superbwarfare.tools.blast.StruckVehicles.mark(pSource, vehicle)
            if (result.accepted && damageClass == com.atsuishio.superbwarfare.tools.blast.VehicleDamageClass.AIRPLANE)
                com.atsuishio.superbwarfare.api.aircraft.AircraftSurfaceModules.applyBlast(vehicle, center,
                    result.appliedDamage.toDouble())
        }
        if (vehicle.isAlive) applyBlastPush(vehicle, plan, center, distance)
        if (damage >= 5.0 || diagnostics && (damage > 0.0 || struck)) com.atsuishio.superbwarfare.tools.blast.BlastDamageLog.vehicle(vehicle, pSource,
            damageClass.name, rule, plan.kg, distance, radii.severe, damage, healthBefore)
        if (diagnostics && damage > 0.0) EliteDiagnostics.record(vehicle,
            "tnt_blast", "VEHICLE", "source_uuid", pSource?.uuid, "tnt_kg", plan.kg, "distance", distance,
            "class", damageClass.name, "rule", rule, "damage", damage,
            "health_before", healthBefore, "health_after", vehicle.health)
        return hit
    }

    /** Air-to-air / surface-to-air missiles (not anti-tank ones) for the aircraft proximity rule. */
    private fun missileSource(source: Entity?): Boolean {
        if (source == null) return false
        if (com.atsuishio.superbwarfare.tools.blast.ExternalMunitionBlasts.isExternal(source)) return true
        val projectile = source as? net.minecraft.world.entity.projectile.Projectile ?: return false
        return com.atsuishio.superbwarfare.api.aircraft.AircraftProjectileDamage.kindOf(projectile, null) ==
            com.atsuishio.superbwarfare.api.aircraft.AircraftHitRules.Kind.MISSILE
    }

    /** Legacy rules for vehicles without a DamageClass: true damage near the fireball (>= 25 kg), soft falloff. */
    private fun applyLegacyTntToVehicle(
        vehicle: VehicleEntity, plan: TntBlast.Plan, center: Vec3, distance: Double,
        cylinder: BlastCylinder?, inCylinder: Boolean, diagnostics: Boolean,
    ): Boolean {
        val parameters = plan.parameters
        val radii = plan.radii
        var hit = false
        val trueDamage = if (cylinder != null) {
            if (inCylinder) BlastModel.vehicleTrueDamageInCylinder(plan.kg, parameters) else 0.0
        } else BlastModel.vehicleTrueDamage(plan.kg, distance, radii, parameters)
        val healthBefore = vehicle.health
        if (trueDamage > 0.0) {
            val result = vehicle.applyResolvedDamage(
                com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleDamageRequest(
                    damageSource, trueDamage.toFloat(),
                    com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleModulePolicy.SKIP_NATIVE
                )
            )
            hit = result.accepted
        }
        if (vehicle.isAlive) applyBlastPush(vehicle, plan, center, distance)
        val armored = vehicle.hasArmorHitboxes()
        var softDamage = 0.0
        if (!armored && vehicle.isAlive) {
            softDamage = when {
                inCylinder -> parameters.softVehicleCentreDamage
                distance < radii.severe -> BlastModel.softVehicleDamage(
                    distance, BlastExposure.seenFraction(level, center, vehicle), radii, parameters)
                else -> 0.0
            }
            // Ordinary hurt: the vehicle's own damage modifiers and aircraft hit rules still apply.
            if (softDamage > 0.0 && vehicle.hurt(damageSource, softDamage.toFloat())) hit = true
        }
        if (diagnostics && (trueDamage > 0.0 || softDamage > 0.0)) EliteDiagnostics.record(vehicle,
            "tnt_blast", "VEHICLE", "source_uuid", pSource?.uuid, "tnt_kg", plan.kg, "distance", distance,
            "in_cylinder", inCylinder, "armor_hitboxes", armored, "true_damage", trueDamage,
            "soft_damage", softDamage, "health_before", healthBefore, "health_after", vehicle.health)
        return hit
    }

    /**
     * Shoves (and spins) ground vehicles: IFV-class from 5 kg, MBT-class from 20 kg, harder for bigger charges and
     * closer blasts, lifting them near the charge ([BlastPush]).
     */
    private fun applyBlastPush(vehicle: VehicleEntity, plan: TntBlast.Plan, center: Vec3, distance: Double) {
        val type = vehicle.vehicleType
        if (type !in PUSHED_TYPES || vehicle.isFixedWingFlightVehicle()) return
        val pushRadius = plan.radii.severe
        val speed = BlastPush.speedMps(plan.kg, distance, pushRadius, vehicle.mass.toDouble())
        if (speed <= 0.0) return
        val target = vehicle.boundingBox.center
        var hx = target.x - center.x
        var hz = target.z - center.z
        val horizontal = sqrt(hx * hx + hz * hz)
        if (horizontal > 1.0e-4) { hx /= horizontal; hz /= horizontal } else { hx = 0.0; hz = 0.0 }
        val lift = BlastPush.liftShare(distance, pushRadius)
        val side = sqrt((1.0 - lift * lift).coerceAtLeast(0.0))
        val perTick = speed / 20.0
        val vx = hx * side * perTick
        val vy = lift * perTick
        val vz = hz * side * perTick
        // Spin: the top tips away from the charge, plus a yaw from where it struck along the hull.
        val yawRadians = Math.toRadians(vehicle.yRot.toDouble())
        val forwardX = -sin(yawRadians); val forwardZ = cos(yawRadians)
        val along = hx * forwardX + hz * forwardZ          // +1 blast behind (pushes forward)
        val across = hx * forwardZ - hz * forwardX
        val spin = (speed * 1.6).coerceAtMost(40.0)          // deg/tick at full push
        val random = level.random
        val yawSpin = ((random.nextDouble() - 0.5) * 0.8 + 0.4 * across * along.sign) * spin * 0.6
        val pitchSpin = -along * spin * 0.5
        val rollSpin = across * spin * 0.5
        vehicle.applyBlastPush(vx, vy, vz, yawSpin.toFloat(), pitchSpin.toFloat(), rollSpin.toFloat())
    }

    /** Legacy damage application (monster bonus, knockback, fire) for a non-vehicle blast target. */
    private fun applyBlastDamage(entity: Entity, center: Vec3, damage: Double) {
        if (entity is Monster) {
            doDamage(entity, this.damageSource, damage.toFloat() * (1 + 0.2f * this.damageMultiplier))
        } else {
            doDamage(entity, this.damageSource, damage.toFloat())
        }
        if (entity is LivingEntity) {
            var force = damage * 0.015
            force = ProtectionEnchantment.getExplosionKnockbackAfterDampener(entity, force)
            val direction = center.vectorTo(entity.boundingBox.center)
            if (direction.lengthSqr() > 1.0e-8) {
                entity.deltaMovement = entity.deltaMovement.add(direction.normalize().scale(force))
            }
        }
        entity.invulnerableTime = 1
        if (fireTime > 0) {
            entity.setSecondsOnFire(fireTime)
        }
    }

    /** Blocks whose centres lie inside the fireball; the force falls to zero at its edge (hardness rules kept). */
    private fun collectFireballBlocks(plan: TntBlast.Plan) {
        val fireball = plan.radii.fireball
        if (fireball <= 0.0) return
        val random = level.random
        val limit = fireball * fireball
        val cursor = BlockPos.MutableBlockPos()
        val set = LinkedHashSet<BlockPos>()
        for (bx in Mth.floor(x - fireball)..Mth.floor(x + fireball)) {
            for (by in Mth.floor(y - fireball)..Mth.floor(y + fireball)) {
                for (bz in Mth.floor(z - fireball)..Mth.floor(z + fireball)) {
                    val dx = bx + 0.5 - x
                    val dy = by + 0.5 - y
                    val dz = bz + 0.5 - z
                    val distanceSqr = dx * dx + dy * dy + dz * dz
                    if (distanceSqr > limit) continue
                    cursor.set(bx, by, bz)
                    if (!level.isInWorldBounds(cursor)) continue
                    val state = level.getBlockState(cursor)
                    if (state.isAir) continue
                    var resistance = state.block.defaultDestroyTime()
                    if (resistance < 0f) continue
                    if (state.soundType === SoundType.METAL || state.soundType === SoundType.COPPER ||
                        state.soundType === SoundType.NETHERITE_BLOCK) resistance *= 3f
                    val force = BlastModel.blockForce(plan.kg, sqrt(distanceSqr), fireball, random.nextDouble(), plan.parameters)
                    if (force > resistance &&
                        damageCalculator.shouldBlockExplode(this, level, cursor, state, force.toFloat())) {
                        set.add(cursor.immutable())
                    }
                }
            }
        }
        this.toBlow.addAll(set)
    }

    /** Every breakable block whose centre lies inside the penetrator cylinder. */
    private fun collectCylinderBlocks(cylinder: BlastCylinder, plan: TntBlast.Plan) {
        val bounds = cylinder.bounds()
        val force = BlastModel.cylinderBlockForce(plan.kg, plan.parameters).toFloat()
        val cursor = BlockPos.MutableBlockPos()
        val set = LinkedHashSet<BlockPos>()
        for (bx in Mth.floor(bounds[0])..Mth.floor(bounds[3])) {
            for (by in Mth.floor(bounds[1])..Mth.floor(bounds[4])) {
                for (bz in Mth.floor(bounds[2])..Mth.floor(bounds[5])) {
                    if (!cylinder.contains(bx + 0.5, by + 0.5, bz + 0.5)) continue
                    cursor.set(bx, by, bz)
                    if (!level.isInWorldBounds(cursor)) continue
                    val state = level.getBlockState(cursor)
                    if (state.isAir || state.block.defaultDestroyTime() < 0f) continue
                    if (damageCalculator.shouldBlockExplode(this, level, cursor, state, force)) set.add(cursor.immutable())
                }
            }
        }
        this.toBlow.addAll(set)
    }

    override fun finalizeExplosion(pSpawnParticles: Boolean) {
        if (this.level.isClientSide) {
            this.level.playLocalSound(
                this.x,
                this.y,
                this.z,
                SoundEvents.GENERIC_EXPLODE,
                SoundSource.BLOCKS,
                4.0f,
                (1.0f + (this.level.random.nextFloat() - this.level.random.nextFloat()) * 0.2f) * 0.7f,
                false
            )
        }

        val flag = this.interactsWithBlocks()
        if (pSpawnParticles) {
            if (!(this.radius < 2.0f) && flag) {
                this.level.addParticle(ParticleTypes.EXPLOSION_EMITTER, this.x, this.y, this.z, 1.0, 0.0, 0.0)
            } else {
                this.level.addParticle(ParticleTypes.EXPLOSION, this.x, this.y, this.z, 1.0, 0.0, 0.0)
            }
        }

        // Backstop for callers that pass BlockInteraction.DESTROY directly: nothing breaks while the munition
        // block-damage master switch is off.
        if (flag && !ExplosionConfig.MUNITION_BLOCK_DAMAGE.get()) this.toBlow.clear()
        if (flag && this.toBlow.isNotEmpty()) {
            val list = ObjectArrayList<Pair<ItemStack, BlockPos>>()
            val flag1 = this.indirectSourceEntity is Player

            val blowList = this.toBlow.stream().filter { !this.level.getBlockState(it).isAir }.toList()

            for (blockpos in blowList) {
                val blockstate = this.level.getBlockState(blockpos)

                val blockpos1 = blockpos.immutable()
                this.level.profiler.push("explosion_blocks")

                if (this.level is ServerLevel) {
                    val blockEntity = if (blockstate.hasBlockEntity()) this.level.getBlockEntity(blockpos) else null
                    val lootParamsBuilder = (LootParams.Builder(level))
                        .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(blockpos))
                        .withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
                        .withOptionalParameter(LootContextParams.BLOCK_ENTITY, blockEntity)
                        .withOptionalParameter(LootContextParams.THIS_ENTITY, this.pSource)
                        .withParameter(LootContextParams.EXPLOSION_RADIUS, this.radius)

                    blockstate.spawnAfterBreak(level, blockpos, ItemStack.EMPTY, flag1)
                    blockstate.getDrops(lootParamsBuilder).forEach {
                        addBlockDrops(list, it, blockpos1)
                    }
                }

                blockstate.onBlockExploded(this.level, blockpos, this)
                this.level.profiler.pop()
            }

            for (pair in list) {
                Block.popResource(this.level, pair.getSecond(), pair.getFirst())
            }
        }
    }

    class Builder(private val directSource: Entity) {
        private val level: Level = directSource.level()
        private var sourceEntity: Entity?
        private var attackerEntity: Entity?
        private var damage = 0f
        private var radius = 0f
        private var particleType: ParticleTool.ParticleType = ParticleTool.ParticleType.MINI
        private var destroyBlock: Supplier<BlockInteraction> =
            Supplier { if (ExplosionConfig.explosionsBreakBlocks()) BlockInteraction.DESTROY else BlockInteraction.KEEP }
        private var fireTime = 0
        private var damageMultiplier = 1f
        private var damageSource: DamageSource? = null
        private var particlePosition: Vec3? = null
        private var emitFx = true
        private var explosionCauseId: ResourceLocation? = null
        private var explosionProfileId: ResourceLocation? = null
        private var tntEquivalentKg: Double? = null
        private var penetratorDirection: Vec3? = null
        var position: Vec3

        init {
            this.sourceEntity = directSource
            this.attackerEntity = directSource
            this.position = Vec3(directSource.x, directSource.eyeY, directSource.z)
        }

        fun source(source: Entity?): Builder {
            this.sourceEntity = source
            return this
        }

        fun attacker(attacker: Entity?): Builder {
            this.attackerEntity = attacker
            return this
        }

        fun damage(damage: Float): Builder {
            this.damage = damage
            return this
        }

        fun radius(radius: Float): Builder {
            this.radius = radius
            return this
        }

        fun withParticleType(particleType: ParticleTool.ParticleType): Builder {
            this.particleType = particleType
            return this
        }

        fun emitFx(emitFx: Boolean): Builder {
            this.emitFx = emitFx
            return this
        }

        fun explosionCause(causeId: ResourceLocation?): Builder {
            this.explosionCauseId = causeId
            return this
        }

        fun explosionProfile(profileId: ResourceLocation?): Builder {
            this.explosionProfileId = profileId
            return this
        }

        fun destroyBlock(destroyBlock: Supplier<BlockInteraction>): Builder {
            this.destroyBlock = destroyBlock
            return this
        }

        fun keepBlock(): Builder {
            this.destroyBlock = Supplier { BlockInteraction.KEEP }
            return this
        }

        fun fireTime(fireTime: Int): Builder {
            this.fireTime = fireTime
            return this
        }

        fun damageMultiplier(damageMultiplier: Float): Builder {
            this.damageMultiplier = damageMultiplier
            return this
        }

        fun damageSource(damageSource: DamageSource?): Builder {
            this.damageSource = damageSource
            return this
        }

        fun position(position: Vec3): Builder {
            this.position = position
            return this
        }

        fun particlePosition(particlePosition: Vec3?): Builder {
            this.particlePosition = particlePosition
            return this
        }

        /**
         * TNT-equivalent charge in kg. > 0 (with the model enabled) replaces the legacy damage/radius blast;
         * 0 forces the legacy blast. Unset: the direct source's stamped/default charge is used, except for
         * vehicles (whose death explosions stay on the legacy path).
         */
        fun tntEquivalent(kg: Double): Builder {
            this.tntEquivalentKg = kg
            return this
        }

        /** Penetrator bomb travel direction: the fireball volume becomes a forward cylinder. */
        fun penetrator(direction: Vec3?): Builder {
            this.penetratorDirection = direction
            return this
        }

        fun explode() {
            if (level.isClientSide) return

            val source =
                (if (this.damageSource != null) this.damageSource else ModDamageTypes.causeCustomExplosionDamage(
                    level.registryAccess(),
                    sourceEntity,
                    attackerEntity
                ))!!

            val blockInteraction = if (ProjectileProfiles.suppressesVehicleBlockDamage(directSource)) {
                BlockInteraction.KEEP
            } else {
                destroyBlock.get()
            }
            val kg = tntEquivalentKg ?: if (directSource is VehicleEntity) 0.0 else TntEquivalents.resolve(directSource)
            val plan = TntBlast.plan(kg, penetratorDirection)
            // The Explosion radius drives camera shake and loot survival; a TNT blast reaches its moderate radius.
            val explosionRadius = if (plan != null) maxOf(radius, plan.radii.moderate.toFloat()) else radius
            val customExplosion = CustomExplosion(
                level, directSource,
                source, damage,
                position.x, position.y, position.z, explosionRadius, blockInteraction
            )
                .setFireTime(fireTime)
                .setDamageMultiplier(damageMultiplier)
                .setTntPlan(plan)
            customExplosion.explode()
            ForgeEventFactory.onExplosionStart(directSource.level(), customExplosion)
            customExplosion.finalizeExplosion(false)

            val fireball = plan?.radii?.fireball?.toFloat() ?: 0f
            // The dust, flash and sound recipe covers the damaging reach (never less than the authored radius);
            // the fireball itself is sent separately below at exactly R = k * W^(1/3).
            val presentationRadius = if (plan != null)
                BlastModel.presentationRadius(radius.toDouble(), plan.radii).toFloat() else radius
            ParticleTool.dispatchExplosionFx(
                directSource.level(),
                ExplosionFxContext(
                    directSource = directSource,
                    attacker = attackerEntity,
                    gameplayPosition = position,
                    particlePosition = particlePosition ?: position,
                    radius = presentationRadius,
                    emitFx = emitFx,
                    particleType = if (plan != null)
                        TntBlast.particleType(particleType, presentationRadius.toDouble()) else particleType,
                    causeId = explosionCauseId,
                    profileId = explosionProfileId,
                    fireballRadius = fireball,
                )
            )
            if (plan != null) {
                // The visible fireball is the blast's own presentation, R = k * W^(1/3): shown for every TNT
                // detonation, including when an armor hit, a pack effect or FFA replaced the impact recipe.
                (level as? ServerLevel)?.let { FireballMessage.send(it, particlePosition ?: position, plan.radii.fireball) }
            }
            if (plan != null && plan.producesShockwave) {
                // A separate phenomenon from the fireball: sent even when the impact presentation was replaced.
                (level as? ServerLevel)?.let { ShockwaveMessage.send(it, particlePosition ?: position, plan) }
            }
        }
    }

    companion object {
        /** HE gun/mortar shells against infantry: damage and severe-radius factors. */
        const val HE_SHELL_INFANTRY_DAMAGE = 1.3
        const val HE_SHELL_INFANTRY_REACH = 1.25
        private val PUSHED_TYPES = setOf(VehicleType.TANK, VehicleType.APC, VehicleType.AA, VehicleType.CAR,
            VehicleType.ARTILLERY)

        @JvmStatic
        fun addBlockDrops(
            pDropPositionArray: ObjectArrayList<Pair<ItemStack, BlockPos>>,
            pStack: ItemStack,
            pPos: BlockPos
        ) {
            val i = pDropPositionArray.size

            for (j in 0..<i) {
                val pair = pDropPositionArray[j]
                val itemstack = pair.getFirst()
                if (ItemEntity.areMergable(itemstack, pStack)) {
                    val itemstack1 = ItemEntity.merge(itemstack, pStack, 16)
                    pDropPositionArray[j] = Pair.of(itemstack1, pair.getSecond())
                    if (pStack.isEmpty) {
                        return
                    }
                }
            }

            pDropPositionArray.add(Pair.of(pStack, pPos))
        }
    }
}
