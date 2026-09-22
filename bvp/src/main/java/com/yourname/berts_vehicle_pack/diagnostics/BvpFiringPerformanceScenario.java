package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.projectile.ProjectileEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.item.gun.ProjectileFactory;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Opt-in matched BMPT workload; timing samples exclude setup and detailed diagnostic logging. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID)
public final class BvpFiringPerformanceScenario {
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static Run active;

    private BvpFiringPerformanceScenario() { }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) return;
        event.getDispatcher().register(Commands.literal("bvp_firing_perf")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("label", StringArgumentType.word()).executes(context -> {
                    if (active != null) return 0;
                    String label = StringArgumentType.getString(context, "label");
                    if (!label.matches("[A-Za-z0-9_-]{1,40}")) return 0;
                    var player = context.getSource().getPlayerOrException();
                    if (EliteDiagnostics.isEnabled(player.serverLevel())) {
                        player.sendSystemMessage(Component.literal("Stop Elite diagnostics before timing."));
                        return 0;
                    }
                    active = new Run(player, label);
                    try { active.prepare(); }
                    catch (RuntimeException failure) { fail(failure); return 0; }
                    return 1;
                })));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(TickEvent.ServerTickEvent event) {
        if (active == null || event.getServer() != active.server) return;
        if (event.phase == TickEvent.Phase.START) {
            active.tickStartNanos = System.nanoTime();
            active.tickStartCpuNanos = cpuTime();
            return;
        }
        try { active.tick(); } catch (RuntimeException failure) { fail(failure); }
    }

    @SubscribeEvent
    public static void join(EntityJoinLevelEvent event) {
        if (active == null || event.getLevel() != active.level || event.isCanceled()
                || !(event.getEntity() instanceof Projectile projectile)) return;
        active.projectiles.add(projectile);
        boolean fragment = projectile instanceof ProjectileEntity bullet && bullet.isImpactShrapnel();
        if (fragment) active.fragments.add(projectile);
        if (active.phase != null) {
            if (fragment) active.phase.fragmentsCreated++;
            else active.phase.primaryCreated++;
        }
    }

    @SubscribeEvent
    public static void leave(EntityLeaveLevelEvent event) {
        if (active == null || event.getLevel() != active.level) return;
        active.fragments.remove(event.getEntity());
        active.projectiles.remove(event.getEntity());
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        if (active != null && event.getServer() == active.server) active.finish("STOPPED", null);
    }

    private static long cpuTime() {
        return THREADS.isCurrentThreadCpuTimeSupported() && THREADS.isThreadCpuTimeEnabled()
                ? THREADS.getCurrentThreadCpuTime() : -1L;
    }

    private static void fail(RuntimeException failure) {
        if (active != null) active.finish("ERROR", failure.toString());
    }

    private static final class Phase {
        final String name;
        final List<Double> wallMillis = new ArrayList<>();
        final List<Double> cpuMillis = new ArrayList<>();
        final Map<String, Integer> rejections = new LinkedHashMap<>();
        final List<Map<String, Object>> launchSamples = new ArrayList<>();
        int accepted;
        int primaryCreated;
        int fragmentsCreated;
        int peakFragments;
        int peakProjectiles;

        Phase(String name) { this.name = name; }

        Map<String, Object> report() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", name);
            result.put("sampleTicks", wallMillis.size());
            result.put("acceptedShots", accepted);
            result.put("rejections", rejections);
            result.put("launchSamples", launchSamples);
            result.put("primaryProjectilesCreated", primaryCreated);
            result.put("serverFragmentsCreated", fragmentsCreated);
            result.put("peakServerFragments", peakFragments);
            result.put("peakServerProjectiles", peakProjectiles);
            result.put("tickWallMillis", statistics(wallMillis));
            result.put("tickCpuMillis", statistics(cpuMillis));
            return result;
        }
    }

    private static final class Run {
        final MinecraftServer server;
        final ServerLevel level;
        final ServerPlayer observer;
        final String label;
        final String id = UUID.randomUUID().toString();
        final Vec3 savedPosition;
        final float savedYaw;
        final float savedPitch;
        final Vec3 origin;
        final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        final Set<ChunkPos> forced = new LinkedHashSet<>();
        final Set<Projectile> projectiles = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<Projectile> fragments = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<Phase> phases = new ArrayList<>();
        final long startedNanos = System.nanoTime();
        final String startedUtc = Instant.now().toString();
        VehicleEntity vehicle;
        BlockPos wallCenter;
        Phase phase;
        int elapsed;
        long tickStartNanos;
        long tickStartCpuNanos;
        boolean finished;

        Run(ServerPlayer observer, String label) {
            this.observer = observer;
            this.server = observer.server;
            this.level = observer.serverLevel();
            this.label = label;
            savedPosition = observer.position();
            savedYaw = observer.getYRot();
            savedPitch = observer.getXRot();
            origin = new Vec3(Math.floor(savedPosition.x) + 40, Math.floor(savedPosition.y),
                    Math.floor(savedPosition.z) + 40);
        }

        void prepare() {
            observer.stopRiding();
            force(origin.x, origin.z);
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(
                    new ResourceLocation(BertsVehiclePack.MODID, "bmpt"));
            Entity entity = type == null ? null : type.create(level);
            if (!(entity instanceof VehicleEntity created)) throw new IllegalStateException("BMPT is unavailable");
            vehicle = created;
            vehicle.load(new CompoundTag());
            vehicle.moveTo(origin.x, origin.y, origin.z, 0, 0);
            vehicle.setNoGravity(true);
            vehicle.addTag("bvp_firing_perf_fixture");
            vehicle.setEnergy(vehicle.getMaxEnergy());
            if (!level.addFreshEntity(vehicle)) throw new IllegalStateException("BMPT insertion failed");
            observer.teleportTo(level, origin.x + 7, origin.y + 2, origin.z - 7, -20, 0);
            if (!observer.startRiding(vehicle, true)) throw new IllegalStateException("BMPT shooter could not board");
            observer.sendSystemMessage(Component.literal("BMPT firing benchmark " + label + " started."));
        }

        void resetWeapon() {
            vehicle.modifyGunData("DualCannon", data -> {
                data.resetStatus();
                data.reload.setPendingProgressPercent(0);
                data.ammo.set(850);
                data.virtualAmmo.set(0);
                data.heat.set(0);
                data.overHeat.set(false);
            });
        }

        void shoot() {
            var result = vehicle.vehicleShootResult(observer, "DualCannon");
            if (phase != null) {
                if (result.isAccepted()) phase.accepted++;
                else phase.rejections.merge(result.getReason().toString(), 1, Integer::sum);
                if (phase.launchSamples.size() < 3) for (var projectileId : result.getSpawnedProjectileIds()) {
                    Entity projectile = level.getEntity(projectileId);
                    if (projectile != null) phase.launchSamples.add(Map.of(
                            "position", projectile.position().toString(),
                            "velocity", projectile.getDeltaMovement().toString(),
                            "type", ForgeRegistries.ENTITY_TYPES.getKey(projectile.getType()).toString(),
                            "firstBlockHit", level.clip(new ClipContext(projectile.position(),
                                    projectile.position().add(projectile.getDeltaMovement()),
                                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, projectile))
                                    .getBlockPos().toShortString()));
                }
            }
        }

        void tick() {
            elapsed++;
            if (vehicle == null || vehicle.isRemoved() || !observer.isAlive()) {
                throw new IllegalStateException("Firing fixture or observer disappeared");
            }
            vehicle.setDeltaMovement(Vec3.ZERO);
            vehicle.setPos(origin.x, origin.y, origin.z);
            vehicle.setYRot(0);
            vehicle.setXRot(0);
            vehicle.setTurretYRot(0);
            vehicle.setTurretXRot(0);
            if (elapsed == 40) {
                var muzzle = vehicle.resolveMuzzleFrame("DualCannon", 1.0F);
                if (muzzle == null || Math.abs(muzzle.getDirection().z) < 0.9) {
                    throw new IllegalStateException("BMPT muzzle is not aligned with the benchmark wall");
                }
                wallCenter = BlockPos.containing(muzzle.getPosition().add(muzzle.getDirection().scale(20)));
                paintWall(true);
                resetWeapon();
            }
            if (elapsed >= 60 && elapsed < 140) shoot();
            if (elapsed == 200) begin("idle");
            if (elapsed == 400) {
                phase = null;
                paintWall(false);
                resetWeapon();
            }
            if (elapsed == 440) begin("air_fire");
            if (elapsed >= 440 && elapsed < 840) shoot();
            if (elapsed == 840) phase = null;
            if (elapsed == 940) {
                paintWall(true);
                resetWeapon();
            }
            if (elapsed == 980) begin("wall_fire");
            if (elapsed >= 980 && elapsed < 1380) shoot();
            if (elapsed == 1380) phase = null;
            if (phase != null) {
                phase.peakFragments = Math.max(phase.peakFragments, fragments.size());
                phase.peakProjectiles = Math.max(phase.peakProjectiles, projectiles.size());
                phase.wallMillis.add((System.nanoTime() - tickStartNanos) / 1_000_000.0);
                long cpu = cpuTime();
                if (cpu >= 0 && tickStartCpuNanos >= 0) {
                    phase.cpuMillis.add((cpu - tickStartCpuNanos) / 1_000_000.0);
                }
            }
            if (elapsed >= 1480) finish("COMPLETE", null);
        }

        void begin(String name) {
            phase = new Phase(name);
            phases.add(phase);
            observer.sendSystemMessage(Component.literal("BMPT benchmark phase: " + name));
        }

        void paintWall(boolean present) {
            for (int x = -4; x <= 4; x++) for (int y = -3; y <= 3; y++) {
                BlockPos pos = wallCenter.offset(x, y, 0);
                force(pos.getX(), pos.getZ());
                blocks.putIfAbsent(pos.immutable(), level.getBlockState(pos));
                level.setBlockAndUpdate(pos, present ? Blocks.BEDROCK.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
        }

        void force(double x, double z) {
            ChunkPos pos = new ChunkPos((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
            if (!level.getForcedChunks().contains(pos.toLong())) {
                level.setChunkForced(pos.x, pos.z, true);
                forced.add(pos);
            }
            level.getChunk(pos.x, pos.z);
        }

        void finish(String status, String error) {
            if (finished) return;
            finished = true;
            Phase lastPhase = phase;
            phase = null;
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("schema", 1);
            report.put("status", status);
            report.put("error", error);
            report.put("label", label);
            report.put("id", id);
            report.put("startedUtc", startedUtc);
            report.put("completedUtc", Instant.now().toString());
            report.put("wallDurationSeconds", (System.nanoTime() - startedNanos) / 1_000_000_000.0);
            report.put("elapsedTicks", elapsed);
            report.put("origin", origin.toString());
            report.put("wallCenter", wallCenter == null ? null : wallCenter.toShortString());
            report.put("wallStillPresent", wallCenter != null && level.getBlockState(wallCenter).is(Blocks.BEDROCK));
            report.put("detailedDiagnosticsEnabled", EliteDiagnostics.isEnabled(level));
            report.put("javaVersion", System.getProperty("java.version"));
            report.put("bvpSource", sourceOf(BvpFiringPerformanceScenario.class));
            report.put("sbwSource", sourceOf(VehicleEntity.class));
            report.put("phaseAtStop", lastPhase == null ? null : lastPhase.name);
            report.put("phases", phases.stream().map(Phase::report).toList());
            report.put("notes", List.of("One BMPT, one observer, fixed muzzle and position.",
                    "Warm-up precedes idle, air fire and bedrock impacts; one authoritative fire attempt per tick.",
                    "Ammo and heat reset only before each firing phase; normal admission, cooldown, heat and belt advance remain active.",
                    "Timing includes the identical diagnostic harness; detailed per-projectile diagnostics are disabled.",
                    "This workload isolates server fire/impact work; it is not a capture of player C2S input."));
            try {
                Path directory = Path.of("logs", "bvp-firing-performance");
                Files.createDirectories(directory);
                Path output = directory.resolve(label + "-" + id + ".json");
                Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report));
                observer.sendSystemMessage(Component.literal("BMPT firing benchmark " + status + ": " + output));
            } catch (Exception failure) {
                observer.sendSystemMessage(Component.literal("BMPT benchmark output failed: " + failure));
            } finally {
                for (Projectile projectile : new ArrayList<>(projectiles)) projectile.discard();
                observer.stopRiding();
                if (vehicle != null) vehicle.discard();
                blocks.forEach(level::setBlockAndUpdate);
                forced.forEach(pos -> level.setChunkForced(pos.x, pos.z, false));
                observer.teleportTo(level, savedPosition.x, savedPosition.y, savedPosition.z, savedYaw, savedPitch);
                active = null;
            }
        }
    }

    private static String sourceOf(Class<?> type) {
        var source = type.getProtectionDomain().getCodeSource();
        return source == null ? "unknown" : source.getLocation().toExternalForm();
    }

    private static Map<String, Double> statistics(List<Double> samples) {
        if (samples.isEmpty()) return Map.of();
        List<Double> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        double sum = sorted.stream().mapToDouble(Double::doubleValue).sum();
        return Map.of("mean", sum / sorted.size(), "p50", percentile(sorted, 0.50),
                "p95", percentile(sorted, 0.95), "p99", percentile(sorted, 0.99),
                "max", sorted.get(sorted.size() - 1), "total", sum);
    }

    private static double percentile(List<Double> sorted, double percentile) {
        return sorted.get(Math.min(sorted.size() - 1, Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1)));
    }
}
