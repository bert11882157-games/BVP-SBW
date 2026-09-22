package com.atsuishio.superbwarfare.api.vehicle.weapon.prediction

import com.atsuishio.superbwarfare.data.gun.GunData
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import java.lang.ref.WeakReference

/**
 * One-entry reuse for duplicate client CCIP samples inside the same simulation tick.
 *
 * There is intentionally no cross-tick reuse: Level exposes no complete block/fluid mutation
 * epoch, so a prior tick's collision result cannot be proven current. The weak level reference
 * avoids retaining a disconnected world when no later CCIP sample arrives to clear the entry.
 */
internal object ClientNominalCollisionResultCache {
    private const val DETECT_LIQUID = true
    private const val REJECT_CONTEXT_SENSITIVE = true

    internal data class GunState(
        val selectedAmmoType: Int,
        val selectedFireMode: Int,
        val ammo: Int,
        val virtualAmmo: Int,
        val fireIndex: Int,
        val heatBits: Long,
        val overHeat: Boolean,
        val shootTimer: Int,
        val reloadState: Int,
        val reloadTime: Int,
        val reloadStage: Int,
        val prepareTime: Int,
        val prepareLoadTime: Int,
        val iterativeLoadTime: Int,
        val finishTime: Int,
        val pendingProgressPercent: Int,
        val reloadStartPending: Boolean,
        val singleReloadStartPending: Boolean,
        val stageThreeStartPending: Boolean,
    )

    private data class Key(
        val dimension: ResourceKey<Level>,
        val gameTime: Long,
        val snapshot: NominalShotSnapshot,
        val gunState: GunState,
        val detectLiquid: Boolean,
        val rejectContextSensitive: Boolean,
    )

    private data class Entry(
        val levelIdentity: Int,
        val level: WeakReference<Level>,
        val key: Key,
        val result: NominalShotResult,
    )

    private var entry: Entry? = null

    fun captureGunState(data: GunData): GunState = GunState(
        data.selectedAmmoType.get(),
        data.selectedFireMode.get(),
        data.ammo.get(),
        data.virtualAmmo.get(),
        data.fireIndex.get(),
        data.heat.get().toRawBits(),
        data.overHeat.get(),
        data.shootTimer.get(),
        data.reload.state().ordinal,
        data.reload.time(),
        data.reload.stage(),
        data.reload.prepareTimer.get(),
        data.reload.prepareLoadTimer.get(),
        data.reload.iterativeLoadTimer.get(),
        data.reload.finishTimer.get(),
        data.reload.pendingProgressPercent(),
        data.reload.reloadStarter.shouldStart(),
        data.reload.singleReloadStarter.shouldStart(),
        data.reload.stage3Starter.shouldStart(),
    )

    fun lookup(
        level: Level,
        snapshot: NominalShotSnapshot,
        gunState: GunState,
    ): NominalShotResult? {
        val cached = entry ?: return null
        val levelIdentity = System.identityHashCode(level)
        if (cached.level.get() !== level ||
            cached.levelIdentity != levelIdentity ||
            cached.key.gameTime != level.gameTime
        ) {
            entry = null
            return null
        }
        val key = key(level, snapshot, gunState)
        return cached.result.takeIf { cached.key == key }
    }

    fun store(
        level: Level,
        snapshot: NominalShotSnapshot,
        gunState: GunState,
        result: NominalShotResult,
    ) {
        if (!cacheable(result)) {
            invalidate()
            return
        }
        entry = Entry(
            System.identityHashCode(level),
            WeakReference(level),
            key(level, snapshot, gunState),
            result,
        )
    }

    fun invalidate() {
        entry = null
    }

    private fun key(
        level: Level,
        snapshot: NominalShotSnapshot,
        gunState: GunState,
    ) = Key(
        level.dimension(),
        level.gameTime,
        snapshot,
        gunState,
        DETECT_LIQUID,
        REJECT_CONTEXT_SENSITIVE,
    )

    private fun cacheable(result: NominalShotResult): Boolean =
        result.status != NominalShotStatus.INVALID_CONTEXT &&
            result.diagnostic != NominalShotDiagnostic.FIRST_VEHICLE_COLLISION &&
        result.status != NominalShotStatus.NON_FINITE &&
            result.point?.let(::finite) != false &&
            result.direction?.let(::finite) != false &&
            result.timeOfFlightTicks?.isFinite() != false &&
            result.travelledDistanceBlocks.isFinite()

    private fun finite(vector: net.minecraft.world.phys.Vec3): Boolean =
        vector.x.isFinite() && vector.y.isFinite() && vector.z.isFinite()
}
