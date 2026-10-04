package com.atsuishio.superbwarfare.api.vehicle.deck

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.phys.Vec3
import java.util.WeakHashMap

/**
 * Whatever stands on a deck moves and turns with it. The owner calls [afterOwnerMoved] once per tick after its
 * own movement (server and client). The change of pose since the previous call (client corrections included) is
 * applied to every rider, by the side that simulates it:
 * - the server carries mobs, items and vehicles (all server-simulated),
 * - the client carries its own player (players move themselves), through [localPlayerHook].
 *
 * A rider is anything whose feet are on a deck column top, or (living things) up to [LIVING_ABOVE] blocks above
 * one, so a jump on a moving deck stays in its frame. Riders are moved by the turned local position and keep their
 * deck-relative velocity; the local player gets the deck's velocity when stepping off and loses it stepping on.
 */
object DeckCarry {
    const val MAX_STEP = 16.0
    const val BELOW = 0.4
    const val LIVING_ABOVE = 4.0
    const val VEHICLE_ABOVE = 1.25

    fun interface LocalCarry {
        fun carry(owner: Entity, from: DeckPose, to: DeckPose, surface: DeckSurface)
    }

    /** Set on the physical client (DeckClientCarry); never referenced by server code paths. */
    @Volatile @JvmStatic var localPlayerHook: LocalCarry? = null

    private val serverPoses = WeakHashMap<Entity, DeckPose>()
    private val clientPoses = WeakHashMap<Entity, DeckPose>()

    @JvmStatic
    fun afterOwnerMoved(owner: Entity) {
        val surface = (owner as? DeckSurfaceEntity)?.deckSurface() ?: return
        val now = DeckPose.of(owner)
        val client = owner.level().isClientSide
        val poses = if (client) clientPoses else serverPoses
        val before = synchronized(poses) { poses.put(owner, now) } ?: return
        if (before.sameAs(now)) return
        val dx = now.x - before.x
        val dz = now.z - before.z
        if (dx * dx + dz * dz > MAX_STEP * MAX_STEP || kotlin.math.abs(now.y - before.y) > MAX_STEP) return
        if (client) localPlayerHook?.carry(owner, before, now, surface)
        else carryServer(owner, before, now, surface)
    }

    @JvmStatic
    fun forget(owner: Entity) {
        synchronized(serverPoses) { serverPoses.remove(owner) }
        synchronized(clientPoses) { clientPoses.remove(owner) }
    }

    private fun carryServer(owner: Entity, from: DeckPose, to: DeckPose, surface: DeckSurface) {
        val region = from.envelope(surface).inflate(1.0, LIVING_ABOVE + 1.0, 1.0)
        val riders = owner.level().getEntities(owner, region) { e ->
            e.vehicle == null && e !is Player && e !is Projectile && e !is DeckSurfaceEntity &&
                e.isAlive && !e.noPhysics && !DeckCollisions.ignores(owner, e)
        }
        for (rider in riders) {
            if (isOnDeck(rider, from, surface)) move(rider, from, to)
        }
    }

    @JvmStatic
    fun isOnDeck(entity: Entity, pose: DeckPose, surface: DeckSurface): Boolean {
        val above = if (entity is VehicleEntity) VEHICLE_ABOVE else LIVING_ABOVE
        return !DeckCollisions.supportTop(entity, pose, surface, BELOW, above).isNaN()
    }

    /** Moves and turns [entity] with the deck from [from] to [to]; returns the displacement. */
    @JvmStatic
    fun move(entity: Entity, from: DeckPose, to: DeckPose): Vec3 {
        val lx = from.localX(entity.x, entity.z)
        val lz = from.localZ(entity.x, entity.z)
        val nx = to.worldX(lx, lz)
        val nz = to.worldZ(lx, lz)
        val ny = entity.y + (to.y - from.y)
        val moved = Vec3(nx - entity.x, ny - entity.y, nz - entity.z)
        entity.setPos(nx, ny, nz)
        val turn = Mth.wrapDegrees(to.yaw - from.yaw)
        if (turn != 0f) {
            entity.yRot = entity.yRot + turn
            if (entity is LivingEntity) {
                entity.yHeadRot += turn
                entity.yBodyRot += turn
            }
        }
        return moved
    }
}
