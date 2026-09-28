package com.atsuishio.superbwarfare.diagnostics

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.aircraft.AircraftArmamentManager
import com.atsuishio.superbwarfare.api.aircraft.AircraftArmamentRegistry
import com.atsuishio.superbwarfare.api.aircraft.AircraftBombLauncher
import com.atsuishio.superbwarfare.api.aircraft.AircraftBombTargeting
import com.atsuishio.superbwarfare.api.aircraft.AircraftDesignationData
import com.atsuishio.superbwarfare.api.aircraft.AircraftLaserLauncher
import com.atsuishio.superbwarfare.api.aircraft.AircraftMissileLauncher
import com.atsuishio.superbwarfare.api.aircraft.AircraftPylonRacks
import com.atsuishio.superbwarfare.api.aircraft.AircraftStoreWeapons
import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy
import com.atsuishio.superbwarfare.api.vehicle.flight.VehicleFlightInputContext
import com.atsuishio.superbwarfare.api.vehicle.flight.VehicleFlightStrategy
import com.atsuishio.superbwarfare.api.vehicle.flight.VehicleFlightTickResult
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.tools.angleTo
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import net.minecraftforge.registries.ForgeRegistries
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.IdentityHashMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Diagnostic launches only (`bvp.diagnostics.scenarios`): a moving two-sided war for performance work
 * (owner's brief, 2026-09-28).
 *
 * `/bvp_war <label> <small|medium|large|huge>` builds a flat pad and fights one war:
 *
 * | size   | planes | helicopters | ground | FPS goal |
 * |--------|--------|-------------|--------|----------|
 * | small  | 5      | 0           | 8      | 90       |
 * | medium | 5      | 3           | 12     | 60       |
 * | large  | 10     | 3           | 24     | 40       |
 * | huge   | 15     | 3           | 36     | 20       |
 *
 * Blue (NATO) and Red (Soviet / Chinese) ground vehicles face each other across an 80-block gap and drive
 * back and forth along their lines, crewed by passive mobs: the driver seat is steered by the harness and the
 * gunner seats aim through the vehicles' own mob-gunner turret aim at the nearest enemy (air-defence vehicles
 * at aircraft). Every weapon fires at its real cadence while its turret is on target. Tanks cycle their ammunition
 * types. Aircraft fly kinematic orbits over the enemy line (the flight strategy is the harness'; the vehicles
 * still move, collide and take damage). Every two seconds they release the next store: free-fall, laser (the
 * harness paints a moving enemy), GPS (a waypoint on one), TV (the seeker locks the painted vehicle), laser and
 * TV missiles, FFA air-to-air, air-to-ground and anti-radiation missiles, rocket pods, and gun pods. Helicopters
 * hover-orbit behind their own line facing the enemy and fire their guns, rockets and missiles. Destroyed
 * vehicles are counted, left as wrecks for 5 s and respawned at home.
 *
 * Phases (server ticks): `setup` 0-40 (the pad), `warmup` 40-300 (everything spawns and starts moving, no
 * fire), `war` 300-2300, `end`. Each phase start and end is logged as `BVP_WAR <label> <phase> START|END` for
 * the job's client captures. `logs/bvp-war/<label>.json` holds, per phase: tick wall/CPU times with the
 * harness' own share taken out, live projectile counts, entity counts by type, shots and releases by weapon and
 * by guidance, rejections, kills and hits. `/bvp_war_stop` ends a war early.
 */
@EventBusSubscriber(modid = Mod.MODID)
object WarDiagnostics {
    enum class Size(val planes: Int, val helis: Int, val ground: Int, val fpsGoal: Int) {
        SMALL(5, 0, 8, 90), MEDIUM(5, 3, 12, 60), LARGE(10, 3, 24, 40), HUGE(15, 3, 36, 20)
    }

    private val blueGround = listOf("m1a2_abrams_sep_v2", "m2_bradley", "gepard", "leopard_2a4", "lav25", "challenger_2",
        "cv9040_no_net", "marder_1a5", "leclerc_s1", "k2a1_black_panther", "m1_abrams_elite", "marder_1a2", "type_90",
        "m2_bradley", "gepard", "leopard_2a4", "lav25", "m1a2_abrams_sep_v2")
    private val redGround = listOf("t72b", "bmp2", "9k22_tunguska", "9p149_shturm", "t90a", "btr80a", "t80u_obr1985",
        "bmp3m_elite", "zsu23_4", "9p148", "ztz99a", "bmp2m", "zbd_09", "btr_90", "t72b3", "bmp_1am", "t90m", "bmd_1")
    private val blueJets = listOf("a_10", "f_16c", "fa_18e", "f_15e", "f_111f", "f_16b", "a_7d", "f_15c")
    private val redJets = listOf("su_25", "su_24", "su_35", "j_10a", "su_17", "j_11a", "mig_29", "su_39", "q_5", "j_15d")
    private val blueHelis = listOf("ah_64d", "eurocopter_tiger")
    private val redHelis = listOf("mi24v", "mi28n", "ka50")
    private val airDefence = setOf("gepard", "9k22_tunguska", "zsu23_4")

    private val schedule = listOf("war" to 300, "end" to 2300)
    private const val WARMUP = 40
    /** Half-width of the ground patrol lanes and the gap between the two lines. */
    private const val LANE_HALF = 16.0
    private const val FRONT = 40.0

    private var active: Run? = null
    /** The first war's pad; later wars of the same server session fight on it again (comparable scenes). */
    private var anchor: Vec3? = null
    private val threads = ManagementFactory.getThreadMXBean()

    /** The kinematic flight of a war aircraft (VehicleFlightController asks before its own strategy). */
    @JvmStatic fun flightStrategy(vehicle: VehicleEntity): VehicleFlightStrategy? {
        val run = active ?: return null
        if (vehicle.level().isClientSide || vehicle.isWreck) return null
        return run.flights[vehicle]
    }

    @SubscribeEvent fun commands(event: RegisterCommandsEvent) {
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) return
        event.dispatcher.register(Commands.literal("bvp_war").requires { it.hasPermission(2) }
            .then(Commands.argument("label", StringArgumentType.word())
                .then(Commands.argument("size", StringArgumentType.word()).executes { context ->
                    val label = StringArgumentType.getString(context, "label")
                    val size = runCatching { Size.valueOf(StringArgumentType.getString(context, "size").uppercase()) }
                        .getOrNull() ?: return@executes 0
                    val player = context.source.playerOrException
                    if (active != null || !label.matches(Regex("[A-Za-z0-9_-]{1,40}")) || player.vehicle != null ||
                        !player.server.isDedicatedServer || player.server.localIp != "127.0.0.1") return@executes 0
                    val run = Run(player, label, size)
                    active = run
                    try { run.prepare() } catch (failure: Exception) { run.finish("ERROR", failure.toString()) }
                    if (active != null) 1 else 0
                })))
        event.dispatcher.register(Commands.literal("bvp_war_stop").requires { it.hasPermission(2) }
            .executes { active?.finish("STOPPED", "Operator request"); 1 })
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST) fun tickStart(event: TickEvent.ServerTickEvent) {
        val run = active ?: return
        if (event.phase != TickEvent.Phase.START || event.server != run.player.server) return
        run.tickStartNanos = System.nanoTime()
        run.tickStartCpu = cpu()
        run.harnessNanos = 0L
    }

    @SubscribeEvent(priority = EventPriority.LOWEST) fun tickEnd(event: TickEvent.ServerTickEvent) {
        val run = active ?: return
        if (event.phase != TickEvent.Phase.END || event.server != run.player.server) return
        try { run.tick() } catch (failure: Exception) { run.finish("ERROR", failure.stackTraceToString().take(2000)) }
    }

    @SubscribeEvent fun stopping(event: ServerStoppingEvent) {
        if (active?.player?.server === event.server) active?.finish("STOPPED", "Server stopping")
    }

    private fun cpu(): Long = if (threads.isCurrentThreadCpuTimeSupported && threads.isThreadCpuTimeEnabled)
        threads.currentThreadCpuTime else -1L

    enum class Kind { GROUND, JET, HELI }

    /** Kinematic orbit: a circle (jets bank into it; helicopters keep facing [face]). */
    internal class WarFlight(val center: Vec3, val radius: Double, val speed: Double, val direction: Int,
                             val heli: Boolean, var face: Vec3?, start: Double) : VehicleFlightStrategy() {
        var angle = start
        override fun tickServer(vehicle: VehicleEntity, input: VehicleFlightInputContext): VehicleFlightTickResult {
            angle += direction * speed / radius
            val next = Vec3(center.x + radius * cos(angle), center.y, center.z + radius * sin(angle))
            var motion = next.subtract(vehicle.position())
            val limit = speed * 3.0
            if (motion.lengthSqr() > limit * limit) motion = motion.normalize().scale(limit)
            val tangent = Vec3(-sin(angle) * direction, 0.0, cos(angle) * direction)
            val look = if (heli) face?.subtract(vehicle.position())?.takeIf { it.horizontalDistanceSqr() > 1.0 } ?: tangent
                else tangent
            val yaw = Math.toDegrees(atan2(-look.x, look.z)).toFloat()
            val bank = if (heli) 0F
                else (-direction * Math.toDegrees(atan2(speed * speed, radius * 0.08))).toFloat().coerceIn(-55F, 55F)
            return VehicleFlightTickResult(motion, 1.0, 0.6, 0.8, 0.8, yaw, 0F, bank, true, 1.0)
        }
    }

    private class Phase(val name: String) {
        val wall = ArrayList<Double>()
        val cpu = ArrayList<Double>()
        val harness = ArrayList<Double>()
        val projectiles = ArrayList<Int>()
        val entitySamples = linkedMapOf<String, Long>()
        var entitySampleCount = 0
        var accepted = 0
        val rejected = linkedMapOf<String, Int>()
        val firedByWeapon = linkedMapOf<String, Int>()
        val releasesByGuidance = linkedMapOf<String, Int>()
        val liveVehicles = ArrayList<Int>()
        fun report(): Map<String, Any?> = linkedMapOf("name" to name, "ticks" to wall.size,
            "tickWallMs" to stats(wall), "tickCpuMs" to stats(cpu), "harnessMs" to stats(harness),
            "msptMean" to (if (wall.isEmpty()) 0.0 else wall.sum() / wall.size),
            "msptWithoutHarnessMean" to (if (wall.isEmpty()) 0.0 else (wall.sum() - harness.sum()) / wall.size),
            "projectiles" to linkedMapOf("mean" to (if (projectiles.isEmpty()) 0.0 else projectiles.average()),
                "peak" to (projectiles.maxOrNull() ?: 0)),
            "liveVehiclesMean" to (if (liveVehicles.isEmpty()) 0.0 else liveVehicles.average()),
            "entitiesByTypeMean" to entitySamples.entries.sortedByDescending { it.value }.take(30)
                .associate { it.key to it.value.toDouble() / maxOf(1, entitySampleCount) },
            "shotsAccepted" to accepted, "shotRejections" to rejected,
            "releasesByGuidance" to releasesByGuidance,
            "acceptedByWeapon" to firedByWeapon.entries.sortedByDescending { it.value }.associate { it.key to it.value })
    }

    private class Unit(val id: String, val team: Int, val kind: Kind, val home: Vec3, val slot: Int) {
        var vehicle: VehicleEntity? = null
        val crew = ArrayList<Mob>()
        var gunner: Mob? = null
        var respawnAt = 0
        var wreckedAt = -1
        var waypoint = 0
        var steerSign = 1
        var bestDistance = Double.MAX_VALUE
        var progressAt = 0
        var target: VehicleEntity? = null
        var aligned = false
        var releaseCursor = 0
        val credit = HashMap<String, Double>()
        val cadence = HashMap<String, Double>()
    }

    private class Run(val player: ServerPlayer, val label: String, val size: Size) {
        val server = player.server
        val level = player.serverLevel()
        val started = System.nanoTime()
        val startedUtc: String = Instant.now().toString()
        val origin = anchor ?: Vec3(kotlin.math.floor(player.x) + 8, kotlin.math.floor(player.y), kotlin.math.floor(player.z) + 8)
            .also { anchor = it }
        val units = ArrayList<Unit>()
        val byVehicle = IdentityHashMap<VehicleEntity, Unit>()
        val flights = IdentityHashMap<VehicleEntity, WarFlight>()
        val forced = LinkedHashSet<ChunkPos>()
        val phases = ArrayList<Phase>()
        val notes = ArrayList<String>()
        val kills = linkedMapOf<String, Int>()
        val hits = linkedMapOf<String, DoubleArray>()
        var phase: Phase? = null
        var age = 0
        var tickStartNanos = 0L
        var tickStartCpu = -1L
        var harnessNanos = 0L
        var finished = false

        fun prepare() {
            // pad x -130..130, z -170..170 around the origin; forced so both lines and the orbits keep ticking
            for (x in -130..130 step 16) for (z in -170..170 step 16) force(origin.x + x, origin.z + z)
            fill(-130, -1, -170, 130, -1, 170, Blocks.STONE.defaultBlockState())
            for (y0 in 0..24 step 6) fill(-130, y0, -170, 130, y0 + 5, 170, Blocks.AIR.defaultBlockState())
            player.stopRiding()
            // behind and above the blue line, looking north over the whole war
            player.teleportTo(level, origin.x, origin.y + 48, origin.z - 150, 0f, 16f)
            player.deltaMovement = Vec3.ZERO
            player.abilities.flying = true; player.abilities.invulnerable = true; player.onUpdateAbilities()
            log("setup", "READY", "size" to size.name.lowercase(), "fpsGoal" to size.fpsGoal)
        }

        fun fill(x0: Int, y0: Int, z0: Int, x1: Int, y1: Int, z1: Int, state: net.minecraft.world.level.block.state.BlockState) {
            val base = BlockPos.containing(origin)
            for (pos in BlockPos.betweenClosed(base.offset(x0, y0, z0), base.offset(x1, y1, z1)))
                if (level.getBlockState(pos) != state) level.setBlock(pos, state, 2)
        }

        fun force(x: Double, z: Double) {
            val pos = ChunkPos(kotlin.math.floor(x).toInt() shr 4, kotlin.math.floor(z).toInt() shr 4)
            if (level.setChunkForced(pos.x, pos.z, true)) forced.add(pos)
        }

        fun roster() {
            val perSide = size.ground / 2
            for (team in 0..1) {
                val pool = if (team == 0) blueGround else redGround
                val sign = if (team == 0) -1.0 else 1.0
                for (i in 0 until perSide) {
                    // up to six vehicles a row, 40 blocks apart; rows 14 blocks further back
                    val row = i / 6; val col = i % 6
                    val x = (col - 2.5) * 40.0 + (row % 2) * 12.0
                    val z = sign * (FRONT + row * 14.0)
                    units.add(Unit(pool[i % pool.size], team, Kind.GROUND, origin.add(x, 0.0, z), i))
                }
            }
            for (i in 0 until size.planes) {
                val team = i % 2
                val pool = if (team == 0) blueJets else redJets
                units.add(Unit(pool[(i / 2) % pool.size], team, Kind.JET, origin, i))
            }
            for (i in 0 until size.helis) {
                val team = i % 2
                val pool = if (team == 0) blueHelis else redHelis
                val sign = if (team == 0) -1.0 else 1.0
                units.add(Unit(pool[(i / 2) % pool.size], team, Kind.HELI,
                    origin.add((i - 1) * 45.0, 22.0, sign * (FRONT + 45.0)), i))
            }
        }

        fun spawn(unit: Unit) {
            val type = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation("berts_vehicle_pack", unit.id))
            val vehicle = type?.create(level) as? VehicleEntity
            if (vehicle == null) { notes.add("missing ${unit.id}"); unit.respawnAt = Int.MAX_VALUE; return }
            vehicle.load(CompoundTag())
            val facing = if (unit.team == 0) 0f else 180f
            var at = unit.home
            if (unit.kind == Kind.JET) {
                // orbits over the enemy line, each at its own height and radius
                val center = origin.add(0.0, 56.0 + unit.slot * 5.0, (if (unit.team == 0) 1.0 else -1.0) * (FRONT + 10.0))
                val radius = 70.0 + (unit.slot % 3) * 12.0
                val start = unit.slot * 1.3
                val flight = WarFlight(center, radius, 3.2 + (unit.slot % 3) * 0.4, if (unit.slot % 2 == 0) 1 else -1,
                    false, null, start)
                at = Vec3(center.x + radius * cos(start), center.y, center.z + radius * sin(start))
                flights[vehicle] = flight
                loadout(unit, vehicle)
            } else if (unit.kind == Kind.HELI) {
                val flight = WarFlight(unit.home, 14.0, 0.5, 1, true,
                    origin.add(0.0, 0.0, (if (unit.team == 0) 1.0 else -1.0) * FRONT), unit.slot * 2.0)
                at = Vec3(unit.home.x + 14.0 * cos(flight.angle), unit.home.y, unit.home.z + 14.0 * sin(flight.angle))
                flights[vehicle] = flight
                loadout(unit, vehicle)
            }
            vehicle.moveTo(at.x, at.y, at.z, facing, 0f)
            runCatching { vehicle.energy = vehicle.maxEnergy }
            vehicle.addTag("bvp_war_fixture")
            if (!level.addFreshEntity(vehicle)) { notes.add("insertion failed ${unit.id}"); unit.respawnAt = age + 200; return }
            unit.vehicle = vehicle; unit.wreckedAt = -1; unit.target = null
            byVehicle[vehicle] = unit
            if (unit.kind == Kind.GROUND) crew(unit, vehicle)
        }

        /** Passive mobs in the driver seat and every seat up to the turret and passenger-weapon controllers. */
        fun crew(unit: Unit, vehicle: VehicleEntity) {
            unit.crew.forEach { it.discard() }; unit.crew.clear()
            val seats = vehicle.computed().seats().size
            var needed = 0
            if (vehicle.hasTurret()) needed = maxOf(needed, vehicle.turretControllerIndex)
            if (vehicle.hasPassengerWeaponStation()) needed = maxOf(needed, vehicle.passengerWeaponStationControllerIndex)
            needed = needed.coerceIn(0, maxOf(0, seats - 1))
            for (i in 0..needed) {
                val cow = EntityType.COW.create(level) ?: break
                cow.setNoAi(true); cow.isInvulnerable = true; cow.isSilent = true
                cow.setPos(vehicle.position()); cow.addTag("bvp_war_fixture")
                if (!level.addFreshEntity(cow)) break
                if (!cow.startRiding(vehicle, true)) { cow.discard(); break }
                unit.crew.add(cow)
            }
            unit.gunner = (if (vehicle.hasTurret()) vehicle.getNthEntity(vehicle.turretControllerIndex) else null) as? Mob
                ?: unit.crew.firstOrNull()
        }

        /** A varied loadout: hardpoints take turns across the guidance kinds they allow. */
        fun loadout(unit: Unit, vehicle: VehicleEntity) {
            val definition = AircraftArmamentRegistry.aircraft[ResourceLocation("berts_vehicle_pack", unit.id)]
                ?: run { notes.add("no armament ${unit.id}"); return }
            val selections = CompoundTag(); val counts = CompoundTag()
            for ((mountIndex, mount) in AircraftArmamentRegistry.mounts(definition).withIndex()) {
                val options = mount.getAsJsonArray("AllowedStores")?.mapNotNull { value ->
                    val store = AircraftArmamentRegistry.stores[ResourceLocation(value.asString)] ?: return@mapNotNull null
                    val copies = AircraftPylonRacks.maxCopies(definition, mount, store)
                    if (copies < 1 || guidance(store) == "OTHER") null else Triple(value.asString, store, copies)
                } ?: continue
                if (options.isEmpty()) continue
                val kinds = options.map { guidance(it.second) }.distinct()
                val kind = kinds[(mountIndex + unit.slot) % kinds.size]
                val (id, _, copies) = options.first { guidance(it.second) == kind }
                selections.putString(mount["Id"].asString, id); counts.putInt(mount["Id"].asString, copies)
            }
            if (selections.isEmpty) { notes.add("no stores for ${unit.id}"); return }
            vehicle.persistentData.put("BvpAircraftArmament", CompoundTag().also {
                it.put("Selections", selections); it.put("Counts", counts)
            })
        }

        fun guidance(store: JsonObject): String {
            val category = store["Category"]?.asString ?: return "OTHER"
            return when (category) {
                "BOMB" -> "BOMB_" + (store.getAsJsonObject("Bomb")?.get("Mode")?.asString ?: "DUMB") +
                    (if (store.getAsJsonObject("Bomb")?.has("Cluster") == true) "_CLUSTER" else "")
                "LASER_GUIDED" -> "MISSILE_LASER"
                "COMMAND_GUIDED" -> "MISSILE_" + (store.getAsJsonObject("CommandGuidance")?.get("Mode")?.asString ?: "?")
                "AIR_TO_AIR", "AIR_TO_GROUND", "ANTI_RADIATION" ->
                    "FFA_" + (store.getAsJsonObject("Guidance")?.get("Mode")?.asString ?: category)
                "ROCKET_POD" -> "ROCKETS"
                "GUN_POD" -> "GUN_POD"
                else -> "OTHER"
            }.let { if (it == "MISSILE_MCLOS" || it == "MISSILE_SACLOS") "OTHER" else it }  // need a seated pilot
        }

        fun enemies(unit: Unit) = units.filter { it.team != unit.team && it.vehicle?.let { v -> !v.isRemoved && !v.isWreck } == true }

        /** Nearest enemy of the class this unit engages; re-chosen every second. */
        fun retarget(unit: Unit, vehicle: VehicleEntity) {
            val foes = enemies(unit)
            val air = unit.id in airDefence
            val preferred = when {
                unit.kind == Kind.JET -> foes.filter { it.kind == Kind.GROUND }
                air -> foes.filter { it.kind != Kind.GROUND }.ifEmpty { foes.filter { it.kind == Kind.GROUND } }
                unit.kind == Kind.HELI -> foes.filter { it.kind == Kind.GROUND }
                else -> foes.filter { it.kind != Kind.JET }
            }
            val best = preferred.minByOrNull { it.vehicle!!.distanceToSqr(vehicle) }?.vehicle
            unit.target = best
            if (unit.kind == Kind.GROUND && best != null) {
                vehicle.aiTurretTargetUUID = best.uuid.toString()
                if (vehicle.hasPassengerWeaponStation()) vehicle.aiPassengerWeaponTargetUUID = best.uuid.toString()
            }
            if (unit.kind == Kind.HELI) flights[vehicle]?.face = best?.position()
        }

        /** Lane patrol: drive to one end of the lane, turn, drive back. Steering sign self-calibrates. */
        fun drive(unit: Unit, vehicle: VehicleEntity, moving: Boolean) {
            val end = unit.home.add(if (unit.waypoint == 0) -LANE_HALF else LANE_HALF, 0.0, 0.0)
            val dx = end.x - vehicle.x; val dz = end.z - vehicle.z
            val distance = sqrt(dx * dx + dz * dz)
            if (distance < 5.0) { unit.waypoint = 1 - unit.waypoint; unit.bestDistance = Double.MAX_VALUE; unit.progressAt = age }
            if (distance < unit.bestDistance - 1.0) { unit.bestDistance = distance; unit.progressAt = age }
            else if (age - unit.progressAt > 160) {
                // no progress for 8 s: this vehicle steers the other way round (or is stuck): flip and retry
                unit.steerSign = -unit.steerSign; unit.progressAt = age; unit.bestDistance = distance
            }
            val want = Math.toDegrees(atan2(-dx, dz)).toFloat()
            val error = net.minecraft.util.Mth.wrapDegrees(want - vehicle.yRot) * unit.steerSign
            vehicle.forwardInputDown = moving && kotlin.math.abs(error) < 100f
            vehicle.backInputDown = false
            vehicle.leftInputDown = moving && error < -6f
            vehicle.rightInputDown = moving && error > 6f
            vehicle.upInputDown = false
            vehicle.sprintInputDown = false
        }

        fun weapons(vehicle: VehicleEntity): List<String> = vehicle.gunDataMap.keys.toList()

        fun perTick(unit: Unit, vehicle: VehicleEntity, name: String): Double = unit.cadence.getOrPut(name) {
            val data = vehicle.getGunData(name)
            if (data == null) 0.05 else {
                val rpm = data.get(GunProp.RPM).coerceAtLeast(1)
                val reload = maxOf(data.get(GunProp.EMPTY_RELOAD_TIME), data.get(GunProp.NORMAL_RELOAD_TIME))
                if (data.get(GunProp.MAGAZINE) <= 1 && reload > 0) minOf(rpm / 1200.0, 1.0 / reload) else rpm / 1200.0
            }
        }

        fun fireGround(unit: Unit, vehicle: VehicleEntity, current: Phase) {
            val target = unit.target?.takeIf { !it.isRemoved && !it.isWreck } ?: return
            val gunner = unit.gunner ?: return
            if (age % 2 == 0) {
                val from = vehicle.getShootPos(gunner, 1f)
                val to = target.boundingBox.center.subtract(from)
                unit.aligned = to.lengthSqr() < 400.0 * 400.0 &&
                    vehicle.getShootDirectionForHud(gunner, 1f).angleTo(to) < 5.0
            }
            if (!unit.aligned) return
            for (name in weapons(vehicle)) shoot(unit, vehicle, name, target, current)
        }

        fun shoot(unit: Unit, vehicle: VehicleEntity, name: String, target: VehicleEntity?, current: Phase) {
            var owed = unit.credit.getOrPut(name) { (unit.slot * 0.37 + name.length * 0.11) % 1.0 } + perTick(unit, vehicle, name)
            var shots = 0
            while (owed >= 1.0 && shots < 3) {
                owed -= 1.0; shots++
                val result = vehicle.vehicleShootResult(null, name, target?.uuid, null)
                if (result.isAccepted()) {
                    current.accepted++
                    current.firedByWeapon.merge("${unit.id}/$name", 1, Int::plus)
                } else current.rejected.merge(result.reason.toString(), 1, Int::plus)
            }
            unit.credit[name] = owed.coerceAtMost(3.0)
        }

        /** Jets and helicopters: the next loaded store every two seconds, guns and pods at their cadence. */
        fun fireAir(unit: Unit, vehicle: VehicleEntity, current: Phase) {
            val target = unit.target?.takeIf { !it.isRemoved && !it.isWreck }
            // guns: helicopters face their target; jets strafe only when the target is ahead
            if (target != null) {
                val ahead = target.position().subtract(vehicle.position())
                val nose = vehicle.lookAngle
                if (ahead.lengthSqr() < 260.0 * 260.0 && nose.angleTo(ahead) < (if (unit.kind == Kind.HELI) 25.0 else 12.0))
                    for (name in weapons(vehicle)) shoot(unit, vehicle, name, target, current)
            }
            if ((age + unit.slot * 7) % 40 != 0 || target == null) return
            val definition = AircraftArmamentManager.definition(vehicle) ?: return
            val selections = vehicle.persistentData.getCompound("BvpAircraftArmament").getCompound("Selections")
            val mounts = AircraftArmamentRegistry.mounts(definition).filter { selections.contains(it["Id"].asString) }
            if (mounts.isEmpty()) return
            // over the enemy for bombs; missiles from anywhere
            val pair = mounts[unit.releaseCursor++ % mounts.size]
            val id = selections.getString(pair["Id"].asString)
            val store = AircraftArmamentRegistry.stores[ResourceLocation.tryParse(id)] ?: return
            val kind = guidance(store)
            val mount = AircraftArmamentRegistry.mountPositions(pair).firstOrNull() ?: return
            val channel = AircraftStoreWeapons.PREFIX + pair["Id"].asString
            // paint the target for laser and TV weapons (a moving spot), and give GPS weapons a waypoint on it
            if (kind.contains("LASER") || kind.contains("TV") || kind == "MISSILE_TV")
                AircraftDesignationData.get(level).put(vehicle.uuid, target.boundingBox.center, target)
            if (kind == "BOMB_GPS") AircraftBombTargeting.setDiagnosticGpsTarget(vehicle, BlockPos.containing(target.position()))
            val ok = when {
                kind.startsWith("BOMB") -> AircraftBombLauncher.launch(vehicle, player, mount, store, null, Vec3(0.0, -2.5, 0.0))
                kind.startsWith("MISSILE") -> AircraftLaserLauncher.launch(vehicle, player, mount, store, channel)
                kind.startsWith("FFA") -> {
                    val origin = vehicle.position().add(0.0, -1.5, 0.0)
                    AircraftMissileLauncher.launchAt(vehicle, player, channel, origin,
                        target.boundingBox.center.subtract(origin).normalize(), store)
                }
                kind == "ROCKETS" || kind == "GUN_POD" -> {
                    val natives = AircraftArmamentManager.nativeWeapons(pair, id)
                    natives.forEach { shoot(unit, vehicle, it, target, current) }
                    natives.isNotEmpty()
                }
                else -> false
            }
            if (ok) {
                current.releasesByGuidance.merge(kind, 1, Int::plus)
                current.firedByWeapon.merge("${unit.id}/$id", 1, Int::plus)
            } else current.rejected.merge("RELEASE_FAILED_$kind", 1, Int::plus)
        }

        /** Top up ammunition that ran low (every 5 s per vehicle, staggered) and cycle tanks' ammunition types. */
        fun rearm(unit: Unit, vehicle: VehicleEntity) {
            if ((age + unit.slot * 13) % 100 != 0) return
            val cycle = (age / 100 + unit.slot) % 2 == 0
            for ((name, data) in vehicle.gunDataMap) {
                val magazine = data.get(GunProp.MAGAZINE).coerceAtLeast(1)
                val low = data.ammo.get() < magazine / 3 + 1 || data.overHeat.get()
                val consumers = data.get(GunProp.AMMO_CONSUMER).size
                if (!low && !(cycle && consumers > 1)) continue
                vehicle.modifyGunData(name) { d ->
                    if (low) {
                        d.resetStatus(); d.reload.setPendingProgressPercent(0)
                        d.ammo.set(maxOf(magazine, 30)); d.heat.set(0.0); d.overHeat.set(false)
                    }
                    if (cycle && consumers > 1)
                        d.changeAmmoConsumerWithResult((d.selectedAmmoType.get() + 1) % consumers, vehicle.ammoSupplier,
                            vehicle.vehicleReloadTransitionPolicy())
                }
            }
        }

        fun observeHits(on: Boolean) {
            com.atsuishio.superbwarfare.api.diagnostics.VehicleHitObserver.listener = if (!on) null else
                com.atsuishio.superbwarfare.api.diagnostics.VehicleHitObserver.Listener { vehicle, source, requested, lost ->
                    if (!vehicle.tags.contains("bvp_war_fixture")) return@Listener
                    val type = ForgeRegistries.ENTITY_TYPES.getKey(vehicle.type)?.path ?: "?"
                    val cause = source.directEntity?.let { ForgeRegistries.ENTITY_TYPES.getKey(it.type)?.path }
                        ?: source.type().msgId()
                    val row = hits.getOrPut("$type <- $cause") { DoubleArray(3) }
                    row[0] += 1.0
                    if (!(lost > 0.01f) && requested > 0f) row[1] += 1.0
                    row[2] += maxOf(0f, lost).toDouble()
                }
        }

        /** Wrecks are counted, left for 5 s and respawned at home. */
        fun lifecycle(unit: Unit) {
            val vehicle = unit.vehicle
            if (vehicle == null) { if (age >= unit.respawnAt) spawn(unit); return }
            if (vehicle.isRemoved || vehicle.isWreck || vehicle.health <= 0f) {
                if (unit.wreckedAt < 0) { unit.wreckedAt = age; kills.merge(unit.id, 1, Int::plus) }
                if (age - unit.wreckedAt >= 100 || vehicle.isRemoved) {
                    byVehicle.remove(vehicle); flights.remove(vehicle)
                    unit.crew.forEach { it.stopRiding(); it.discard() }; unit.crew.clear(); unit.gunner = null
                    if (!vehicle.isRemoved) vehicle.discard()
                    unit.vehicle = null; unit.respawnAt = age + 20
                }
            }
        }

        fun tick() {
            val t0 = System.nanoTime()
            if (age == WARMUP) { roster(); units.forEach { spawn(it) }; log("warmup", "START", "units" to units.size) }
            val next = schedule.firstOrNull { it.second == age }
            if (next != null) {
                phase?.let { log(it.name, "END") }
                when (next.first) {
                    "war" -> observeHits(true)
                    "end" -> { finish("COMPLETE", null); return }
                }
                phase = Phase(next.first).also { phases.add(it) }
                log(next.first, "START")
            }
            val current = phase
            val war = current?.name == "war"
            for (unit in units) {
                if (age < WARMUP) break
                lifecycle(unit)
                val vehicle = unit.vehicle ?: continue
                if (vehicle.isWreck) continue
                if ((age + unit.slot * 3) % 20 == 0 || unit.target?.let { it.isRemoved || it.isWreck } == true) retarget(unit, vehicle)
                if (unit.kind == Kind.GROUND) {
                    drive(unit, vehicle, true)
                    if (war && current != null) { rearm(unit, vehicle); fireGround(unit, vehicle, current) }
                } else if (war && current != null) {
                    rearm(unit, vehicle); fireAir(unit, vehicle, current)
                }
            }
            if (current != null) {
                if (age % 20 == 0) {
                    var projectiles = 0
                    for (entity in level.allEntities) {
                        if (entity is Projectile) projectiles++
                        if (age % 100 == 0) current.entitySamples.merge(
                            ForgeRegistries.ENTITY_TYPES.getKey(entity.type)?.path ?: "?", 1L, Long::plus)
                    }
                    if (age % 100 == 0) current.entitySampleCount++
                    current.projectiles.add(projectiles)
                    current.liveVehicles.add(units.count { it.vehicle?.isWreck == false })
                }
            }
            harnessNanos += System.nanoTime() - t0
            if (current != null) {
                current.wall.add((System.nanoTime() - tickStartNanos) / 1e6)
                current.harness.add(harnessNanos / 1e6)
                val cpu = cpu()
                if (cpu >= 0 && tickStartCpu >= 0) current.cpu.add((cpu - tickStartCpu) / 1e6)
            }
            age++
        }

        fun log(phase: String, event: String, vararg extra: Pair<String, Any>) {
            Mod.LOGGER.info("BVP_WAR {} {} {} {}", label, phase, event, extra.joinToString(" ") { "${it.first}=${it.second}" })
        }

        fun finish(status: String, error: String?) {
            if (finished) return
            finished = true
            phase?.let { log(it.name, "END") }
            phase = null
            val report = linkedMapOf<String, Any?>("schema" to 1, "status" to status, "error" to error, "label" to label,
                "size" to size.name.lowercase(), "fpsGoal" to size.fpsGoal,
                "startedUtc" to startedUtc, "completedUtc" to Instant.now().toString(),
                "wallSeconds" to (System.nanoTime() - started) / 1e9, "ticks" to age,
                "units" to units.map { linkedMapOf("id" to it.id, "team" to it.team, "kind" to it.kind.name,
                    "steerSign" to it.steerSign, "loadout" to it.vehicle?.persistentData?.getCompound("BvpAircraftArmament")
                        ?.getCompound("Selections")?.let { s -> s.allKeys.associateWith { k -> s.getString(k) } }) },
                "notes" to notes, "phases" to phases.map { it.report() }, "kills" to kills,
                "hits" to hits.entries.sortedByDescending { it.value[0] }.take(60).associate {
                    it.key to linkedMapOf("hits" to it.value[0].toInt(), "noDamage" to it.value[1].toInt(),
                        "damage" to it.value[2]) })
            try {
                val dir = Path.of("logs", "bvp-war")
                Files.createDirectories(dir)
                Files.writeString(dir.resolve("$label.json"),
                    GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(report))
            } catch (failure: Exception) {
                Mod.LOGGER.error("BVP_WAR report failed", failure)
            } finally {
                observeHits(false)
                flights.clear()
                for (entity in level.allEntities.toList())
                    if (entity is Projectile || entity.tags.contains("bvp_war_fixture")) entity.discard()
                units.forEach { u -> u.crew.forEach { it.discard() } }
                forced.forEach { level.setChunkForced(it.x, it.z, false) }
                player.abilities.invulnerable = player.isCreative; player.onUpdateAbilities()
                log("run", status, "error" to (error ?: "none"))
                player.sendSystemMessage(Component.literal("War $label: $status"))
                active = null
            }
        }
    }

    private fun stats(samples: List<Double>): Map<String, Double> {
        if (samples.isEmpty()) return emptyMap()
        val sorted = samples.sorted()
        fun pct(p: Double) = sorted[((p * sorted.size).toInt()).coerceIn(0, sorted.size - 1)]
        return linkedMapOf("mean" to sorted.average(), "p50" to pct(0.5), "p95" to pct(0.95), "p99" to pct(0.99),
            "max" to sorted.last())
    }
}
