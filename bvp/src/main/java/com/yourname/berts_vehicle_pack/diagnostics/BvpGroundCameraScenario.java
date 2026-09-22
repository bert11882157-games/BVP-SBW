package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;

import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.google.gson.Gson;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import com.yourname.berts_vehicle_pack.armor.VehicleModuleHealth;
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
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Private ramp traversal through normal ground physics, with long camera observation stops. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID)
public final class BvpGroundCameraScenario {
    private static Run active;
    private BvpGroundCameraScenario() { }

    private static boolean privatePlayer(ServerPlayer player) {
        MinecraftServer server = player.server;
        return DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios") && server.isDedicatedServer()
                && !server.usesAuthentication() && "127.0.0.1".equals(server.getLocalIp())
                && server.getPort() == BvpFireTrafficControl.PORT && server.getPlayerCount() == 1
                && player.level().dimension() == Level.OVERWORLD && player.isAlive()
                && !player.isSpectator() && player.getAbilities().instabuild
                && BvpFireTrafficControl.identity(player.getGameProfile().getName(), player.getUUID())
                && BvpFireTrafficControl.loopback(player.connection.connection.getRemoteAddress(), false);
    }

    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) return;
        event.getDispatcher().register(Commands.literal("bvp_ground_camera")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("hud")
                    .executes(context -> startHud(context.getSource().getPlayerOrException(),"m1_abrams_elite"))
                    .then(Commands.argument("vehicle",StringArgumentType.word()).executes(context ->
                        startHud(context.getSource().getPlayerOrException(),StringArgumentType.getString(context,"vehicle")))))
                .then(Commands.literal("module")
                    .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("health_percent", DoubleArgumentType.doubleArg(0,100))
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayerOrException();
                                Run run = active;
                                if (run == null || !run.hudOnly || run.player != player || !privatePlayer(player)
                                        || player.getVehicle() != run.vehicle ||
                                        !(run.vehicle instanceof ArmoredVehicleEntity armored)) return 0;
                                String id = StringArgumentType.getString(context,"id");
                                double maximum = switch(id) {
                                    case "engine" -> VehicleModuleHealth.ENGINE_HP;
                                    case "lefttrack", "righttrack" -> VehicleModuleHealth.TRACK_HP;
                                    case "weaponsystems" -> armored.hasBvpWeaponsSystemsModule()
                                            ? VehicleModuleHealth.WEAPONS_SYSTEMS_HP : 0;
                                    default -> 0;
                                };
                                if(maximum <= 0) return 0;
                                double percent = DoubleArgumentType.getDouble(context,"health_percent");
                                float before = armored.getModuleHealth(id);
                                armored.setModuleHealth(id,maximum*percent/100.0);
                                Map<String,Object> row = new LinkedHashMap<>();
                                row.put("tick",run.level.getGameTime()); row.put("event","HUD_MODULE_SETUP");
                                row.put("module",id); row.put("requestedHealthPercent",percent);
                                row.put("healthBefore",before); row.put("healthAfter",armored.getModuleHealth(id));
                                row.put("modules",armored.vehicleModuleHudState()); run.rows.add(row);
                                player.sendSystemMessage(Component.literal(id+" health="+armored.getModuleHealth(id)));
                                return 1;
                            }))))
                .then(Commands.argument("seat", IntegerArgumentType.integer(0, 1)).executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    if (active != null || !privatePlayer(player) || player.getVehicle() != null
                            || EliteDiagnostics.isServerEnabled()) return 0;
                    active = new Run(player, IntegerArgumentType.getInteger(context, "seat"));
                    try { active.prepare(); }
                    catch (Exception failure) { active.finish(failure.toString()); return 0; }
                    return 1;
                })));
    }

    private static int startHud(ServerPlayer player,String vehicleId) {
        if(!java.util.Set.of("m1_abrams_elite","m1128","m2_bradley").contains(vehicleId)
                || active != null || !privatePlayer(player) || player.getVehicle() != null
                || EliteDiagnostics.isServerEnabled()) return 0;
        active = new Run(player,0); active.hudOnly = true; active.vehicleId = vehicleId;
        try { active.prepare(); }
        catch(Exception failure) { active.finish(failure.toString()); return 0; }
        return 1;
    }

    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        Run run = active;
        if (run == null || run.server != event.getServer()) return;
        try {
            if (event.phase == TickEvent.Phase.START) run.controls();
            else run.sample();
        } catch (Exception failure) { run.finish(failure.toString()); }
    }

    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        if (active != null && active.server == event.getServer()) active.finish("SERVER_STOP");
    }

    private static final class Run {
        static final int X = 4096, Z = 4096, Y = -60;
        static final int[] STOPS = {0, 32, 64, 92, 126};
        static final String[] NAMES = {"flat", "uphill", "crest", "downhill", "flat_end"};
        final ServerPlayer player;
        final MinecraftServer server;
        final ServerLevel level;
        final int seat;
        final Vec3 savedPosition;
        final float savedYaw, savedPitch;
        final boolean savedFlying;
        final long started = System.nanoTime();
        final String startedUtc = Instant.now().toString();
        final List<BlockPos> blocks = new ArrayList<>();
        final List<ChunkPos> chunks = new ArrayList<>();
        final List<Map<String, Object>> rows = new ArrayList<>();
        VehicleEntity vehicle;
        Cow driver;
        int phase, held, ticks, travelTicks;
        boolean holding = true, capture;
        boolean hudOnly;
        String vehicleId = "m1_abrams_elite";

        Run(ServerPlayer player, int seat) {
            this.player = player; server = player.server; level = player.serverLevel(); this.seat = seat;
            savedPosition = player.position(); savedYaw = player.getYRot(); savedPitch = player.getXRot();
            savedFlying = player.getAbilities().flying;
        }

        void prepare() {
            // This fixed private fixture uses only empty space above the untouched superflat floor.
            for (int x = X - 12; x <= X + 12; x += 8) for (int z = Z - 12; z <= Z + 140; z += 8) {
                ChunkPos chunk = new ChunkPos(x >> 4, z >> 4);
                if (!level.getForcedChunks().contains(chunk.toLong())) {
                    level.setChunkForced(chunk.x, chunk.z, true); chunks.add(chunk);
                }
            }
            for (int dz = -10; dz <= 140; dz++) {
                int height = dz < 20 ? 0 : dz < 56 ? (dz - 20) / 3 : dz < 72 ? 12
                        : dz < 108 ? (108 - dz) / 3 : 0;
                for (int dx = -10; dx <= 10; dx++) for (int dy = 0; dy <= height; dy++) {
                    BlockPos point = new BlockPos(X + dx, Y + dy, Z + dz);
                    if (!level.getBlockState(point).isAir()) throw new IllegalStateException("Occupied fixture footprint " + point);
                    blocks.add(point);
                }
            }
            for (BlockPos point : blocks) level.setBlock(point, Blocks.STONE.defaultBlockState(), 3);
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(BertsVehiclePack.MODID, vehicleId));
            Entity entity = type == null ? null : type.create(level);
            if (!(entity instanceof VehicleEntity candidate)) throw new IllegalStateException("Abrams unavailable");
            vehicle = candidate; vehicle.load(new CompoundTag()); vehicle.moveTo(X, Y + 1, Z, 0, 0);
            vehicle.setEnergy(vehicle.getMaxEnergy()); setPhaseName();
            if (!level.addFreshEntity(vehicle)) throw new IllegalStateException("Spawn rejected");
            if (seat == 1) {
                driver = EntityType.COW.create(level);
                if (driver == null) throw new IllegalStateException("Driver unavailable");
                driver.setNoAi(true); driver.setInvulnerable(true); driver.setPos(vehicle.position());
                if (!level.addFreshEntity(driver) || !driver.startRiding(vehicle, true)) throw new IllegalStateException("Driver boarding rejected");
            }
            player.teleportTo(level, X, Y + 2, Z, 0, 0);
            if (!player.startRiding(vehicle, true) || vehicle.getSeatIndex(player) != seat)
                throw new IllegalStateException("Observer seat mismatch");
            EliteDiagnostics.INSTANCE.startForEntities(server, driver == null
                    ? java.util.Set.of(player.getUUID(), vehicle.getUUID())
                    : java.util.Set.of(player.getUUID(), vehicle.getUUID(), driver.getUUID()));
            capture = true;
            player.sendSystemMessage(Component.literal("Ground camera ramp started: seat " + seat));
        }

        void controls() {
            vehicle.setForwardInputDown(!holding && vehicle.getDeltaMovement().horizontalDistance() < .18);
            vehicle.setBackInputDown(false); vehicle.setLeftInputDown(false); vehicle.setRightInputDown(false);
            vehicle.setUpInputDown(holding); vehicle.setSprintInputDown(false);
        }

        void sample() {
            if (!privatePlayer(player) || player.getVehicle() != vehicle || vehicle.isRemoved()
                    || ++ticks > 3000 || System.nanoTime() - started > 180_000_000_000L)
                throw new IllegalStateException("Camera fixture context or duration limit");
            if (ticks % 2 == 0) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("tick", level.getGameTime()); row.put("elapsed", ticks); row.put("phase", NAMES[phase]);
                row.put("holding", holding); row.put("heldTicks", held);
                row.put("position", List.of(vehicle.getX(), vehicle.getY(), vehicle.getZ()));
                row.put("pitch", vehicle.getPitch(1)); row.put("roll", vehicle.getRoll(1));
                row.put("speed", vehicle.getDeltaMovement().length()); rows.add(row);
                if(vehicle instanceof ArmoredVehicleEntity armored) row.put("modules",armored.vehicleModuleHudState());
                row.put("primary",vehicle.getPrimaryWeaponIndex(seat));
                row.put("secondary",vehicle.getSecondaryWeaponIndex(seat));
            }
            if(hudOnly) {
                if(ticks >= 2800) finish(null);
                return;
            }
            if (holding) {
                if (++held >= 220) {
                    if (++phase == STOPS.length) { finish(null); return; }
                    holding = false; held = 0; travelTicks = 0; setPhaseName();
                }
            } else {
                if (++travelTicks > 650) throw new IllegalStateException("Ramp traversal timed out at " + NAMES[phase]);
                if (vehicle.getZ() >= Z + STOPS[phase]) { holding = true; held = 0; setPhaseName(); }
            }
        }

        void setPhaseName() {
            if(hudOnly) {
                vehicle.setCustomName(Component.literal("camera_probe:"+seat+":hud:hold"));
                return;
            }
            vehicle.setCustomName(Component.literal("camera_probe:" + seat + ":" + NAMES[phase] + ":" + (holding ? "hold" : "drive")));
        }

        void finish(String error) {
            if (active != this) return;
            active = null;
            try {
                Map<String, Object> report = new LinkedHashMap<>();
                report.put("startedUtc", startedUtc); report.put("error", error); report.put("seat", seat);
                report.put("vehicleType",vehicleId);
                report.put("vehicle", vehicle == null ? null : vehicle.getUUID().toString()); report.put("rows", rows);
                report.put("status", error == null ? "TRAVERSAL_COMPLETE_VISUAL_ACCEPTANCE_PENDING" : "ERROR");
                Path directory = Path.of("logs", "ground-camera-tests"); Files.createDirectories(directory);
                Files.writeString(directory.resolve("seat-" + seat + "-" + server.getTickCount() + ".json"), new Gson().toJson(report));
            } catch (Exception failure) {
                org.slf4j.LoggerFactory.getLogger(BvpGroundCameraScenario.class).error("Ground camera report failed", failure);
            } finally {
                player.stopRiding();
                if (driver != null) driver.discard();
                if (vehicle != null) vehicle.discard();
                for (BlockPos point : blocks) if (level.getBlockState(point).is(Blocks.STONE))
                    level.setBlock(point, Blocks.AIR.defaultBlockState(), 3);
                for (ChunkPos chunk : chunks) level.setChunkForced(chunk.x, chunk.z, false);
                player.getAbilities().flying = savedFlying; player.onUpdateAbilities();
                player.teleportTo(level, savedPosition.x, savedPosition.y, savedPosition.z, savedYaw, savedPitch);
                if (capture) EliteDiagnostics.INSTANCE.stop(server);
                player.sendSystemMessage(Component.literal("Ground camera traversal " + (error == null ? "complete" : error)));
            }
        }
    }
}
