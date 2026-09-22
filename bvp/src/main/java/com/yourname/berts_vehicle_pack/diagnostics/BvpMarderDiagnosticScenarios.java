package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;

import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Opt-in flat-ground controls test; vehicle travel and collisions run through normal server ticks. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID)
public final class BvpMarderDiagnosticScenarios {
    private static Run active;

    private BvpMarderDiagnosticScenarios() { }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) return;
        event.getDispatcher().register(Commands.literal("bvp_marder_test")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    if (active != null) return 0;
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    if (!player.getAbilities().mayfly) {
                        context.getSource().sendFailure(Component.literal("Use a creative diagnostic world."));
                        return 0;
                    }
                    EliteDiagnostics.INSTANCE.start(player.server);
                    active = new Run(player);
                    try { active.prepare(); }
                    catch (RuntimeException failure) { fail(failure); return 0; }
                    return 1;
                }));
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (active == null || active.server != event.getServer()) return;
        try {
            if (event.phase == TickEvent.Phase.START) active.controls();
            else active.sample();
        } catch (RuntimeException failure) { fail(failure); }
    }

    private static void fail(RuntimeException failure) {
        if (active == null) return;
        active.record("SCENARIO_ERROR", "error", failure.toString());
        active.close("ERROR");
        active = null;
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        if (active != null && active.server == event.getServer()) {
            active.close("STOPPED");
            active = null;
        }
    }

    private static final class Run {
        final ServerPlayer observer;
        final MinecraftServer server;
        final ServerLevel level;
        final Vec3 savedPosition;
        final float savedYaw;
        final float savedPitch;
        final boolean wasFlying;
        final Map<String, Fixture> driven = new LinkedHashMap<>();
        final List<Entity> owned = new ArrayList<>();
        final List<ChunkPos> forced = new ArrayList<>();
        Vec3 origin;
        int elapsed;
        int checks;
        int failures;

        Run(ServerPlayer player) {
            observer = player;
            server = player.server;
            level = player.serverLevel();
            savedPosition = player.position();
            savedYaw = player.getYRot();
            savedPitch = player.getXRot();
            wasFlying = player.getAbilities().flying;
        }

        void prepare() {
            int x = Mth.floor(savedPosition.x) + 256;
            int z = Mth.floor(savedPosition.z) + 256;
            level.getChunk(x >> 4, z >> 4);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            origin = new Vec3(x, y, z);
            // Reject unsuitable terrain instead of replacing the user's blocks.
            for (int dx = -16; dx <= 16; dx += 8) {
                for (int dz = -64; dz <= 384; dz += 4) {
                    BlockPos floor = new BlockPos(x + dx, y - 1, z + dz);
                    // Height queries on unloaded chunks return the world's minimum build height.
                    level.getChunk(floor);
                    if (!level.getBlockState(floor).isSolidRender(level, floor)
                            || level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz) != y) {
                        throw new IllegalStateException("Flat empty runway required at " + floor);
                    }
                }
            }
            observer.stopRiding();
            observer.getAbilities().flying = true;
            observer.onUpdateAbilities();
            for (int index = 0; index < 2; index++) {
                String id = index == 0 ? "marder_1a1" : "marder_1a2";
                VehicleEntity vehicle = spawn(id, origin.add(index == 0 ? -8 : 8, 0, 0));
                Cow crew = EntityType.COW.create(level);
                if (crew == null) throw new IllegalStateException("Crew fixture missing");
                crew.setNoAi(true);
                crew.setInvulnerable(true);
                crew.setPos(vehicle.position());
                if (!level.addFreshEntity(crew)) throw new IllegalStateException("Crew spawn rejected");
                owned.add(crew);
                check(id + "_occupied", crew.startRiding(vehicle, true));
                driven.put(id, new Fixture(vehicle, crew));
            }
            spawn("t_62a", origin.add(-24, 0, 0));
            record("SCENARIO_STARTED", "origin", origin, "duration_ticks", 1420);
        }

        VehicleEntity spawn(String id, Vec3 point) {
            force(point);
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(BertsVehiclePack.MODID, id));
            Entity entity = type == null ? null : type.create(level);
            if (!(entity instanceof VehicleEntity vehicle)) throw new IllegalStateException("Missing " + id);
            vehicle.load(new CompoundTag());
            vehicle.moveTo(point.x, point.y, point.z, 0, 0);
            vehicle.addTag("bvp_marder_diagnostic_fixture");
            if (!level.addFreshEntity(vehicle)) throw new IllegalStateException("Vehicle spawn rejected");
            owned.add(vehicle);
            vehicle.setEnergy(vehicle.getMaxEnergy());
            EliteDiagnostics.record(vehicle, "marder_suite", "FIXTURE_SPAWNED");
            return vehicle;
        }

        void controls() {
            for (Fixture fixture : driven.values()) {
                VehicleEntity vehicle = fixture.vehicle;
                vehicle.setForwardInputDown(elapsed >= 40 && elapsed < 400 || elapsed >= 1060 && elapsed < 1240);
                vehicle.setBackInputDown(elapsed >= 450 && elapsed < 810);
                vehicle.setRightInputDown(elapsed >= 860 && elapsed < 1020 || elapsed >= 1180 && elapsed < 1300);
                vehicle.setLeftInputDown(false);
                vehicle.setUpInputDown(elapsed >= 400 && elapsed < 450 || elapsed >= 810 && elapsed < 860
                        || elapsed >= 1020 && elapsed < 1060 || elapsed >= 1240 && elapsed < 1360);
                vehicle.setSprintInputDown(false);
            }
        }

        void sample() {
            elapsed++;
            for (var row : driven.entrySet()) {
                Fixture f = row.getValue();
                VehicleEntity v = f.vehicle;
                double speed = v.getDeltaMovement().horizontalDistance() * 72;
                double yawRate = Math.abs(Mth.wrapDegrees(v.getYRot() - f.previousYaw)) * 20;
                f.previousYaw = v.getYRot();
                if (elapsed >= 320 && elapsed <= 400) { f.forward += speed; f.forwardSamples++; }
                if (elapsed >= 730 && elapsed <= 810) { f.reverse += speed; f.reverseSamples++; }
                if (elapsed >= 920 && elapsed <= 1020) { f.pivot += yawRate; f.pivotSamples++; }
                if (elapsed >= 60) {
                    f.maxVerticalSpeed = Math.max(f.maxVerticalSpeed, Math.abs(v.getDeltaMovement().y));
                    f.maxHeightDrift = Math.max(f.maxHeightDrift, Math.abs(v.getY() - origin.y));
                }
                if (elapsed == 450 || elapsed == 860 || elapsed == 1060 || elapsed == 1360) {
                    check(row.getKey() + "_braked_" + elapsed, speed < 0.5);
                }
                if (elapsed % 5 == 0) EliteDiagnostics.record(v, "marder_suite", "CONTROL_SAMPLE",
                        "elapsed", elapsed, "speed_kmh", speed, "yaw_rate_deg_s", yawRate,
                        "position", v.position(), "velocity", v.getDeltaMovement(), "on_ground", v.onGround(),
                        "forward", v.forwardInputDown(), "reverse", v.backInputDown(),
                        "right", v.rightInputDown(), "brake", v.upInputDown(), "power", v.getPower());
                if (elapsed == 1360) f.crew.discard();
                if (elapsed == 1400) check(row.getKey() + "_unoccupied_stopped", speed < 0.05 && v.getPower() == 0);
                if (elapsed % 20 == 0) force(v.position());
            }
            if (elapsed % 20 == 0) {
                double z = driven.values().stream().mapToDouble(f -> f.vehicle.getZ()).average().orElse(origin.z);
                observer.teleportTo(level, origin.x + 32, origin.y + 9, z + 24, 135, 14);
            }
            if (elapsed != 1420) return;
            for (var row : driven.entrySet()) {
                Fixture f = row.getValue();
                double forward = f.forward / f.forwardSamples;
                double reverse = f.reverse / f.reverseSamples;
                double pivot = f.pivot / f.pivotSamples;
                check(row.getKey() + "_forward_envelope", forward > 20 && forward < 85);
                check(row.getKey() + "_reverse_symmetry", reverse / forward > 0.85 && reverse / forward < 1.2);
                check(row.getKey() + "_controlled_pivot", pivot > 20 && pivot < 65);
                check(row.getKey() + "_ground_stability", f.maxHeightDrift < 0.1 && f.maxVerticalSpeed < 0.15);
                record("MOVEMENT_RESULT", "vehicle", row.getKey(), "forward_kmh", forward,
                        "reverse_kmh", reverse, "pivot_deg_s", pivot,
                        "height_drift", f.maxHeightDrift, "max_vertical_velocity", f.maxVerticalSpeed);
            }
            Fixture a = driven.get("marder_1a1"), b = driven.get("marder_1a2");
            check("family_forward_parity", Math.abs(a.forward / b.forward - 1) < 0.05);
            check("family_reverse_parity", Math.abs(a.reverse / b.reverse - 1) < 0.05);
            check("family_pivot_parity", Math.abs(a.pivot / b.pivot - 1) < 0.05);
            close(failures == 0 ? "PASS" : "FAILED");
            active = null;
        }

        void force(Vec3 point) {
            ChunkPos chunk = new ChunkPos(Mth.floor(point.x) >> 4, Mth.floor(point.z) >> 4);
            if (!level.getForcedChunks().contains(chunk.toLong())) {
                level.setChunkForced(chunk.x, chunk.z, true);
                forced.add(chunk);
            }
        }

        void check(String name, boolean passed) {
            checks++;
            if (!passed) failures++;
            record("ASSERTION", "name", name, "passed", passed);
        }

        void record(String event, Object... fields) {
            EliteDiagnostics.record(observer, "marder_suite", event, fields);
        }

        void close(String status) {
            record("SCENARIO_FINISHED", "status", status, "checks", checks, "failures", failures, "elapsed", elapsed);
            owned.forEach(Entity::discard);
            forced.forEach(chunk -> level.setChunkForced(chunk.x, chunk.z, false));
            observer.getAbilities().flying = wasFlying;
            observer.onUpdateAbilities();
            observer.teleportTo(level, savedPosition.x, savedPosition.y, savedPosition.z, savedYaw, savedPitch);
            observer.sendSystemMessage(Component.literal("Marder diagnostics " + status + ": " + checks + " checks, " + failures + " failures."));
            EliteDiagnostics.INSTANCE.stop(server);
        }
    }

    private static final class Fixture {
        final VehicleEntity vehicle;
        final Cow crew;
        float previousYaw;
        double forward;
        double reverse;
        double pivot;
        double maxHeightDrift;
        double maxVerticalSpeed;
        int forwardSamples;
        int reverseSamples;
        int pivotSamples;

        Fixture(VehicleEntity vehicle, Cow crew) { this.vehicle = vehicle; this.crew = crew; }
    }
}
