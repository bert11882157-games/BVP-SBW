package com.atsuishio.superbwarfare.api.projectile

import com.atsuishio.superbwarfare.data.CustomData
import com.atsuishio.superbwarfare.data.projectile.GuidedPropulsionData
import com.atsuishio.superbwarfare.data.projectile.MotionSyncPolicy
import com.atsuishio.superbwarfare.data.projectile.PenetrationCurve
import com.atsuishio.superbwarfare.data.projectile.ProjectileTrailMode
import com.atsuishio.superbwarfare.data.projectile.RicochetCurve
import com.atsuishio.superbwarfare.data.gun.ProjectileBeltTracer
import com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.phys.Vec3
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object ProjectileProfiles {
    private const val PROFILE_TAG = "SBWProjectileProfile"
    private const val PROFILE_SNAPSHOT_TAG = "SBWProjectileProfileSnapshot"
    private const val SHOT_SEQUENCE_TAG = "SBWProjectileShotSequence"
    private const val PROFILE_SNAPSHOT_VERSION = 1
    private const val TRACER_V2_EXTENSION_ID = "berts_vehicle_pack:tracer_v2"
    private const val PROJECTILE_EFFECT_V1_EXTENSION_ID = "berts_vehicle_pack:projectile_effect_v1"
    /** Canonical BVP elite-green tracer colour used by generated tracer_v2 profiles. */
    private const val ELITE_GREEN_TRACER_RED = 72
    private const val ELITE_GREEN_TRACER_GREEN = 255
    private const val ELITE_GREEN_TRACER_BLUE = 96
    private val SMALL_WHITE_TRACER_ROUNDS = setOf(
        ResourceLocation("berts_vehicle_pack", "pg9"),
        ResourceLocation("berts_vehicle_pack", "og9"),
    )

    private data class CachedProfile(val generation: Long, val profile: ResolvedProjectileProfile)

    /** In-memory adapter used to preserve a decoded immutable snapshot through the public copy API. */
    private class SnapshotProfileSource(
        private val id: ResourceLocation,
        private val profile: ResolvedProjectileProfile,
    ) : ProfiledProjectile {
        override fun getProjectileProfileId(): ResourceLocation = id

        override fun getResolvedProjectileProfile(): ResolvedProjectileProfile = profile

        override fun setProjectileProfileId(id: ResourceLocation?) {
            throw UnsupportedOperationException("Decoded projectile profile snapshots are immutable")
        }
    }

    private val cacheGeneration = AtomicLong()
    private val resolvedProfiles = ConcurrentHashMap<ResourceLocation, CachedProfile>()

    /**
     * Server-only launch provenance used by authoritative armor consumers.  This deliberately
     * does not enter the spawn payload/profile snapshot: clients must never author or transport
     * the range used to select a penetration-curve sample.  Entity keys remain live for the
     * projectile lifetime and are weak so failed/removed spawns cannot accumulate state.
     */
    private val serverLaunchPositions: MutableMap<Entity, Vec3> =
        Collections.synchronizedMap(WeakHashMap())

    @JvmStatic
    fun assign(entity: Entity, id: ResourceLocation?) {
        (entity as? ProfiledProjectile)?.setProjectileProfileId(id)
    }

    /**
     * Applies a per-shot belt presentation policy to the immutable profile snapshot.  The
     * authored/global profile is never mutated; the snapshot is carried by normal SBW spawn data.
     */
    @JvmStatic
    fun assignShotTracerPolicy(entity: Entity, policy: ProjectileBeltTracer?): Boolean {
        if (policy == null || policy == ProjectileBeltTracer.INHERIT) return true
        val target = entity as? ProfiledProjectile ?: return false
        val template = target.getResolvedProjectileProfile() ?: return false
        val extensions = template.extensions()
        // The BVP generator owns the v1 effect extension, while a belt policy is a per-shot
        // override.  Do not require authoring to duplicate the tracer_v2 payload in every
        // projectile profile: materialize a complete immutable tracer snapshot when GREEN is
        // requested and the authored profile has no top-level tracer object.
        val tracer = extensions.get(TRACER_V2_EXTENSION_ID)?.let {
            if (it.isJsonObject) it.asJsonObject else null
        }

        val trailMode = when (policy) {
            ProjectileBeltTracer.NONE, ProjectileBeltTracer.SUPPRESS -> {
                tracer?.addProperty("Enabled", false)
                ProjectileTrailMode.SUPPRESS
            }
            ProjectileBeltTracer.GREEN -> {
                val effectiveTracer = tracer ?: defaultTracerExtension().also {
                    extensions.add(TRACER_V2_EXTENSION_ID, it)
                }
                effectiveTracer.addProperty("Enabled", true)
                effectiveTracer.add("ColorRgb", JsonArray().apply {
                    // Keep belt GREEN identical to the existing generated BVP/elite tracer;
                    // never fall back to the pure-green SBW/default presentation.
                    add(ELITE_GREEN_TRACER_RED)
                    add(ELITE_GREEN_TRACER_GREEN)
                    add(ELITE_GREEN_TRACER_BLUE)
                })
                // GREEN is an explicit replacement policy, never an inherited DEFAULT.  This
                // keeps an authored red tracer_v2/native fallback from winning over the belt
                // round when a provider is available for the selected visual profile.
                ProjectileTrailMode.REPLACE
            }
            ProjectileBeltTracer.RED -> {
                val effectiveTracer = tracer ?: defaultTracerExtension().also {
                    extensions.add(TRACER_V2_EXTENSION_ID, it)
                }
                effectiveTracer.addProperty("Enabled", true)
                effectiveTracer.add("ColorRgb", JsonArray().apply {
                    add(255)
                    add(32)
                    add(32)
                })
                // RED is an explicit replacement just like GREEN; NONE remains the only
                // suppress-all policy and cannot fall through to an authored/default tracer.
                ProjectileTrailMode.REPLACE
            }
            ProjectileBeltTracer.INHERIT -> template.trailMode
        }

        val snapshot = ResolvedProjectileProfile(
            template.id,
            template.combat,
            template.visualProfileId,
            template.impactVisualProfileId,
            template.motionSync,
            template.collision,
            template.luminance,
            trailMode,
            template.renderScale,
            extensions,
            template.guidedPropulsion,
        )
        target.copyProjectileProfileFrom(SnapshotProfileSource(snapshot.id, snapshot))
        return true
    }

    /**
     * Applies the reusable typed small-white tracer presentation used by rounds which are
     * logically non-tracer ammunition.  Combat metadata, light/luminance, penetration, and
     * projectile motion remain untouched; only the immutable synchronized tracer extension is
     * materialized.  The identity is the authored Combat.RoundId, never a display name or gun.
     */
    @JvmStatic
    fun assignTypedSmallWhiteTracerPresentation(entity: Entity): Boolean {
        val target = entity as? ProfiledProjectile ?: return false
        val template = target.getResolvedProjectileProfile() ?: return false
        if (!isSmallWhiteTracerRoundId(template.combat?.roundId)) return true

        val extensions = template.extensions()
        val tracer = extensions.get(TRACER_V2_EXTENSION_ID)?.let {
            if (it.isJsonObject) it.asJsonObject else null
        } ?: defaultTracerExtension().also {
            extensions.add(TRACER_V2_EXTENSION_ID, it)
        }
        tracer.addProperty("Enabled", true)
        tracer.add("ColorRgb", JsonArray().apply {
            add(255)
            add(255)
            add(255)
        })

        val snapshot = ResolvedProjectileProfile(
            template.id,
            template.combat,
            template.visualProfileId,
            template.impactVisualProfileId,
            template.motionSync,
            template.collision,
            template.luminance,
            ProjectileTrailMode.REPLACE,
            template.renderScale,
            extensions,
            template.guidedPropulsion,
        )
        target.copyProjectileProfileFrom(SnapshotProfileSource(snapshot.id, snapshot))
        return true
    }

    /** Canonical dimensions for a typed tracer synthesized from a belt policy. */
    private fun defaultTracerExtension(): JsonObject = JsonObject().apply {
        addProperty("Enabled", true)
        add("ColorRgb", JsonArray().apply {
            add(255)
            add(255)
            add(255)
        })
        addProperty("EveryNthShot", 1)
        addProperty("LengthBlocks", 4.0)
        addProperty("WidthBlocks", 0.025)
        addProperty("Opacity", 0.9)
        addProperty("LifetimeTicks", 3)
        add("Core", JsonObject().apply {
            addProperty("WidthScale", 1.0)
            addProperty("OpacityScale", 1.0)
        })
        add("Glow", JsonObject().apply {
            addProperty("WidthScale", 4.0)
            addProperty("OpacityScale", 0.25)
        })
    }

    @JvmStatic
    fun assignShotSequence(entity: Entity, sequence: Long) {
        (entity as? SequencedProjectile)?.setProjectileShotSequence(sequence.coerceAtLeast(0L))
    }

    @JvmStatic
    fun shotSequence(entity: Entity): Long {
        return (entity as? SequencedProjectile)?.getProjectileShotSequence()?.coerceAtLeast(0L) ?: 0L
    }

    @JvmStatic
    fun profileId(entity: Entity): ResourceLocation? {
        return (entity as? ProfiledProjectile)?.getProjectileProfileId()
    }

    @JvmStatic
    fun resolve(entity: Entity): ResolvedProjectileProfile? {
        return (entity as? ProfiledProjectile)?.getResolvedProjectileProfile()
    }

    /** Returns the immutable opt-in guided propulsion descriptor carried by this projectile. */
    @JvmStatic
    fun guidedPropulsion(entity: Entity): GuidedPropulsionProfile? = resolve(entity)?.guidedPropulsion

    @JvmStatic
    fun resolve(id: ResourceLocation?): ResolvedProjectileProfile? {
        if (id == null) return null
        while (true) {
            val generation = cacheGeneration.get()
            resolvedProfiles[id]?.takeIf { it.generation == generation }?.let { return it.profile }

            val resolved = resolveUncached(id)
            if (cacheGeneration.get() != generation) continue
            if (resolved == null) return null

            val cached = CachedProfile(generation, resolved)
            resolvedProfiles[id] = cached
            if (cacheGeneration.get() == generation) return resolved
            resolvedProfiles.remove(id, cached)
        }
    }

    /** Reloads affect future assignments while in-flight projectiles retain their immutable snapshot. */
    @JvmStatic
    fun invalidateCache() {
        cacheGeneration.incrementAndGet()
        resolvedProfiles.clear()
    }

    /** Records the exact post-setPos server launch point immediately before entity insertion. */
    @JvmStatic
    fun recordServerLaunchPosition(entity: Entity, position: Vec3?) {
        if (entity.level().isClientSide || !finite(position)) {
            serverLaunchPositions.remove(entity)
            return
        }
        serverLaunchPositions[entity] = Vec3(position!!.x, position.y, position.z)
    }

    /** Removes provenance when a prepared entity never enters the level. */
    @JvmStatic
    fun clearServerLaunchPosition(entity: Entity) {
        serverLaunchPositions.remove(entity)
    }

    /**
     * Returns the authoritative launch-to-impact distance in metres/blocks.  The impact point
     * must be the server collision point supplied by the accepted impact context; shooter,
     * camera, and client positions are intentionally never consulted.
     */
    @JvmStatic
    fun launchToImpactTravelRangeMetres(entity: Entity, impactPosition: Vec3?): Double? {
        if (entity.level().isClientSide || !finite(impactPosition)) return null
        val launchPosition = serverLaunchPositions[entity] ?: return null
        val dx = impactPosition!!.x - launchPosition.x
        val dy = impactPosition.y - launchPosition.y
        val dz = impactPosition.z - launchPosition.z
        val distance = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        return distance.takeIf { it.isFinite() && it >= 0.0 }
    }

    private fun resolveUncached(id: ResourceLocation): ResolvedProjectileProfile? {
        val data = CustomData.PROJECTILE_PROFILE[id.toString()] ?: return null

        val combatData = data.combat
        val combat = if (combatData == null) {
            null
        } else {
            completeCombatDescriptor(id, combatData) ?: return null
        }
        val collision = data.collision?.let {
            if (it.width > 0f && it.height > 0f) {
                ResolvedProjectileCollision(it.width, it.height)
            } else {
                null
            }
        }

        return ResolvedProjectileProfile(
            id,
            combat,
            data.visualProfile,
            data.impactVisualProfile,
            data.motionSync,
            collision,
            data.luminance.coerceIn(0, 15),
            data.trailMode,
            data.renderScale.takeIf { it > 0f } ?: 1f,
            data.extensions,
            data.guidedPropulsion
                ?.takeIf { supportsGuidedMunition(combat?.munitionType) }
                ?.let(GuidedPropulsionProfile::from),
        )
    }

    /** Guided propulsion is opt-in for typed guided/ATGM munitions only. */
    private fun supportsGuidedMunition(munitionType: ResourceLocation?): Boolean {
        val path = munitionType?.path?.lowercase() ?: return false
        return path == "atgm" || path == "guided"
    }

    /**
     * Validates raw datapack fields before publishing an immutable armor descriptor.  Nullable
     * fields remain on the DTO solely so malformed JSON can be rejected cleanly at this boundary;
     * a usable ProjectileCombatDescriptor is never constructed without the complete tuple.
     */
    private fun completeCombatDescriptor(
        profileId: ResourceLocation,
        data: com.atsuishio.superbwarfare.data.projectile.ProjectileCombatData,
    ): ProjectileCombatDescriptor? {
        val hullDamageClass = data.hullDamageClass ?: return null
        val hullDamage = data.hullDamage ?: return null
        val moduleDamage = data.moduleDamage ?: return null
        val ammoRackDamage = data.ammoRackDamage ?: return null
        if (hullDamage < 0 || moduleDamage < 0 || ammoRackDamage < 0) return null

        val caliber = data.caliberMm?.takeIf { it.isFinite() && it > 0.0 }
        if (data.caliberMm != null && caliber == null) return null
        val diameter = data.diameterMm?.takeIf { it.isFinite() && it > 0.0 }
        if (data.diameterMm != null && diameter == null) return null
        if (caliber == null && diameter == null) return null

        return ProjectileCombatDescriptor(
            profileId,
            data.weaponId,
            data.roundId,
            data.munitionType,
            data.damageType,
            caliber,
            data.penetrationMm,
            data.tandem,
            data.penetrationCurve?.takeIf { curve -> curve.isValid() },
            data.ricochetCurve?.takeIf { curve -> curve.isValid() },
            diameter,
            hullDamageClass,
            hullDamage,
            moduleDamage,
            ammoRackDamage,
        )
    }

    @JvmStatic
    fun copy(source: Entity, target: Entity) {
        val sourceProfile = source as? ProfiledProjectile ?: return
        val targetProfile = target as? ProfiledProjectile ?: return
        targetProfile.copyProjectileProfileFrom(sourceProfile)
        val sourceSequence = (source as? SequencedProjectile)?.getProjectileShotSequence() ?: 0L
        (target as? SequencedProjectile)?.setProjectileShotSequence(sourceSequence)
    }

    /**
     * Assigns an immutable presentation clone of an existing bullet profile. Combat and collision
     * stay on the template; the clone changes only render scale, clears impact classification, and
     * rewrites the tracer extension to the fixed white impact presentation. This lets
     * server-created fragments use the normal SBW/BVP tracer path without a generated profile.
     */
    @JvmStatic
    fun assignImpactTracerProfile(
        entity: Entity,
        templateId: ResourceLocation?,
        renderScale: Float,
    ): Boolean {
        val target = entity as? ProfiledProjectile ?: return false
        val id = templateId ?: return false
        if (!renderScale.isFinite() || renderScale <= 0f) return false
        val template = resolve(id) ?: return false
        val impactExtensions = impactTracerExtensions(template) ?: return false
        val snapshot = ResolvedProjectileProfile(
            template.id,
            template.combat,
            template.visualProfileId,
            null,
            template.motionSync,
            template.collision,
            template.luminance,
            template.trailMode,
            renderScale,
            impactExtensions,
            template.guidedPropulsion,
        )
        target.copyProjectileProfileFrom(SnapshotProfileSource(snapshot.id, snapshot))
        return true
    }

    /**
     * Impact fragments use the normal immutable profile snapshot, but their presentation is
     * intentionally independent of the source weapon's tracer color.  Only this impact-specific
     * assignment rewrites the extension; ordinary projectiles retain their authored profiles.
     * The existing snapshot encoder carries the result to the client without a wire/schema change.
     */
    private fun impactTracerExtensions(template: ResolvedProjectileProfile): JsonObject? {
        val extensions = template.extensions()
        val tracer = extensions.get(TRACER_V2_EXTENSION_ID)
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: extensions.get(PROJECTILE_EFFECT_V1_EXTENSION_ID)
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.get("Trail")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.get("Tracer")
                ?.takeIf { it.isJsonObject }
                ?.deepCopy()
                ?.asJsonObject
                ?.also { extensions.add(TRACER_V2_EXTENSION_ID, it) }
            ?: return null
        tracer.add("ColorRgb", JsonArray().apply {
            add(255)
            add(255)
            add(255)
        })
        tracer.addProperty("Opacity", 1.0)
        return extensions
    }

    @JvmStatic
    fun combatDescriptor(entity: Entity): ProjectileCombatDescriptor? {
        return resolve(entity)?.combat
    }

    /**
     * Returns true only for a typed BVP projectile fired by an entity mounted on an SBW vehicle.
     * This is the single block-mutation gate used by the projectile/explosion paths.  A profile
     * namespace is the stable domain marker; owner topology prevents player-fired BVP rounds
     * outside a vehicle from being treated as vehicle ammunition.  Missing profile metadata does
     * not opt an untyped SBW projectile into the BVP policy.
     *
     * Collision, impact FX, entity/armor damage, and explosion damage remain independent: callers
     * use this predicate only to select KEEP instead of DESTROY for terrain blocks.
     */
    @JvmStatic
    fun suppressesVehicleBlockDamage(entity: Entity): Boolean {
        val projectile = entity as? Projectile ?: return false
        val owner = projectile.owner ?: return false
        if (owner.getRootVehicle() !is VehicleEntity) return false

        val profiled = entity as? ProfiledProjectile ?: return false
        val id = profiled.getProjectileProfileId()
        val combatWeaponId = profiled.getResolvedProjectileProfile()?.combat?.weaponId
        return id?.namespace == "berts_vehicle_pack"
            || combatWeaponId?.namespace == "berts_vehicle_pack"
    }

    /**
     * Stable typed identity for the KPVT cyclic component rounds.  This is deliberately based
     * on the authored Combat.RoundId namespace/prefix, never a display name, weapon path, or
     * vehicle ID.  It also lets runtime impact presentation recognize older generated profiles
     * whose belt Family field predates the KPVT enum.
     */
    @JvmStatic
    fun isKpvtRoundId(roundId: ResourceLocation?): Boolean {
        return roundId != null
                && roundId.namespace == "berts_vehicle_pack"
                && roundId.path.lowercase().startsWith("kpvt_")
    }

    @JvmStatic
    fun isKpvtProjectile(entity: Entity): Boolean {
        return isKpvtRoundId(combatDescriptor(entity)?.roundId)
    }

    /** Stable typed round identities whose non-tracer ammunition receives the small white cue. */
    @JvmStatic
    fun isSmallWhiteTracerRoundId(roundId: ResourceLocation?): Boolean {
        return roundId != null && SMALL_WHITE_TRACER_ROUNDS.contains(roundId)
    }

    /** Authoritative impact-facing scalar/curve resolver at the projectile's finite travel range. */
    @JvmStatic
    fun penetrationMm(entity: Entity, travelRangeMetres: Double): Double? {
        return resolve(entity)?.combat?.penetrationAtTravelRange(travelRangeMetres)
    }

    private fun finite(position: Vec3?): Boolean {
        return position != null && position.x.isFinite() && position.y.isFinite() && position.z.isFinite()
    }

    @JvmStatic
    fun motionSyncMode(
        projectile: ProfiledProjectile,
        legacy: FastThrowableProjectile.MotionSyncMode,
    ): FastThrowableProjectile.MotionSyncMode {
        return when (projectile.getResolvedProjectileProfile()?.motionSync ?: MotionSyncPolicy.INHERIT) {
            MotionSyncPolicy.INHERIT -> legacy
            MotionSyncPolicy.NONE -> FastThrowableProjectile.MotionSyncMode.NONE
            MotionSyncPolicy.ENTITY_INTERVAL -> FastThrowableProjectile.MotionSyncMode.ENTITY_INTERVAL
            MotionSyncPolicy.EVERY_TICK -> FastThrowableProjectile.MotionSyncMode.EVERY_TICK
        }
    }

    @JvmStatic
    fun writeSpawnData(projectile: ProfiledProjectile, buffer: FriendlyByteBuf) {
        val id = projectile.getProjectileProfileId()
        buffer.writeBoolean(id != null)
        if (id != null) {
            buffer.writeResourceLocation(id)
        }

        val snapshot = projectile.getResolvedProjectileProfile()
        buffer.writeBoolean(snapshot != null)
        if (snapshot != null) {
            buffer.writeNbt(encodeSnapshot(snapshot))
        }
        val shotSequence = (projectile as? SequencedProjectile)
            ?.getProjectileShotSequence()
            ?.coerceAtLeast(0L)
            ?: 0L
        buffer.writeVarLong(shotSequence)
    }

    @JvmStatic
    fun readSpawnData(projectile: ProfiledProjectile, buffer: FriendlyByteBuf) {
        if (!buffer.isReadable) return
        val id = if (buffer.readBoolean()) {
            if (!buffer.isReadable) return
            runCatching { buffer.readResourceLocation() }.getOrNull() ?: return
        } else {
            null
        }

        // Legacy packets end after the id. New packets append a versioned immutable snapshot.
        val snapshot = if (buffer.isReadable && buffer.readBoolean() && buffer.isReadable) {
            runCatching { buffer.readNbt() }
                .getOrNull()
                ?.let { decodeSnapshot(it, id) }
        } else {
            null
        }
        applyDecodedProfile(projectile, id, snapshot)
        if (buffer.isReadable) {
            runCatching { buffer.readVarLong() }
                .getOrNull()
                ?.let { sequence ->
                    (projectile as? SequencedProjectile)
                        ?.setProjectileShotSequence(sequence.coerceAtLeast(0L))
                }
        }
    }

    @JvmStatic
    fun writeAdditionalSaveData(projectile: ProfiledProjectile, compound: CompoundTag) {
        projectile.getProjectileProfileId()?.let {
            compound.putString(PROFILE_TAG, it.toString())
        }
        projectile.getResolvedProjectileProfile()?.let {
            compound.put(PROFILE_SNAPSHOT_TAG, encodeSnapshot(it))
        }
        val shotSequence = (projectile as? SequencedProjectile)?.getProjectileShotSequence() ?: 0L
        if (shotSequence > 0L) {
            compound.putLong(SHOT_SEQUENCE_TAG, shotSequence)
        }
    }

    @JvmStatic
    fun readAdditionalSaveData(projectile: ProfiledProjectile, compound: CompoundTag) {
        val hasLegacyId = compound.contains(PROFILE_TAG, Tag.TAG_STRING.toInt())
        val id = compound.getString(PROFILE_TAG)
            .takeIf { hasLegacyId }
            ?.let { ResourceLocation.tryParse(it) }
        val snapshot = if (compound.contains(PROFILE_SNAPSHOT_TAG, Tag.TAG_COMPOUND.toInt())) {
            decodeSnapshot(compound.getCompound(PROFILE_SNAPSHOT_TAG), id)
        } else {
            null
        }

        // Legacy saves contain only PROFILE_TAG and continue resolving against current data.
        if (hasLegacyId || snapshot != null) {
            applyDecodedProfile(projectile, id, snapshot)
        }
        val sequenced = projectile as? SequencedProjectile
        if (sequenced != null && compound.contains(SHOT_SEQUENCE_TAG, Tag.TAG_LONG.toInt())) {
            sequenced.setProjectileShotSequence(compound.getLong(SHOT_SEQUENCE_TAG).coerceAtLeast(0L))
        }
    }

    private fun applyDecodedProfile(
        projectile: ProfiledProjectile,
        legacyId: ResourceLocation?,
        snapshot: ResolvedProjectileProfile?,
    ) {
        if (snapshot == null) {
            projectile.setProjectileProfileId(legacyId)
            return
        }
        projectile.copyProjectileProfileFrom(SnapshotProfileSource(snapshot.id, snapshot))
    }

    private fun encodeSnapshot(profile: ResolvedProjectileProfile): CompoundTag {
        return CompoundTag().apply {
            putInt("Version", PROFILE_SNAPSHOT_VERSION)
            putString("Id", profile.id.toString())

            profile.combat?.let { combat ->
                put("Combat", CompoundTag().apply {
                    putString("ProfileId", combat.profileId.toString())
                    combat.weaponId?.let { putString("WeaponId", it.toString()) }
                    combat.roundId?.let { putString("RoundId", it.toString()) }
                    combat.munitionType?.let { putString("MunitionType", it.toString()) }
                    combat.damageType?.let { putString("DamageType", it.toString()) }
                    combat.caliberMm?.let { putDouble("CaliberMm", it) }
                    combat.diameterMm?.let { putDouble("DiameterMm", it) }
                    combat.hullDamageClass?.let { putString("HullDamageClass", it.name) }
                    combat.hullDamage?.let { putInt("HullDamage", it) }
                    combat.moduleDamage?.let { putInt("ModuleDamage", it) }
                    combat.ammoRackDamage?.let { putInt("AmmoRackDamage", it) }
                    combat.penetrationMm?.let { putDouble("PenetrationMm", it) }
                    combat.penetrationCurve?.takeIf { it.isValid() }?.let { curve ->
                        put("PenetrationCurve", CompoundTag().apply {
                            put("DistancesMetres", doubleListTag(curve.distancesMetres))
                            put("PenetrationMm", doubleListTag(curve.penetrationMm))
                        })
                    }
                    combat.ricochetCurve?.takeIf { it.isValid() }?.let { curve ->
                        put("RicochetCurve", CompoundTag().apply {
                            put("IncidenceAnglesDegrees", doubleListTag(curve.incidenceAnglesDegrees))
                            put("Probability", doubleListTag(curve.probability))
                        })
                    }
                    putBoolean("Tandem", combat.tandem)
                })
            }

            profile.visualProfileId?.let { putString("VisualProfileId", it.toString()) }
            profile.impactVisualProfileId?.let { putString("ImpactVisualProfileId", it.toString()) }
            putString("MotionSync", profile.motionSync.name)
            profile.collision?.let { collision ->
                put("Collision", CompoundTag().apply {
                    putFloat("Width", collision.width)
                    putFloat("Height", collision.height)
                })
            }
            putInt("Luminance", profile.luminance)
            putString("TrailMode", profile.trailMode.name)
            putFloat("RenderScale", profile.renderScale)
            profile.guidedPropulsion?.let { propulsion ->
                put("GuidedPropulsion", CompoundTag().apply {
                    putDouble("InitialSpeed", propulsion.initialSpeed)
                    putDouble("MaxSpeed", propulsion.maxSpeed)
                    putDouble("AccelerationPerTick", propulsion.accelerationPerTick)
                    putInt("ThrustDurationTicks", propulsion.thrustDurationTicks)
                    putDouble("MaxTurnRateDegreesPerSecond", propulsion.maxTurnRateDegreesPerSecond)
                    putInt("GuidanceLookAheadTicks", propulsion.guidanceLookAheadTicks)
                })
            }
            putString("Extensions", profile.extensions().toString())
        }
    }

    private fun decodeSnapshot(
        tag: CompoundTag,
        expectedId: ResourceLocation?,
    ): ResolvedProjectileProfile? {
        if (!tag.contains("Version", Tag.TAG_INT.toInt()) ||
            tag.getInt("Version") != PROFILE_SNAPSHOT_VERSION
        ) {
            return null
        }

        val id = readResourceLocation(tag, "Id") ?: return null
        if (expectedId != null && id != expectedId) return null

        val combat = if (tag.contains("Combat", Tag.TAG_COMPOUND.toInt())) {
            val combatTag = tag.getCompound("Combat")
            val damageClass = readRequiredEnum<ProjectileHullDamageClass>(combatTag, "HullDamageClass")
                ?: return null
            val hullDamage = readNonNegativeInt(combatTag, "HullDamage") ?: return null
            val moduleDamage = readNonNegativeInt(combatTag, "ModuleDamage") ?: return null
            val ammoRackDamage = readNonNegativeInt(combatTag, "AmmoRackDamage") ?: return null
            val hasCaliber = combatTag.contains("CaliberMm")
            val hasDiameter = combatTag.contains("DiameterMm")
            val caliber = readFiniteDouble(combatTag, "CaliberMm")
            val diameter = readFiniteDouble(combatTag, "DiameterMm")
            if ((hasCaliber && caliber == null) || (hasDiameter && diameter == null) ||
                (caliber != null && caliber <= 0.0) || (diameter != null && diameter <= 0.0) ||
                (caliber == null && diameter == null)
            ) return null
            ProjectileCombatDescriptor(
                readResourceLocation(combatTag, "ProfileId") ?: id,
                readResourceLocation(combatTag, "WeaponId"),
                readResourceLocation(combatTag, "RoundId"),
                readResourceLocation(combatTag, "MunitionType"),
                readResourceLocation(combatTag, "DamageType"),
                caliber,
                readFiniteDouble(combatTag, "PenetrationMm"),
                combatTag.contains("Tandem", Tag.TAG_BYTE.toInt()) && combatTag.getBoolean("Tandem"),
                readPenetrationCurve(combatTag),
                readRicochetCurve(combatTag),
                diameter,
                damageClass,
                hullDamage,
                moduleDamage,
                ammoRackDamage,
            )
        } else {
            null
        }

        val collision = if (tag.contains("Collision", Tag.TAG_COMPOUND.toInt())) {
            val collisionTag = tag.getCompound("Collision")
            val width = collisionTag.getFloat("Width")
            val height = collisionTag.getFloat("Height")
            if (collisionTag.contains("Width", Tag.TAG_FLOAT.toInt()) &&
                collisionTag.contains("Height", Tag.TAG_FLOAT.toInt()) &&
                width.isFinite() && height.isFinite() && width > 0f && height > 0f
            ) {
                ResolvedProjectileCollision(width, height)
            } else {
                null
            }
        } else {
            null
        }

        val luminance = if (tag.contains("Luminance", Tag.TAG_INT.toInt())) {
            tag.getInt("Luminance").coerceIn(0, 15)
        } else {
            0
        }
        val renderScale = tag.getFloat("RenderScale")
            .takeIf { tag.contains("RenderScale", Tag.TAG_FLOAT.toInt()) && it.isFinite() && it > 0f }
            ?: 1f

        return ResolvedProjectileProfile(
            id,
            combat,
            readResourceLocation(tag, "VisualProfileId"),
            readResourceLocation(tag, "ImpactVisualProfileId"),
            readEnum(tag, "MotionSync", MotionSyncPolicy.INHERIT),
            collision,
            luminance,
            readEnum(tag, "TrailMode", ProjectileTrailMode.DEFAULT),
            renderScale,
            readExtensions(tag),
            readGuidedPropulsion(tag, combat),
        )
    }

    private fun readResourceLocation(tag: CompoundTag, key: String): ResourceLocation? {
        if (!tag.contains(key, Tag.TAG_STRING.toInt())) return null
        return ResourceLocation.tryParse(tag.getString(key))
    }

    private fun readFiniteDouble(tag: CompoundTag, key: String): Double? {
        if (!tag.contains(key, Tag.TAG_DOUBLE.toInt())) return null
        return tag.getDouble(key).takeIf { it.isFinite() }
    }

    private fun readNonNegativeInt(tag: CompoundTag, key: String): Int? {
        if (!tag.contains(key, Tag.TAG_INT.toInt())) return null
        return tag.getInt(key).takeIf { it >= 0 }
    }

    private fun readPenetrationCurve(tag: CompoundTag): PenetrationCurve? {
        if (!tag.contains("PenetrationCurve", Tag.TAG_COMPOUND.toInt())) return null
        val curveTag = tag.getCompound("PenetrationCurve")
        val distances = readDoubleList(curveTag, "DistancesMetres") ?: return null
        val penetration = readDoubleList(curveTag, "PenetrationMm") ?: return null
        if (distances.size != penetration.size) return null
        return PenetrationCurve(
            distances,
            penetration,
        ).takeIf { it.isValid() }
    }

    private fun readRicochetCurve(tag: CompoundTag): RicochetCurve? {
        if (!tag.contains("RicochetCurve", Tag.TAG_COMPOUND.toInt())) return null
        val curveTag = tag.getCompound("RicochetCurve")
        val angles = readDoubleList(curveTag, "IncidenceAnglesDegrees") ?: return null
        val probability = readDoubleList(curveTag, "Probability") ?: return null
        if (angles.size != probability.size) return null
        return RicochetCurve(angles, probability).takeIf { it.isValid() }
    }

    private fun readGuidedPropulsion(
        tag: CompoundTag,
        combat: ProjectileCombatDescriptor?,
    ): GuidedPropulsionProfile? {
        if (!supportsGuidedMunition(combat?.munitionType) ||
            !tag.contains("GuidedPropulsion", Tag.TAG_COMPOUND.toInt())
        ) return null
        val propulsion = tag.getCompound("GuidedPropulsion")
        val initial = readFiniteDouble(propulsion, "InitialSpeed") ?: return null
        val maximum = readFiniteDouble(propulsion, "MaxSpeed") ?: return null
        val acceleration = readFiniteDouble(propulsion, "AccelerationPerTick") ?: return null
        if (!propulsion.contains("ThrustDurationTicks", Tag.TAG_INT.toInt())) return null
        val duration = propulsion.getInt("ThrustDurationTicks")
        val turnRate = readFiniteDouble(propulsion, "MaxTurnRateDegreesPerSecond") ?: return null
        if (!propulsion.contains("GuidanceLookAheadTicks", Tag.TAG_INT.toInt())) return null
        val lookAhead = propulsion.getInt("GuidanceLookAheadTicks")
        return GuidedPropulsionProfile(initial, maximum, acceleration, duration, turnRate, lookAhead)
            .takeIf { it.isValid() }
    }

    /** Minecraft NBT has no double-array tag; keep curve samples as an exact bounded TAG_DOUBLE list. */
    private fun doubleListTag(values: List<Double>): ListTag {
        return ListTag().also { list ->
            values.forEach { list.add(DoubleTag.valueOf(it)) }
        }
    }

    /** Reads only homogeneous TAG_DOUBLE lists; malformed data falls back to scalar penetration. */
    private fun readDoubleList(tag: CompoundTag, key: String): List<Double>? {
        if (!tag.contains(key, Tag.TAG_LIST.toInt())) return null
        val list = tag.getList(key, Tag.TAG_DOUBLE.toInt())
        if (list.size !in 1..32) return null
        val values = ArrayList<Double>(list.size)
        for (index in 0 until list.size) {
            val value = (list[index] as? DoubleTag)?.asDouble ?: return null
            if (!value.isFinite() || value < 0.0) return null
            values += value
        }
        return values
    }

    private inline fun <reified E : Enum<E>> readEnum(tag: CompoundTag, key: String, fallback: E): E {
        if (!tag.contains(key, Tag.TAG_STRING.toInt())) return fallback
        val name = tag.getString(key)
        return enumValues<E>().firstOrNull { it.name == name } ?: fallback
    }

    private inline fun <reified E : Enum<E>> readRequiredEnum(tag: CompoundTag, key: String): E? {
        if (!tag.contains(key, Tag.TAG_STRING.toInt())) return null
        val name = tag.getString(key)
        return enumValues<E>().firstOrNull { it.name == name }
    }

    private fun readExtensions(tag: CompoundTag): JsonObject {
        if (!tag.contains("Extensions", Tag.TAG_STRING.toInt())) return JsonObject()
        return runCatching { JsonParser.parseString(tag.getString("Extensions")) }
            .getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: JsonObject()
    }
}
