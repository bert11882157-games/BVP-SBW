package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;

import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.google.gson.Gson;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import com.yourname.berts_vehicle_pack.item.BvpVehicleItem;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
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
import java.util.Set;
import java.util.UUID;

/** Private item-placement observation. Uses the normal item callback without loading synthetic entity NBT. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID)
public final class BvpItemPlacementScenario {
    private static final Set<String> TYPES = Set.of("btr_90", "leopard_2a4", "k2a1_black_panther", "m109a7_paladin", "marder_1a5", "zbd_09", "ztl_09", "pzh_2000",
            "qn_506model", "vt_4a1", "cv9040_no_net", "leo2a6", "toyota_jihad_bmp1",
            "toyota_jihad_dshk", "toyota_jihad_s5", "toyota_jihad_spg9", "zu23_2",
            "kord_tripod", "browning_tripod", "milan_tripod", "tow_tripod", "spg9_tripod",
            "vbci", "marder_1a2", "t72a", "tunguska",
            "challenger_2", "leclerc_s1",
            "ho_229", "ju_87_b2");
    private static Run active;

    private BvpItemPlacementScenario() { }

    private static boolean admitted(ServerPlayer player) {
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
        event.getDispatcher().register(Commands.literal("bvp_module_hud")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("type", StringArgumentType.word()).executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    String type = StringArgumentType.getString(context, "type");
                    if (active != null || !TYPES.contains(type) || !admitted(player)
                            || player.getVehicle() != null || EliteDiagnostics.isServerEnabled()) return 0;
                    active = new Run(player, type, 0);
                    active.moduleHud = true;
                    try { active.prepare(); }
                    catch (Exception failure) { active.finish(failure.toString()); return 0; }
                    return 1;
                })));
        event.getDispatcher().register(Commands.literal("bvp_item_placement")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("type", StringArgumentType.word()).executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    String type = StringArgumentType.getString(context, "type");
                    if (active != null || !TYPES.contains(type) || !admitted(player) || player.getVehicle() != null
                            || EliteDiagnostics.isServerEnabled()) return 0;
                    active = new Run(player, type);
                    try { active.prepare(); }
                    catch (Exception failure) { active.finish(failure.toString()); return 0; }
                    return 1;
                })));
        for (String command : List.of("bvp_station_view", "bvp_station_operate")) {
        event.getDispatcher().register(Commands.literal(command)
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("type", StringArgumentType.word())
                        .then(Commands.argument("seat", IntegerArgumentType.integer(0, 15)).executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            String type = StringArgumentType.getString(context, "type");
                            if (active != null || !TYPES.contains(type) || !admitted(player)
                                    || player.getVehicle() != null || EliteDiagnostics.isServerEnabled()) return 0;
                            active = new Run(player, type, IntegerArgumentType.getInteger(context, "seat"));
                            active.operation = command.equals("bvp_station_operate");
                            try { active.prepare(); }
                            catch (Exception failure) { active.finish(failure.toString()); return 0; }
                            return 1;
                        }))));
        }
    }

    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || active == null || active.server != event.getServer()) return;
        try { active.sample(); }
        catch (Exception failure) { active.finish(failure.toString()); }
    }

    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        if (active != null && active.server == event.getServer()) active.finish("SERVER_STOP");
    }

    private static final class Run {
        final ServerPlayer player;
        final MinecraftServer server;
        final ServerLevel level;
        final String type;
        final int station;
        final Vec3 savedPosition;
        final float savedYaw, savedPitch;
        final boolean savedFlying;
        final List<Map<String, Object>> samples = new ArrayList<>();
        final Map<String, Object> report = new LinkedHashMap<>();
        final List<VehicleEntity> owned = new ArrayList<>();
        final List<Entity> stationCrew = new ArrayList<>();
        VehicleEntity vehicle;
        int ticks;
        boolean capture;
        boolean moduleHud;
        boolean operation;

        Run(ServerPlayer player, String type) {
            this(player, type, -1);
        }

        Run(ServerPlayer player, String type, int station) {
            this.player = player; this.type = type; server = player.server; level = player.serverLevel();
            this.station = station;
            savedPosition = player.position(); savedYaw = player.getYRot(); savedPitch = player.getXRot();
            savedFlying = player.getAbilities().flying;
        }

        void prepare() {
            int x = (int) Math.floor(savedPosition.x) + 96;
            int z = (int) Math.floor(savedPosition.z) + 96;
            level.getChunk(x >> 4, z >> 4);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos support = new BlockPos(x, y - 1, z);
            if (level.getBlockState(support).getCollisionShape(level, support).isEmpty()) {
                throw new IllegalStateException("No solid placement support");
            }
            AABB search = new AABB(x - 24, y - 8, z - 24, x + 24, y + 24, z + 24);
            if (!level.getEntitiesOfClass(VehicleEntity.class, search).isEmpty()) {
                throw new IllegalStateException("Placement space already occupied");
            }
            EntityType<?> entityType = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(BertsVehiclePack.MODID, type));
            if (entityType == null) throw new IllegalStateException("Missing type " + type);
            ItemStack item = BvpVehicleItem.create(entityType);
            if (item.isEmpty() || BvpVehicleItem.getDurableState(item) != null) {
                throw new IllegalStateException("Expected fresh creative vehicle item");
            }
            player.getAbilities().flying = true; player.onUpdateAbilities();
            player.teleportTo(level, x + 0.5, y + 2.0, z - 4.0, 0.0f, 35.0f);
            if (station < 0) EliteDiagnostics.INSTANCE.start(server);
            else EliteDiagnostics.INSTANCE.startForEntities(server, Set.of(player.getUUID()));
            capture = true;
            report.put("type", type);
            report.put("startedUtc", Instant.now().toString());
            report.put("route", "fresh BvpVehicleItem.create -> ItemStack.useOn -> normal lifecycle provider");
            report.put("boundary", station < 0
                    ? "server item callback; no entity.load, forced pose, riding, firing or synthetic durable state"
                    : "normal item placement and boarding; no pose writes, firing or synthetic durable state");
            report.put("station", station);
            ItemStack savedHand = player.getMainHandItem();
            InteractionResult result;
            try {
                player.setItemInHand(InteractionHand.MAIN_HAND, item);
                BlockHitResult hit = new BlockHitResult(new Vec3(x + 0.5, y, z + 0.5), Direction.UP, support, false);
                result = item.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
            } finally {
                owned.addAll(level.getEntitiesOfClass(VehicleEntity.class, search));
                player.setItemInHand(InteractionHand.MAIN_HAND, savedHand);
                player.inventoryMenu.broadcastChanges();
            }
            report.put("interactionResult", result.toString());
            report.put("createdCount", owned.size());
            if (!result.consumesAction() || owned.size() != 1 || owned.get(0).getType() != entityType) {
                throw new IllegalStateException("Normal placement did not create exactly the requested type");
            }
            vehicle = owned.get(0);
            report.put("uuid", vehicle.getUUID().toString());
            report.put("entityId", vehicle.getId());
            report.put("initialHealth", vehicle.getHealth());
            if (station < 0) player.teleportTo(level, x + 9.0, y + 4.0, z - 13.0, 35.0f, 12.0f);
            else {
                EliteDiagnostics.includeServerEntity(vehicle.getUUID());
                if (!operation) vehicle.setCustomName(net.minecraft.network.chat.Component.literal(
                        moduleHud ? "camera_probe:0:hud:hold"
                                : "camera_probe:" + station + ":station_" + type + ":hold"));
                for (int index = 0; index < station; index++) {
                    Cow crew = EntityType.COW.create(level);
                    if (crew == null) throw new IllegalStateException("Station crew unavailable");
                    crew.setNoAi(true); crew.setInvisible(true); crew.setInvulnerable(true);
                    crew.setPos(vehicle.position()); stationCrew.add(crew);
                    EliteDiagnostics.includeServerEntity(crew.getUUID());
                    if (!level.addFreshEntity(crew) || !crew.startRiding(vehicle, true))
                        throw new IllegalStateException("Station crew boarding rejected");
                }
                player.teleportTo(level, x + 0.5, y + 2.0, z + 0.5, 0, 0);
                if (!player.startRiding(vehicle, true) || vehicle.getSeatIndex(player) != station)
                    throw new IllegalStateException("Requested station boarding rejected");
                report.put("boardingRoute", "ordinary startRiding; invisible no-AI crew occupy earlier seats");
                report.put("cameraProbe", "existing private camera probe samples parent transforms and normal zoom edges");
                if (operation) {
                    for (String weapon : vehicle.computed().seats().get(station).weapons()) {
                        vehicle.modifyGunData(weapon, gun -> {
                            gun.resetStatus(); gun.ammo.set(Math.max(1, gun.get(GunProp.MAGAZINE)));
                            gun.virtualAmmo.set(0); gun.heat.set(0); gun.overHeat.set(false);
                        });
                    }
                    report.put("boundary", "normal placement, boarding and real client input; one finite magazine prefilled once; no aim or pose writes");
                    report.put("cameraProbe", "disabled for hands-on operation; actual screenshots required");
                }
            }
            EliteDiagnostics.record(player, "item_placement", "PLACED", "vehicle", vehicle.getUUID(),
                    "vehicle_type", type, "route", "item_use_on", "position", vehicle.position());
        }

        void sample() {
            if (!admitted(player) || vehicle == null || vehicle.isRemoved()
                    || (station < 0 ? player.getVehicle() != null
                    : player.getVehicle() != vehicle || vehicle.getSeatIndex(player) != station)) {
                finish("LOST_CONTEXT_OR_VEHICLE"); return;
            }
            ticks++;
            if (moduleHud && (ticks == 100 || ticks == 200)
                    && vehicle instanceof ArmoredVehicleEntity armored) {
                var modules = armored.vehicleModuleHudLayout(1.0F);
                for (int index = 0; index < modules.size(); index++) {
                    var module = modules.get(index);
                    float fraction = ticks == 200 ? 1.0F : switch (index % 4) {
                        case 0 -> 0.0F;
                        case 1 -> 0.5F;
                        case 2 -> 0.2F;
                        default -> 1.0F;
                    };
                    armored.setModuleHealth(module.getId(), module.getHealth().getMaximum() * fraction);
                }
                EliteDiagnostics.record(vehicle, "ground_hud_test", "HEALTH_PHASE",
                        "phase", ticks == 200 ? "repaired" : "mixed_damage", "modules", modules.size(),
                        "boundary", "server module setter and normal state synchronization; not a projectile damage test");
            }
            if (ticks <= 10 || ticks % 10 == 0) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("tick", ticks); row.put("entityTick", vehicle.tickCount);
                row.put("x", vehicle.getX()); row.put("y", vehicle.getY()); row.put("z", vehicle.getZ());
                row.put("health", vehicle.getHealth()); row.put("wreck", vehicle.isWreck());
                row.put("invisible", vehicle.isInvisible());
                if (station >= 0) {
                    // Mounted-entity NBT replaces rider X/Z with mount X/Z; observe live positions directly.
                    row.put("worldTick", level.getGameTime());
                    row.put("riderPosition", List.of(player.getX(), player.getY(), player.getZ()));
                    var seatPose = vehicle.resolveVehicleSeatPose(player, 1.0F, false);
                    if (seatPose != null) {
                        Vec3 body = seatPose.getBodyPosition();
                        row.put("resolvedRiderBody", List.of(body.x, body.y, body.z));
                    }
                }
                samples.add(row);
                BvpVehicleDataDiagnostic.record(vehicle);
            }
            if (ticks >= (operation ? 900 : 300)) finish(null);
        }

        void finish(String error) {
            if (active != this) return;
            active = null;
            try {
                report.put("status", error == null ? "PLACED_OBSERVED_VISUAL_ACCEPTANCE_REQUIRED" : "ERROR");
                report.put("error", error); report.put("samples", samples);
                report.put("renderAcceptance", "Requires matched client geometry telemetry and actual pixels");
                Path directory = Path.of("bvp-diagnostics", "item-placement");
                Files.createDirectories(directory);
                Files.writeString(directory.resolve(type + "-" + UUID.randomUUID() + ".json"), new Gson().toJson(report));
            } catch (Exception failure) { com.mojang.logging.LogUtils.getLogger().error("Item placement diagnostic report failed", failure); }
            finally {
                if (station >= 0 && player.getVehicle() == vehicle) player.stopRiding();
                for (Entity crew : stationCrew) if (!crew.isRemoved()) crew.discard();
                for (Entity entity : owned) if (!entity.isRemoved()) entity.discard();
                if (capture) EliteDiagnostics.INSTANCE.stop(server);
                if (!player.isRemoved()) {
                    player.teleportTo(level, savedPosition.x, savedPosition.y, savedPosition.z, savedYaw, savedPitch);
                    player.getAbilities().flying = savedFlying; player.onUpdateAbilities();
                }
            }
        }
    }
}
