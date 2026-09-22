package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/** Opt-in spawn/render and short-effect fixtures for an isolated creative overworld. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID)
public final class BvpRenderLifecycleDiagnosticScenarios {
    private static final long AGED_GAME_TIME = 976_051_961L;
    private static final String FIXTURE_TAG = "bvp_render_lifecycle_fixture";
    private static Run active;

    private BvpRenderLifecycleDiagnosticScenarios() { }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) return;
        event.getDispatcher().register(Commands.literal("bvp_render_test")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    if (active != null) return 0;
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    if (!player.getAbilities().mayfly || player.level().dimension() != Level.OVERWORLD) {
                        context.getSource().sendFailure(Component.literal("Use an isolated creative overworld."));
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
        if (event.phase != TickEvent.Phase.END || active == null || active.server != event.getServer()) return;
        try { active.tick(); } catch (RuntimeException failure) { fail(failure); }
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        if (active != null && active.server == event.getServer()) {
            active.close("STOPPED");
            active = null;
        }
    }

    private static void fail(RuntimeException failure) {
        if (active == null) return;
        active.record("SCENARIO_ERROR", "error", failure.toString());
        active.close("ERROR");
        active = null;
    }

    private static final class Run {
        final ServerPlayer observer;
        final MinecraftServer server;
        final ServerLevel level;
        final Vec3 savedPosition;
        final float savedYaw;
        final float savedPitch;
        final boolean wasFlying;
        final long savedGameTime;
        final List<Entity> owned = new ArrayList<>();
        final List<ChunkPos> forced = new ArrayList<>();
        Vec3 origin;
        VehicleEntity primary;
        int elapsed;
        int assertions;
        int failures;
        String phase = "setup";

        Run(ServerPlayer player) {
            observer = player;
            server = player.server;
            level = player.serverLevel();
            savedPosition = player.position();
            savedYaw = player.getYRot();
            savedPitch = player.getXRot();
            wasFlying = player.getAbilities().flying;
            savedGameTime = level.getGameTime();
        }

        void prepare() {
            observer.stopRiding();
            observer.getAbilities().flying = true;
            observer.onUpdateAbilities();
            int x = Mth.floor(savedPosition.x) + 256;
            int z = Mth.floor(savedPosition.z) + 256;
            level.getChunk(x >> 4, z >> 4);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            origin = new Vec3(x, y, z);
            setTime(AGED_GAME_TIME);
            observePrimary();
            record("SCENARIO_STARTED", "suite", "render_lifecycle", "origin", origin,
                    "reported_game_time", AGED_GAME_TIME, "duration_ticks", 1120,
                    "dedicated_server", server.isDedicatedServer());
        }

        void tick() {
            elapsed++;
            for (Entity entity : owned) {
                if (entity instanceof VehicleEntity vehicle && !entity.isRemoved()) {
                    BvpVehicleDataDiagnostic.record(vehicle);
                }
            }
            switch (elapsed) {
                case 40 -> spawnPrimary("cold_spawn");
                case 100, 120, 140 -> fire("Cannon");
                case 190 -> discardPrimary();
                case 220 -> spawnPrimary("warm_spawn_1");
                case 280, 300 -> fire("Cannon");
                case 350 -> discardPrimary();
                case 380 -> spawnPrimary("warm_spawn_2");
                case 440, 460 -> fire("Cannon");
                case 500 -> spawnQueueCrowd();
                case 800 -> {
                    phase = "backward_time_correction";
                    observePrimary();
                    record("PHASE_STARTED");
                }
                case 840 -> fire("Cannon");
                case 841 -> setTime(AGED_GAME_TIME - 4000);
                case 880 -> fire("Cannon");
                case 920 -> {
                    phase = "forward_time_correction";
                    record("PHASE_STARTED");
                    fire("Cannon");
                }
                case 921 -> setTime(AGED_GAME_TIME + 16_777_216L);
                case 960 -> fire("Cannon");
                case 1120 -> {
                    check("primary_survived", primary != null && !primary.isRemoved());
                    close(failures == 0 ? "PASS" : "FAIL");
                    active = null;
                }
                default -> { }
            }
        }

        void spawnPrimary(String nextPhase) {
            phase = nextPhase;
            primary = spawn("t80b_obr1976", origin, 180);
            record("PHASE_STARTED", "vehicle", primary.getUUID());
        }

        void discardPrimary() {
            if (primary != null) primary.discard();
            primary = null;
        }

        void observePrimary() {
            observer.teleportTo(level, origin.x + 10, origin.y + 5, origin.z - 14, 35, 15);
        }

        void spawnQueueCrowd() {
            phase = "queue_saturation";
            observer.teleportTo(level, origin.x, origin.y + 18, origin.z + 32, 0, 15);
            String[] ids = {"t72b", "t72a", "t90a", "m60a1", "m48a3_elite", "leo2a6",
                    "m1_abrams_elite", "marder_1a1", "marder_1a2", "t_62a", "bmp2", "bmpt",
                    "lav25", "cv9040_no_net", "m2_bradley"};
            for (int i = 0; i < ids.length; i++) {
                spawn(ids[i], origin.add((i % 6 - 2.5) * 16, 0, 80 + (i / 6) * 24), 180);
            }
            record("PHASE_STARTED", "distinct_types", ids.length);
        }

        VehicleEntity spawn(String id, Vec3 point, float yaw) {
            force(point);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    Mth.floor(point.x), Mth.floor(point.z));
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(BertsVehiclePack.MODID, id));
            Entity entity = type == null ? null : type.create(level);
            if (!(entity instanceof VehicleEntity vehicle)) throw new IllegalStateException("Missing vehicle " + id);
            vehicle.load(new CompoundTag());
            vehicle.moveTo(point.x, y, point.z, yaw, 0);
            vehicle.addTag(FIXTURE_TAG);
            vehicle.setEnergy(vehicle.getMaxEnergy());
            for (int slot = 0; slot < vehicle.getInventory().getSlots(); slot++) {
                vehicle.getInventory().setStackInSlot(slot, ItemStack.EMPTY);
            }
            owned.add(vehicle);
            check("spawn_accepted_" + id, level.addFreshEntity(vehicle));
            record("FIXTURE_SPAWNED", "vehicle", vehicle.getUUID(), "vehicle_type", id,
                    "entity_id", vehicle.getId(), "position", vehicle.position());
            return vehicle;
        }

        void fire(String weapon) {
            if (primary == null || primary.isRemoved()) throw new IllegalStateException("Primary vehicle missing");
            primary.modifyGunData(weapon, Run::loadOne);
            var result = primary.vehicleShootResult(null, weapon);
            check("shot_accepted", result.isAccepted());
            record("SHOT_RESULT", "vehicle", primary.getUUID(), "weapon", weapon,
                    "accepted", result.isAccepted(), "reason", result.getReason());
        }

        static void loadOne(GunData data) {
            data.resetStatus();
            data.reload.setPendingProgressPercent(0);
            data.ammo.set(1);
            data.virtualAmmo.set(0);
            data.heat.set(0);
            data.overHeat.set(false);
        }

        void setTime(long gameTime) {
            ((ServerLevelData) level.getLevelData()).setGameTime(gameTime);
            observer.connection.send(new ClientboundSetTimePacket(gameTime, level.getDayTime(),
                    level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)));
            record("TIME_SET", "game_time", gameTime);
        }

        void force(Vec3 point) {
            ChunkPos pos = new ChunkPos(Mth.floor(point.x) >> 4, Mth.floor(point.z) >> 4);
            if (!level.getForcedChunks().contains(pos.toLong())) {
                level.setChunkForced(pos.x, pos.z, true);
                forced.add(pos);
            }
            level.getChunk(pos.x, pos.z);
        }

        void check(String name, boolean passed) {
            assertions++;
            if (!passed) failures++;
            record(passed ? "ASSERT_PASS" : "ASSERT_FAIL", "check", name);
        }

        void record(String event, Object... fields) {
            Object[] withPhase = new Object[fields.length + 4];
            withPhase[0] = "phase";
            withPhase[1] = phase;
            withPhase[2] = "elapsed_ticks";
            withPhase[3] = elapsed;
            System.arraycopy(fields, 0, withPhase, 4, fields.length);
            EliteDiagnostics.record(observer, "render_lifecycle", event, withPhase);
        }

        void close(String status) {
            record("SCENARIO_COMPLETE", "status", status, "assertions", assertions, "failures", failures);
            owned.forEach(entity -> { if (!entity.isRemoved()) entity.discard(); });
            forced.forEach(pos -> level.setChunkForced(pos.x, pos.z, false));
            setTime(savedGameTime + elapsed);
            observer.stopRiding();
            observer.getAbilities().flying = wasFlying;
            observer.onUpdateAbilities();
            observer.teleportTo(level, savedPosition.x, savedPosition.y, savedPosition.z, savedYaw, savedPitch);
            observer.sendSystemMessage(Component.literal("Render lifecycle diagnostics " + status
                    + ": " + assertions + " assertions, " + failures + " failures."));
            EliteDiagnostics.INSTANCE.stop(server);
        }
    }
}
