package com.atsuishio.superbwarfare.diagnostics

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy
import com.atsuishio.superbwarfare.api.projectile.FarProjectileAccess
import com.atsuishio.superbwarfare.api.vehicle.render.FarTerrainServer
import com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleIndex
import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.NominalProjectileMotion
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.data.gun.ShootParameters
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.tools.OBB
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.EntityJoinLevelEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.nio.file.Files
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/** Real factory shots and collision diagnostics. Run only after personal testing explicitly resumes. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID)
object FarProjectileTestScenario {
    private const val X = 4104
    private const val Z = 6152
    private const val Y = 220
    private const val DISTANCE = 512
    private val identity = UUID.nameUUIDFromBytes("OfflinePlayer:BvpDiagnostics".toByteArray(Charsets.UTF_8))
    private var active: Run? = null

    private fun allowed(player: ServerPlayer): Boolean {
        val server = player.server
        return DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios") &&
            server.isDedicatedServer && !server.usesAuthentication() && server.localIp == "127.0.0.1" &&
            server.port == 25579 && server.playerCount == 1 && player.gameProfile.name == "BvpDiagnostics" &&
            player.uuid == identity && player.abilities.instabuild && !player.isSpectator &&
            player.level().dimension() == Level.OVERWORLD &&
            Files.isRegularFile(server.getWorldPath(LevelResource.ROOT).resolve("sbw-disposable-tests.marker"))
    }

    @SubscribeEvent fun register(event: RegisterCommandsEvent) {
        val command = Commands.literal("sbw_far_projectile_test").requires { it.hasPermission(2) }
        for (kind in listOf("cannon", "bullet", "rocket", "aircraft_cannon", "aircraft_tracer", "aircraft_tracer_remote", "aircraft_tracer_pause")) {
            val branch = Commands.literal(kind)
            for (cover in listOf("clear", "wall", "denied")) branch.then(Commands.literal(cover).executes {
                start(it.source.playerOrException, kind, cover == "wall", cover == "denied")
            })
            if (kind == "bullet") branch.then(Commands.literal("aircraft").executes {
                start(it.source.playerOrException, kind, false, false, true)
            })
            command.then(branch)
        }
        command.then(Commands.literal("cancel").executes {
            val player = it.source.playerOrException
            if (!allowed(player) || active?.player !== player) return@executes 0
            active?.finish("CANCELLED"); 1
        })
        event.dispatcher.register(command)
    }

    @JvmStatic @JvmOverloads fun start(player: ServerPlayer, kind: String, wall: Boolean, denied: Boolean = false,
                                    aircraft: Boolean = false): Int {
        if (!allowed(player) || kind !in listOf("cannon", "bullet", "rocket", "aircraft_cannon", "aircraft_tracer", "aircraft_tracer_remote", "aircraft_tracer_pause")) return 0
        if (active != null || EliteDiagnostics.isServerEnabled()) {
            player.sendSystemMessage(Component.literal("FAR_PROJECTILE refused: another fixture or capture is active")); return 0
        }
        if (FarTerrainServer.radius(player) < DISTANCE + 24) {
            player.sendSystemMessage(Component.literal("FAR_PROJECTILE requires far radius>=536 (client/server view distance>=12); settings unchanged")); return 0
        }
        val run = Run(player, kind, wall, denied, aircraft)
        active = run
        try { run.prepare() } catch (failure: Exception) { run.finish("PREPARE_FAILED: ${failure.message}") }
        return if (active === run) 1 else 0
    }

    @SubscribeEvent fun tick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val run = active ?: return
        try { run.tick() } catch (failure: Exception) { run.finish("FAILED: ${failure.message}") }
    }

    @SubscribeEvent fun joined(event: EntityJoinLevelEvent) { active?.joined(event) }

    @SubscribeEvent fun stopping(event: ServerStoppingEvent) {
        active?.let { it.finish("SERVER_STOPPING"); it.cleanup(force = true) }
    }

    private class Run(val player: ServerPlayer, val kind: String, val wall: Boolean, val denied: Boolean,
                      val aircraft: Boolean) {
        val level = player.serverLevel()
        private val oldPosition = player.position()
        private val oldYaw = player.yRot
        private val oldPitch = player.xRot
        private val oldFlying = player.abilities.flying
        private val oldVelocity = player.deltaMovement
        private val saved = linkedMapOf<BlockPos, BlockState>()
        private val owned = mutableListOf<Entity>()
        private val shots = mutableListOf<Projectile>()
        private lateinit var target: VehicleEntity
        private lateinit var weapon: VehicleEntity
        private var capture = false
        private var ticks = 0
        private var firedAt = -1
        private var terminalTicks = 0
        private var forcedPauseAt = -1
        private var hullBefore = 0f
        private var farSamples = 0
        private val diskLoaded = mutableSetOf<UUID>()
        private var preconditionState = ""
        private var finishReason: String? = null
        private var cleanupTicks = 0
        private var cleanupPending = mutableListOf<Entity>()
        private var terminalHull: Float? = null
        private var terminalTargetRemoved: Boolean? = null
        private val muzzle = Vec3(X + .5, Y + 1.25, Z + .5)

        private fun record(event: String, vararg fields: Any?) =
            EliteDiagnostics.record(player, "far_projectile_test", event, "kind", kind, "wall", wall, "denied", denied,
                "target_aircraft", aircraft, *fields)

        fun joined(event: EntityJoinLevelEvent) {
            val entity = event.entity
            if (entity.level() !== level || owned.none { it.uuid == entity.uuid }) return
            if (event.loadedFromDisk()) diskLoaded += entity.uuid
            record("OWNED_ENTITY_LOADED", "entity", entity.uuid, "from_disk", event.loadedFromDisk(),
                "age", entity.tickCount, "position", entity.position())
        }

        private fun resolveVehicle(previous: VehicleEntity, role: String): VehicleEntity? {
            val current = level.getEntity(previous.uuid) as? VehicleEntity ?: return null
            check("sbw_far_projectile_test" in current.tags) { "Owned $role UUID lost fixture tag" }
            if (current.isRemoved) return null
            if (current !== previous) {
                owned += current
                record("OWNED_ENTITY_REBOUND", "role", role, "entity", current.uuid,
                    "from_disk_observed", current.uuid in diskLoaded, "age", current.tickCount,
                    "previous_removed", previous.isRemoved, "previous_removal_reason", previous.removalReason)
            }
            return current
        }

        private fun vehicle(id: String): VehicleEntity {
            val type = BuiltInRegistries.ENTITY_TYPE.getOptional(
                if (':' in id) ResourceLocation(id) else ResourceLocation("superbwarfare", id)).orElseThrow()
            return type.create(level) as? VehicleEntity ?: error("$id is not a vehicle")
        }

        private fun put(pos: BlockPos, state: BlockState) {
            check(level.getBlockEntity(pos) == null) { "Fixture refuses block entities at $pos" }
            saved.putIfAbsent(pos.immutable(), level.getBlockState(pos))
            check(level.setBlock(pos, state, 3) || level.getBlockState(pos) == state)
        }

        fun prepare() {
            player.sendSystemMessage(Component.literal(EliteDiagnostics.startForEntities(player.server, setOf(player.uuid))))
            capture = EliteDiagnostics.isServerEnabled()
            check(capture) { "Diagnostic capture unavailable" }
            // Only three explicit fixture chunks. Traversal must use the real far terrain lease.
            for (offset in listOf(0, 384, DISTANCE)) level.getChunk(X shr 4, (Z + offset) shr 4)
            check(!level.isPositionEntityTicking(BlockPos(X, Y, Z + DISTANCE))) { "Target is in native simulation" }
            for (offset in listOf(0, DISTANCE)) for (x in -5..5) for (z in -5..5)
                put(BlockPos(X + x, Y - 1, Z + offset + z), Blocks.BEDROCK.defaultBlockState())
            if (wall) for (x in -5..5) for (y in -1..32)
                put(BlockPos(X + x, Y + y, Z + 384), Blocks.BEDROCK.defaultBlockState())
            val prototype = vehicle(if (aircraft) "berts_vehicle_pack:mig19" else "t_90a")
            check(!aircraft || prototype.isFixedWingFlightVehicle()) { "Aircraft wake test requires the fixed-wing provider" }
            prototype.moveTo(X + .5, Y.toDouble(), Z + DISTANCE + .5, 180f, 0f)
            prototype.setNoGravity(true)
            val serialized = CompoundTag()
            check(prototype.save(serialized)) { "Cannot serialize target" }
            target = EntityType.loadEntityRecursive(serialized, level) { it } as? VehicleEntity
                ?: error("Cannot deserialize target")
            target.addTag("sbw_far_projectile_test")
            owned += target
            EliteDiagnostics.includeServerEntity(target.uuid)
            check(level.addFreshEntity(target)) { "Target rejected" }
            weapon = vehicle(when (kind) {
                "rocket" -> "ah_6"
                "aircraft_cannon", "aircraft_tracer", "aircraft_tracer_remote", "aircraft_tracer_pause" -> "berts_vehicle_pack:mig19"
                else -> "t_90a"
            })
            weapon.moveTo(X + 4.5, Y.toDouble(), Z + .5, 0f, 0f)
            weapon.setNoGravity(true)
            weapon.addTag("sbw_far_projectile_test")
            owned += weapon
            EliteDiagnostics.includeServerEntity(weapon.uuid)
            check(level.addFreshEntity(weapon)) { "Weapon rejected" }
            player.teleportTo(level, X + .5, Y.toDouble(), Z - 1.5, 0f, 0f)
            player.deltaMovement = Vec3.ZERO
            player.abilities.flying = true; player.onUpdateAbilities()
            record("PREPARED", "target", target.uuid, "weapon", weapon.uuid, "distance", DISTANCE,
                "cold_method", "NBT_RECREATED_FULL", "target_age", target.tickCount,
                "native_ticking", level.isPositionEntityTicking(target.blockPosition()),
                "target_position", target.position(), "muzzle", muzzle)
        }

        fun tick() {
            if (finishReason != null) { cleanup(); return }
            ticks++
            if (!allowed(player) || player.serverLevel() !== level) { finish("GATE_LOST"); return }
            if (ticks > 1800) { finish("TIMEOUT"); return }
            if (firedAt < 0) {
                // A transient preparation load may unload before the normal far lease reaches it.
                // Never warm-tick/replace the UUID to compensate; accept its genuine resident reload.
                val previousAge = target.tickCount
                val current = resolveVehicle(target, "target")
                if (current != null) target = current
                val currentWeapon = resolveVehicle(weapon, "weapon")
                if (currentWeapon != null) weapon = currentWeapon
                val chunk = target.chunkPosition()
                val full = level.chunkSource.getChunkNow(chunk.x, chunk.z) != null
                val entitiesLoaded = level.areEntitiesLoaded(chunk.toLong())
                val leased = FarTerrainServer.ownsTerrainTicket(level, chunk.toLong())
                val nativeTicking = level.isPositionEntityTicking(target.blockPosition())
                val ready = current != null && FarTerrainServer.ready(player, target)
                val state = listOf(target.isRemoved, previousAge, target.tickCount, nativeTicking,
                    full, entitiesLoaded, leased, current != null, currentWeapon != null, ready).joinToString()
                if (state != preconditionState || ticks % 100 == 0) {
                    preconditionState = state
                    record("PREFIRE_PRECONDITIONS", "tick", ticks, "target", target.uuid,
                        "target_removed", target.isRemoved, "removal_reason", target.removalReason,
                        "previous_age", previousAge, "target_age", target.tickCount, "native_ticking", nativeTicking,
                        "full", full, "entities_loaded", entitiesLoaded, "terrain_ticket", leased,
                        "target_accessible", current != null, "weapon_accessible", currentWeapon != null,
                        "terrain_ready", ready, "from_disk_observed", target.uuid in diskLoaded)
                }
                check(previousAge == 0 && target.tickCount == 0) { "Cold target ticked before first shot" }
                check(!nativeTicking) { "Cold target chunk entered native simulation before first shot" }
                if (!full || !entitiesLoaded || !leased || current == null || currentWeapon == null || !ready) return
                // The original cannon case retains its immediate-after-teleport cold-origin test.
                // The tracer case represents a shooter already resident in normal simulation;
                // await ordinary player loading only, without loading any traversal/target chunks.
                if (kind.startsWith("aircraft_tracer")) {
                    val origin = weapon.chunkPosition()
                    val originReady = level.isPositionEntityTicking(weapon.blockPosition()) &&
                        (-2..2).all { dx -> (-2..2).all { dz ->
                            val x = origin.x + dx; val z = origin.z + dz
                            level.chunkSource.getChunkNow(x, z) != null &&
                                level.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(x, z))
                        } }
                    if (!originReady) return
                }
                fire(); return
            }
            for (shot in shots) {
                if (kind == "aircraft_tracer_pause" && !shot.isRemoved) {
                    if (forcedPauseAt < 0 && shot.tickCount >= 4) {
                        forcedPauseAt = ticks
                        shot.canUpdate(false)
                        record("FORCED_PAUSE", "projectile", shot.uuid)
                    } else if (forcedPauseAt >= 0 && ticks == forcedPauseAt + 20) {
                        shot.canUpdate(true)
                        record("FORCED_RESUME", "projectile", shot.uuid)
                    }
                }
                val far = !level.isPositionEntityTicking(shot.blockPosition())
                if (!shot.isRemoved && far && shot.position().distanceToSqr(muzzle) > 128.0 * 128.0) farSamples++
                record("SHOT_STATE", "projectile", shot.uuid, "position", shot.position(), "age", shot.tickCount,
                    "flight_elapsed_ticks", ticks - firedAt, "simulation_lag_ticks", ticks - firedAt - shot.tickCount,
                    "velocity", shot.deltaMovement, "native_ticking", !far, "removed", shot.isRemoved,
                    "removal_reason", shot.removalReason, "hull", target.health, "target_removed", target.isRemoved,
                    "target_age", target.tickCount, "target_native_ticking", level.isPositionEntityTicking(target.blockPosition()))
            }
            if (shots.all { it.isRemoved }) terminalTicks++
            if (terminalTicks >= (if (aircraft) 20 else 5) || ticks - firedAt > 2500) finish("OBSERVED")
        }

        private fun fire() {
            check(!target.isRemoved && target.tickCount == 0 && level.getEntity(target.uuid) === target &&
                !level.isPositionEntityTicking(target.blockPosition())) { "Cold target changed at first-shot boundary" }
            val body = target.getOBBs().firstOrNull { it.part == OBB.Part.BODY } ?: error("No BODY OBB")
            val aim = OBB.vector3dToVec3(body.center)
            check(aim.distanceToSqr(target.position()) < 100) { "Cold target OBB was not initialized in world space" }
            record("FIRST_SHOT_GEOMETRY", "target", target.uuid, "target_age", target.tickCount,
                "cold_method", if (target.uuid in diskLoaded) "DISK_RELOADED_FULL" else "NBT_RECREATED_FULL",
                "position", target.position(), "body_center", aim, "obb_count", target.getOBBs().size,
                "full", level.chunkSource.getChunkNow(target.chunkPosition().x, target.chunkPosition().z) != null,
                "native_ticking", level.isPositionEntityTicking(target.blockPosition()))
            val name = when (kind) { "cannon", "aircraft_cannon", "aircraft_tracer", "aircraft_tracer_remote", "aircraft_tracer_pause" -> "Cannon"; "bullet" -> "MachineGun"; else -> "Rocket" }
            weapon.modifyGunData(name) { data ->
                data.resetStatus(); data.reload.setPendingProgressPercent(0); data.ammo.set(10)
                data.selectedAmmoType.set(0)
                // Actual fourth NR30 Air Belt round (HEFI-T), not a forced tracer flag/profile.
                data.projectileBeltPhase.set(if (kind.startsWith("aircraft_tracer")) 3 else 0)
                // Native magazine-less weapons consume reserve ammo, not the magazine field above.
                data.virtualAmmo.set(if (data.useBackpackAmmo()) data.get(GunProp.AMMO_COST_PER_SHOOT) else 0)
                data.heat.set(0.0); data.overHeat.set(false)
            }
            val data = weapon.getGunData(name) ?: error("Missing native weapon $name")
            val belt = data.resolveProjectileBelt()
            check(belt.status != com.atsuishio.superbwarfare.data.gun.ProjectileBeltResolutionStatus.INVALID) {
                "Invalid authored belt: ${belt.failure}"
            }
            record("WEAPON_READINESS", "weapon", weapon.uuid, "weapon_name", name,
                "uses_reserve_ammo", data.useBackpackAmmo(), "magazine", data.get(GunProp.MAGAZINE),
                "magazine_ammo", data.ammo.get(), "virtual_ammo", data.virtualAmmo.get(),
                "available_ammo", data.currentAvailableAmmo(weapon), "ammo_cost", data.get(GunProp.AMMO_COST_PER_SHOOT),
                "projectile_amount", data.get(GunProp.PROJECTILE_AMOUNT), "reloading", data.reloading(),
                "charging", data.charging(), "bolt_needed", data.bolt.needed.get(),
                "overheated", data.overHeat.get(), "heat", data.heat.get(), "can_shoot", data.canShoot(weapon))
            val projectileData = belt.projectileData ?: data
            val speed = NominalProjectileMotion.launchSpeed(projectileData, level, muzzle)
            val gravity = projectileData.get(GunProp.GRAVITY).toFloat().toDouble()
            val direction = solveAim(aim, speed, gravity)
            hullBefore = target.health
            val result = data.shootWithResult(ShootParameters(weapon, player, level, muzzle, direction,
                data, 0.0, true, null, null, projectileData = belt.projectileData,
                projectileBeltTracer = belt.round?.tracer))
            record("WEAPON_AFTER_SHOT", "weapon", weapon.uuid, "weapon_name", name,
                "accepted", result.isAccepted(), "rejection_reason", result.reason,
                "magazine_ammo", data.ammo.get(), "virtual_ammo", data.virtualAmmo.get(),
                "available_ammo", data.currentAvailableAmmo(weapon))
            check(result.isAccepted()) { "Native gun factory rejected: ${result.reason}" }
            for (id in result.spawnedProjectileIds) {
                val shot = level.getEntity(id) as? Projectile ?: error("Factory projectile unavailable: $id")
                owned += shot; shots += shot; EliteDiagnostics.includeServerEntity(id)
                // Diagnostic denial exercises the unconditional production expiry sweep while
                // preserving the actual factory projectile, native lifetime and persisted deadline.
                if (denied) { shot.canUpdate(false); record("UPDATE_DENIED", "projectile", id, "game_time", level.gameTime) }
                record("SHOT", "projectile", id, "type", BuiltInRegistries.ENTITY_TYPE.getKey(shot.type),
                    "position", shot.position(), "velocity", shot.deltaMovement, "aim", aim,
                    "target", target.uuid, "hull_before", hullBefore,
                    "lifetime", (shot as? FarProjectileAccess)?.farProjectileLifetimeTicks())
                check(shot.position().distanceToSqr(muzzle) < .0025 && shot.deltaMovement.normalize().dot(direction) > .999) {
                    "Actual launch differs from planned native factory launch"
                }
            }
            if (kind == "aircraft_tracer_remote") {
                // Move the observer through the normal player teleport/tracker path immediately
                // after the real shot; buffered diagnostic writes cannot time this transition.
                player.teleportTo(level, muzzle.x + 400, muzzle.y + 4, muzzle.z + 256, 90f, 0f)
                player.deltaMovement = Vec3.ZERO
                record("REMOTE_OBSERVER", "position", player.position(), "muzzle", muzzle)
            }
            check(shots.size == data.get(GunProp.PROJECTILE_AMOUNT)) {
                "Factory count differs from authored projectile amount: ${shots.size}"
            }
            firedAt = ticks
        }

        /** Selects the short native discrete trajectory; never changes spawned motion or lifetime. */
        private fun solveAim(aim: Vec3, speed: Double, gravity: Double): Vec3 {
            check(speed.isFinite() && speed > 0 && gravity.isFinite() && gravity >= 0)
            val flat = Vec3(aim.x - muzzle.x, 0.0, aim.z - muzzle.z).normalize()
            val range = aim.subtract(muzzle).horizontalDistance()
            fun direction(angle: Double) = flat.scale(cos(angle)).add(0.0, sin(angle), 0.0)
            fun arrival(angle: Double): Double {
                var position = Vec3.ZERO
                var motion = direction(angle).scale(speed)
                repeat(2400) {
                    val next = position.add(motion)
                    if (next.horizontalDistance() >= range) {
                        val t = (range - position.horizontalDistance()) / (next.horizontalDistance() - position.horizontalDistance())
                        return muzzle.y + position.y + (next.y - position.y) * t
                    }
                    position = next
                    motion = if (kind == "bullet") NominalProjectileMotion.afterStep(motion, gravity)
                        else NominalProjectileMotion.afterFastThrowableAirStep(motion, gravity)
                }
                error("Native trajectory cannot reach fixture")
            }
            var low = -.4; var high = .7
            check(arrival(low) < aim.y && arrival(high) > aim.y) { "Target outside bounded low trajectory" }
            repeat(40) { val mid = (low + high) / 2; if (arrival(mid) < aim.y) low = mid else high = mid }
            return direction((low + high) / 2)
        }

        fun finish(reason: String) {
            if (active !== this) return
            if (finishReason != null) {
                record("CLEANUP_FAILED", "reason", reason)
                cleanup(force = true)
                return
            }
            finishReason = reason
            terminalHull = if (::target.isInitialized) target.health else null
            terminalTargetRemoved = if (::target.isInitialized) target.isRemoved else null
            cleanupPending = owned.distinctBy { it.uuid }.toMutableList()
            record("CLEANUP_STARTED", "reason", reason, "owned_entities", cleanupPending.size)
            cleanup()
        }

        /** Only known fixture chunks, at most200 ticks; never retains a permanent ticket. */
        fun cleanup(force: Boolean = false) {
            if (active !== this || finishReason == null) return
            cleanupTicks++
            val iterator = cleanupPending.iterator()
            while (iterator.hasNext()) {
                val previous = iterator.next()
                var current = level.getEntity(previous.uuid)
                if (current == null && !previous.isRemoved) current = previous
                if (current == null && previous.removalReason?.shouldDestroy() != true) {
                    if (force) continue
                    val chunk = previous.chunkPosition()
                    level.getChunk(chunk.x, chunk.z)
                    current = level.getEntity(previous.uuid)
                    if (current == null && (cleanupTicks < 3 || !level.areEntitiesLoaded(chunk.toLong()) ||
                            previous is VehicleEntity && FarVehicleIndex.get(level).positions.containsKey(previous.uuid))) continue
                }
                if (current != null) {
                    if (current is VehicleEntity && "sbw_far_projectile_test" !in current.tags) {
                        record("CLEANUP_REFUSED", "entity", current.uuid, "reason", "fixture_tag_missing")
                        continue
                    }
                    if (!current.isRemoved) current.discard()
                }
                iterator.remove()
            }
            if (cleanupPending.isNotEmpty() && cleanupTicks < 200 && !force) return
            active = null
            val reason = finishReason!!
            record("FINISHED", "reason", reason, "ticks", ticks, "shots", shots.size, "far_samples", farSamples,
                "hull_before", hullBefore, "hull_after", terminalHull, "target_removed", terminalTargetRemoved,
                "wall_unchanged_hull", if (wall && firedAt >= 0) terminalHull == hullBefore else null,
                "cleanup_complete", cleanupPending.isEmpty(), "cleanup_ticks", cleanupTicks,
                "cleanup_pending_uuids", cleanupPending.map { it.uuid }.joinToString(","),
                "review_required", "Verify far_projectile SIMULATED + native impact/contact/dispatch and wall block event; OBSERVED is not PASS")
            try {
                saved.forEach { (pos, state) -> level.setBlock(pos, state, 3) }
                player.teleportTo(level, oldPosition.x, oldPosition.y, oldPosition.z, oldYaw, oldPitch)
                player.deltaMovement = oldVelocity
                player.abilities.flying = oldFlying; player.onUpdateAbilities()
            } finally {
                if (capture) player.sendSystemMessage(Component.literal(EliteDiagnostics.stop(player.server)))
                player.sendSystemMessage(Component.literal("FAR_PROJECTILE finished=$reason; inspect capture for actual impact and traversal; far_samples=$farSamples; cleanup_pending=${cleanupPending.map { it.uuid }}"))
            }
        }
    }
}
