package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.entity.projectile.WireGuideMissileEntity
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.EntityJoinLevelEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import java.util.UUID
import java.util.WeakHashMap

/**
 * Missile approach warning for every vehicle: whether a missile is homing on it and which decoy would break the
 * lock. Sampled after the missiles tick; [AircraftCountermeasures] synchronizes the flags to the crew
 * ([AircraftCountermeasureWire.incoming]), whose client sounds the alarm and draws the INCOMING cue.
 *
 * Native SBW missiles name their target, and any decoy diverts them (flares, or a ground vehicle's smoke), except
 * wire-guided ones. FFA missiles are recorded at launch with their seeker and the locked target, and count only while
 * they still fly toward it, so one a decoy has pulled away stops the warning.
 */
@EventBusSubscriber(modid = com.atsuishio.superbwarfare.Mod.MODID)
object IncomingMissileWarning {
    /** A missile is homing on the vehicle. */
    const val INCOMING = 1
    /** A native seeker, which any decoy diverts: flares, or a ground vehicle's smoke. */
    const val DECOY = 2
    /** An FFA infrared seeker: flares break it. */
    const val FLARE = 4
    /** An FFA radar seeker: chaff breaks it. */
    const val CHAFF = 8

    /** An FFA missile still flying this long after launch is no longer tracked. */
    private const val TRACK_LIMIT_TICKS = 2400L
    /** cos 60 degrees: a homing missile flies toward its target or its lead point, a diverted one turns away. */
    private const val HEADING_COS = 0.5
    private const val CLOSE_RANGE_SQR = 16.0 * 16.0

    private class ExternalTrack(val flags: Int, val target: UUID, val launchedAt: Long) {
        var last: Vec3? = null
    }

    private class Sample(var flags: Int, var at: Long)

    private val external = WeakHashMap<Entity, ExternalTrack>()
    private val samples = WeakHashMap<Entity, Sample>()
    private val capture = ThreadLocal<MutableList<Entity>?>()

    /** The warning flags for [vehicle] from the latest sample; 0 when nothing homes on it. */
    @JvmStatic
    fun flags(vehicle: VehicleEntity): Int {
        val sample = samples[vehicle] ?: return 0
        return if (sample.at >= vehicle.level().gameTime - 1) sample.flags else 0
    }

    /** The decoy that breaks an FFA seeker [mode] (see [AircraftMissileLauncher.guidance]); none for anti-radiation. */
    private fun seeker(mode: String): Int = when (mode) {
        "INFRARED", "GROUND_INFRARED" -> FLARE
        "ACTIVE_RADAR", "SEMI_ACTIVE_RADAR", "ACTIVE_SURFACE_RADAR" -> CHAFF
        else -> 0
    }

    /**
     * Runs [action] (a synchronous FFA launch) and records the missile it spawned as homing on the locked [target]
     * with an FFA seeker [mode]. Without a locked target nothing is recorded.
     */
    @JvmStatic
    fun <T> launch(level: Level, mode: String, target: UUID?, action: () -> T): T {
        if (level.isClientSide || target == null) return action()
        val previous = capture.get()
        val spawned = ArrayList<Entity>(2)
        capture.set(spawned)
        try {
            return action()
        } finally {
            capture.set(previous)
            val missile = spawned.firstOrNull { it is Projectile } ?: spawned.firstOrNull()
            if (missile != null && missile.isAlive)
                external[missile] = ExternalTrack(INCOMING or seeker(mode), target, level.gameTime)
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
    fun tick(event: TickEvent.LevelTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val level = event.level as? ServerLevel ?: return
        val now = level.gameTime
        AircraftNativeThreats.forEachHoming(level) { missile, target ->
            mark(target, now, if (missile is WireGuideMissileEntity) INCOMING else INCOMING or DECOY)
        }
        val tracks = external.entries.iterator()
        while (tracks.hasNext()) {
            val entry = tracks.next()
            val missile = entry.key
            val track = entry.value
            if (missile.level() !== level) continue
            if (missile.isRemoved || !missile.isAlive || now - track.launchedAt > TRACK_LIMIT_TICKS) {
                tracks.remove()
                continue
            }
            val position = missile.position()
            val last = track.last
            track.last = position
            val target = level.getEntity(track.target)?.takeIf { it.isAlive && !it.isRemoved } ?: continue
            if (homingOn(position, last, target)) mark(target, now, track.flags)
        }
    }

    private fun homingOn(position: Vec3, last: Vec3?, target: Entity): Boolean {
        val toTarget = target.boundingBox.center.subtract(position)
        if (toTarget.lengthSqr() < CLOSE_RANGE_SQR) return true
        // FFA may move its missiles without a vanilla velocity, so the heading is the last tick's displacement.
        val velocity = if (last == null) Vec3.ZERO else position.subtract(last)
        if (velocity.lengthSqr() < 1.0E-4) return true
        return velocity.dot(toTarget) >= HEADING_COS * velocity.length() * toTarget.length()
    }

    /** Adds [flags] to this tick's sample for the vehicle [target] is (or rides). */
    private fun mark(target: Entity, now: Long, flags: Int) {
        var current: Entity? = target
        while (current != null && current !is VehicleEntity) current = current.vehicle
        val vehicle = current as? VehicleEntity ?: return
        val sample = samples.getOrPut(vehicle) { Sample(0, now) }
        if (sample.at != now) {
            sample.flags = 0
            sample.at = now
        }
        sample.flags = sample.flags or flags
    }

    @SubscribeEvent
    fun stopped(event: ServerStoppedEvent) {
        external.clear()
        samples.clear()
    }
}
