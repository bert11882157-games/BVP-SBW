package com.atsuishio.superbwarfare.diagnostics

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.aircraft.AircraftArmamentRegistry
import com.atsuishio.superbwarfare.api.aircraft.AircraftPylonRacks
import com.atsuishio.superbwarfare.api.aircraft.AircraftStoreWeapons
import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.google.gson.GsonBuilder
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
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

/**
 * Diagnostic launches only (`bvp.diagnostics.scenarios`): a busy battlefield for performance work.
 *
 * `/bvp_stress <label>` builds a stone pad with bedrock walls in front of and behind it, then runs fixed phases
 * (server ticks): `empty` (the pad alone), `fleet` (20 ground vehicles in two staggered rows and 10 aircraft and
 * helicopters behind them, every hardpoint carrying the store that hangs the most munitions), `war` (every ground
 * vehicle fires every weapon it has as fast as its guns allow, aircraft fire their guns and release their stores;
 * ammunition and heat are topped up every second) and `ceasefire`. Fixtures are held in place, kept at full health
 * and invulnerable. Each phase start and end is logged as `BVP_STRESS <label> <phase> START|END` so external
 * orchestration can time client captures; the server tick wall/CPU times, projectile counts and shot results per
 * phase are written to `logs/bvp-stress/<label>.json`. `/bvp_stress_stop` ends a run early.
 */
@EventBusSubscriber(modid = Mod.MODID)
object StressDiagnostics {
    private val ground = listOf("t72b", "t90a", "m1a2_abrams_sep_v2", "leopard_2a4", "challenger_2", "leclerc_s1",
        "k2a1_black_panther", "ztz99a", "t80u_obr1985", "m1_abrams_elite", "bmp2", "bmp3m_elite", "m2_bradley",
        "cv9040_no_net", "marder_1a2", "btr80a", "lav25", "tunguska", "gepard", "zsu23_4")
    private val air = listOf("f_16c", "su_25", "a_10", "f_15c", "su_27", "fa_18e", "mig_29", "su_35", "ah_64d", "mi24v")
    /** Phase name and the server tick it starts at; the last entry ends the run. */
    private val schedule = listOf("empty" to 60, "warmup" to 300, "fleet" to 600, "war" to 1200, "ceasefire" to 2400,
        "end" to 2600)
    private var active: Run? = null
    private val threads = ManagementFactory.getThreadMXBean()

    @SubscribeEvent fun commands(event: RegisterCommandsEvent) {
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) return
        event.dispatcher.register(Commands.literal("bvp_stress").requires { it.hasPermission(2) }
            .then(Commands.argument("label", StringArgumentType.word()).executes { context ->
                val label = StringArgumentType.getString(context, "label")
                val player = context.source.playerOrException
                if (active != null || !label.matches(Regex("[A-Za-z0-9_-]{1,40}")) || player.vehicle != null ||
                    !player.server.isDedicatedServer || player.server.localIp != "127.0.0.1") return@executes 0
                val run = Run(player, label)
                active = run
                try { run.prepare() } catch (failure: Exception) { run.finish("ERROR", failure.toString()) }
                if (active != null) 1 else 0
            }))
        event.dispatcher.register(Commands.literal("bvp_stress_stop").requires { it.hasPermission(2) }
            .executes { active?.finish("STOPPED", "Operator request"); 1 })
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST) fun tickStart(event: TickEvent.ServerTickEvent) {
        val run = active ?: return
        if (event.phase != TickEvent.Phase.START || event.server != run.player.server) return
        run.tickStartNanos = System.nanoTime()
        run.tickStartCpu = cpu()
        try { run.hold() } catch (failure: Exception) { run.finish("ERROR", failure.toString()) }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST) fun tickEnd(event: TickEvent.ServerTickEvent) {
        val run = active ?: return
        if (event.phase != TickEvent.Phase.END || event.server != run.player.server) return
        try { run.tick() } catch (failure: Exception) { run.finish("ERROR", failure.toString()) }
    }

    @SubscribeEvent fun stopping(event: ServerStoppingEvent) {
        if (active?.player?.server === event.server) active?.finish("STOPPED", "Server stopping")
    }

    private fun cpu(): Long = if (threads.isCurrentThreadCpuTimeSupported && threads.isThreadCpuTimeEnabled)
        threads.currentThreadCpuTime else -1L

    private class Phase(val name: String) {
        val wall = ArrayList<Double>()
        val cpu = ArrayList<Double>()
        val projectiles = ArrayList<Int>()
        var accepted = 0
        val rejected = linkedMapOf<String, Int>()
        val firedByType = linkedMapOf<String, Int>()
        fun report(): Map<String, Any?> = linkedMapOf("name" to name, "ticks" to wall.size,
            "tickWallMs" to stats(wall), "tickCpuMs" to stats(cpu),
            "msptMean" to (if (wall.isEmpty()) 0.0 else wall.sum() / wall.size),
            "projectiles" to linkedMapOf("mean" to (if (projectiles.isEmpty()) 0.0 else projectiles.average()),
                "peak" to (projectiles.maxOrNull() ?: 0)),
            "shotsAccepted" to accepted, "shotRejections" to rejected, "acceptedByWeapon" to firedByType)
    }

    private class Run(val player: ServerPlayer, val label: String) {
        val server = player.server
        val level = player.serverLevel()
        val started = System.nanoTime()
        val startedUtc: String = Instant.now().toString()
        val origin = Vec3(kotlin.math.floor(player.x) + 8, kotlin.math.floor(player.y), kotlin.math.floor(player.z) + 8)
        val fleet = linkedMapOf<VehicleEntity, Vec3>()
        val aircraft = HashSet<VehicleEntity>()
        val forced = LinkedHashSet<ChunkPos>()
        val phases = ArrayList<Phase>()
        val notes = ArrayList<String>()
        var phase: Phase? = null
        var age = 0
        var tickStartNanos = 0L
        var tickStartCpu = -1L
        var finished = false
        var lostFixtures = 0

        fun prepare() {
            // Pad x -10..190, z -110..90 around the origin; bedrock walls at z +80 and z -100.
            for (x in -10..190 step 16) for (z in -110..90 step 16) force(origin.x + x, origin.z + z)
            fill(-10, -1, -110, 190, -1, 90, Blocks.STONE.defaultBlockState())
            for (y0 in 0..24 step 6) fill(-10, y0, -110, 190, y0 + 5, 90, Blocks.AIR.defaultBlockState())
            fill(-10, 0, 80, 190, 14, 80, Blocks.BEDROCK.defaultBlockState())
            fill(-10, 0, -100, 190, 14, -100, Blocks.BEDROCK.defaultBlockState())
            observe()
            player.abilities.flying = true; player.abilities.invulnerable = true; player.onUpdateAbilities()
            log("setup", "READY")
        }

        fun fill(x0: Int, y0: Int, z0: Int, x1: Int, y1: Int, z1: Int, state: net.minecraft.world.level.block.state.BlockState) {
            val base = BlockPos.containing(origin)
            for (pos in BlockPos.betweenClosed(base.offset(x0, y0, z0), base.offset(x1, y1, z1)))
                if (level.getBlockState(pos) != state) level.setBlock(pos, state, 2)
        }

        fun force(x: Double, z: Double) {
            val pos = ChunkPos(kotlin.math.floor(x).toInt() shr 4, kotlin.math.floor(z).toInt() shr 4)
            if (level.setChunkForced(pos.x, pos.z, true)) forced.add(pos)
            level.getChunk(pos.x, pos.z)
        }

        /** Above and in front of the ground rows, looking back over them to the aircraft (fire passes below). */
        fun observe() {
            player.stopRiding()
            player.teleportTo(level, origin.x + 45, origin.y + 18, origin.z + 38, 180f, 28f)
            player.deltaMovement = Vec3.ZERO
        }

        fun spawn() {
            ground.forEachIndexed { index, id ->
                val row = index / 10
                val point = origin.add((index % 10) * 9.0 + row * 4.5, 0.0, -row * 14.0)
                add(id, point, null)
            }
            air.forEachIndexed { index, id -> add(id, origin.add(index * 18.0 - 2.0, 0.0, -45.0), loadout(id)) }
            log("fleet", "SPAWNED", "vehicles" to fleet.size, "aircraft" to aircraft.size)
        }

        fun add(id: String, point: Vec3, equipment: CompoundTag?) {
            val type = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation("berts_vehicle_pack", id))
            val vehicle = type?.create(level) as? VehicleEntity
            if (vehicle == null) { notes.add("missing fixture $id"); return }
            vehicle.load(CompoundTag())
            if (equipment != null) { vehicle.persistentData.put("BvpAircraftArmament", equipment); aircraft.add(vehicle) }
            vehicle.moveTo(point.x, point.y, point.z, 0f, 0f)
            vehicle.isInvulnerable = true
            vehicle.addTag("bvp_stress_fixture")
            if (!level.addFreshEntity(vehicle)) { notes.add("insertion failed $id"); return }
            fleet[vehicle] = point
        }

        /** Every hardpoint with the allowed store that hangs the most munitions (most rack copies x capacity). */
        fun loadout(id: String): CompoundTag? {
            val definition = AircraftArmamentRegistry.aircraft[ResourceLocation("berts_vehicle_pack", id)] ?: return null
            val selections = CompoundTag(); val counts = CompoundTag()
            for (mount in AircraftArmamentRegistry.mounts(definition)) {
                val key = mount["Id"].asString
                var best: Pair<String, Int>? = null; var bestScore = -1.0
                for (value in mount.getAsJsonArray("AllowedStores") ?: continue) {
                    val store = AircraftArmamentRegistry.stores[ResourceLocation(value.asString)] ?: continue
                    val copies = AircraftPylonRacks.maxCopies(definition, mount, store)
                    if (copies < 1) continue
                    val score = copies * (store["Capacity"]?.asInt ?: 1) + (store["MassKg"]?.asDouble ?: 0.0) / 1e5
                    if (score > bestScore) { bestScore = score; best = value.asString to copies }
                }
                val (store, copies) = best ?: continue
                selections.putString(key, store); counts.putInt(key, copies)
            }
            if (selections.isEmpty) { notes.add("no stores for $id"); return null }
            return CompoundTag().also { it.put("Selections", selections); it.put("Counts", counts) }
        }

        fun hold() {
            for ((vehicle, point) in fleet) {
                if (vehicle.isRemoved || vehicle.isWreck) continue
                vehicle.setPos(point); vehicle.deltaMovement = Vec3.ZERO; vehicle.setOnGround(true)
                if (vehicle.health < vehicle.getMaxHealth()) vehicle.health = vehicle.getMaxHealth()
            }
        }

        fun rearm() {
            for (vehicle in fleet.keys) for (name in vehicle.gunDataMap.keys) vehicle.modifyGunData(name) { data ->
                data.resetStatus(); data.reload.setPendingProgressPercent(0)
                data.ammo.set(500); data.virtualAmmo.set(0); data.heat.set(0.0); data.overHeat.set(false)
            }
        }

        fun fire() {
            val current = phase ?: return
            for (vehicle in fleet.keys) {
                if (vehicle.isRemoved || vehicle.isWreck) continue
                val natives = vehicle.gunDataMap.keys.toList()
                val names = if (vehicle in aircraft) {
                    // Stores go every two seconds, staggered across the line; guns every tick.
                    if ((age + fleet.keys.indexOf(vehicle) * 4) % 40 == 0) AircraftStoreWeapons.ids(vehicle, 0, natives)
                    else natives
                } else natives
                for (name in names) {
                    val result = vehicle.vehicleShootResult(null, name)
                    if (result.isAccepted()) {
                        current.accepted++
                        val key = ForgeRegistries.ENTITY_TYPES.getKey(vehicle.type)?.path + "/" + name
                        current.firedByType.merge(key, 1, Int::plus)
                    } else current.rejected.merge(result.reason.toString(), 1, Int::plus)
                }
            }
        }

        fun tick() {
            val next = schedule.firstOrNull { it.second == age }
            if (next != null) {
                phase?.let { log(it.name, "END") }
                when (next.first) {
                    "warmup" -> { spawn(); phase = null }
                    "war" -> rearm()
                    "end" -> { finish("COMPLETE", null); return }
                }
                if (next.first != "warmup") {
                    phase = Phase(next.first).also { phases.add(it) }
                    log(next.first, "START")
                }
            }
            val current = phase
            if (current != null) {
                if (current.name == "war") {
                    if (age % 20 == 0) rearm()
                    fire()
                }
                if (age % 10 == 0) current.projectiles.add(level.allEntities.count { it is Projectile })
                current.wall.add((System.nanoTime() - tickStartNanos) / 1e6)
                val cpu = cpu()
                if (cpu >= 0 && tickStartCpu >= 0) current.cpu.add((cpu - tickStartCpu) / 1e6)
            }
            lostFixtures = fleet.keys.count { it.isRemoved || it.isWreck }
            age++
        }

        fun log(phase: String, event: String, vararg extra: Pair<String, Any>) {
            Mod.LOGGER.info("BVP_STRESS {} {} {} {}", label, phase, event, extra.joinToString(" ") { "${it.first}=${it.second}" })
        }

        fun finish(status: String, error: String?) {
            if (finished) return
            finished = true
            phase?.let { log(it.name, "END") }
            phase = null
            val report = linkedMapOf<String, Any?>("schema" to 1, "status" to status, "error" to error, "label" to label,
                "startedUtc" to startedUtc, "completedUtc" to Instant.now().toString(),
                "wallSeconds" to (System.nanoTime() - started) / 1e9, "ticks" to age,
                "ground" to ground, "aircraft" to air, "lostFixtures" to lostFixtures, "notes" to notes,
                "phases" to phases.map { it.report() })
            try {
                val dir = Path.of("logs", "bvp-stress")
                Files.createDirectories(dir)
                Files.writeString(dir.resolve("$label.json"),
                    GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(report))
            } catch (failure: Exception) {
                Mod.LOGGER.error("BVP_STRESS report failed", failure)
            } finally {
                for (entity in level.allEntities.toList())
                    if (entity is Projectile || entity.tags.contains("bvp_stress_fixture")) entity.discard()
                fleet.clear()
                forced.forEach { level.setChunkForced(it.x, it.z, false) }
                player.abilities.invulnerable = player.isCreative; player.onUpdateAbilities()
                log("run", status, "error" to (error ?: "none"))
                player.sendSystemMessage(Component.literal("Stress run $label: $status"))
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
