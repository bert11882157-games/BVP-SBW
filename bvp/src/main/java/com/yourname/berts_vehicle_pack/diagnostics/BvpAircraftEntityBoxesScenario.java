package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
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

/** Private acceptance of real Entity.move against the aircraft's physical volumes. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID)
public final class BvpAircraftEntityBoxesScenario {
    private static final Set<String> TYPES = Set.of("mig_19s", "mi_24d", "il_76m");
    private static final double HALF = 0.015;

    private static boolean admitted(ServerPlayer player) {
        var server = player.server;
        return DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios") && server.isDedicatedServer()
                && !server.usesAuthentication() && "127.0.0.1".equals(server.getLocalIp())
                && server.getPort() == BvpFireTrafficControl.PORT && server.getPlayerCount() == 1
                && player.level().dimension() == Level.OVERWORLD && player.isAlive()
                && !player.isSpectator() && player.getAbilities().instabuild && player.getVehicle() == null
                && BvpFireTrafficControl.identity(player.getGameProfile().getName(), player.getUUID())
                && BvpFireTrafficControl.loopback(player.connection.connection.getRemoteAddress(), false);
    }

    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) return;
        event.getDispatcher().register(Commands.literal("bvp_aircraft_entity_boxes")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("type", StringArgumentType.word()).executes(context -> {
                    var player = context.getSource().getPlayerOrException();
                    String type = StringArgumentType.getString(context, "type");
                    if (!admitted(player) || !TYPES.contains(type)) return 0;
                    return run(player, type);
                })));
    }

    private static int run(ServerPlayer player, String type) {
        var level = player.serverLevel();
        var rows = new ArrayList<Map<String, Object>>();
        var report = new LinkedHashMap<String, Object>();
        VehicleEntity aircraft = null;
        String error = null;
        try {
            var entityType = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(BertsVehiclePack.MODID, type));
            if (entityType == null || !(entityType.create(level) instanceof VehicleEntity created))
                throw new IllegalStateException("Aircraft unavailable: " + type);
            aircraft = created;
            aircraft.moveTo(player.getX() + 20, 220, player.getZ(), 0, 0);
            aircraft.setNoGravity(true);
            aircraft.setSynchedGearRot(0);
            if (!level.addFreshEntity(aircraft)) throw new IllegalStateException("Aircraft spawn failed");
            var snapshot = aircraft.getAircraftCollisionSnapshot(1F);
            if (snapshot == null || snapshot.getParts().size() != 2) throw new IllegalStateException("Expected two definitions");
            var body = snapshot.getParts().stream().filter(p -> p.getRole().name().equals("FUSELAGE")).findFirst().orElseThrow();
            var gear = snapshot.getParts().stream().filter(p -> p.getRole().name().equals("LANDING_GEAR")).findFirst().orElseThrow();
            AABB b = body.getWorldBounds(), g = gear.getWorldBounds(), q = snapshot.getQueryBounds();
            check(rows, "both_parts_active", body.getActive() && gear.getActive());
            check(rows, "public_bounds_match_physical_union", same(aircraft.getBoundingBox(), q));
            check(rows, "centered_nested_footprints", Math.abs(b.getCenter().x-g.getCenter().x)<1e-6
                    && Math.abs(b.getCenter().z-g.getCenter().z)<1e-6
                    && g.minX>b.minX && g.maxX<b.maxX && g.minZ>b.minZ && g.maxZ<b.maxZ && g.minY<b.minY);
            double gearY = (g.minY + Math.min(g.maxY, b.minY)) * 0.5;
            moveProbe(player, rows, "fuselage_blocks_entity", q.minX-2, b.getCenter().y, b.getCenter().z,
                    new Vec3(q.getXsize()+4, 0, 0), true);
            moveProbe(player, rows, "fuselage_far_end_blocks_entity", q.minX-2, b.getCenter().y, b.maxZ-0.2,
                    new Vec3(q.getXsize()+4, 0, 0), true);
            moveProbe(player, rows, "gear_blocks_entity", q.minX-2, gearY, g.getCenter().z,
                    new Vec3(q.getXsize()+4, 0, 0), true);
            double gapX = (b.maxX + g.maxX) * 0.5;
            check(rows, "probe_fits_empty_inset", (b.maxX-g.maxX)*0.5 > HALF*2);
            moveProbe(player, rows, "empty_envelope_inset_is_not_solid", gapX, gearY, q.minZ-2,
                    new Vec3(0, 0, q.getZsize()+4), false);
            aircraft.setSynchedGearRot(1);
            aircraft.setPos(aircraft.getX(), aircraft.getY(), aircraft.getZ());
            var retracted = aircraft.getAircraftCollisionSnapshot(1F);
            boolean retractable = aircraft.computed().getAircraftTerrainContact().getRetractableGear();
            check(rows, "gear_activation_matches_policy", retracted.getParts().stream()
                    .filter(p -> p.getRole().name().equals("LANDING_GEAR")).findFirst().orElseThrow().getActive() != retractable);
            moveProbe(player, rows, "gear_retraction_changes_physical_collision", q.minX-2, gearY, g.getCenter().z,
                    new Vec3(q.getXsize()+4, 0, 0), !retractable);
            aircraft.setSynchedGearRot(0);
            aircraft.setYRot(37);
            aircraft.setXRot(12);
            aircraft.setPos(aircraft.getX()+9, aircraft.getY()+3, aircraft.getZ()-7);
            var moved = aircraft.getAircraftCollisionSnapshot(1F);
            check(rows, "moved_rotated_public_bounds_match", same(aircraft.getBoundingBox(), moved.getQueryBounds()));
            check(rows, "moved_bounds_contain_all_active_corners", moved.getParts().stream().filter(p -> p.getActive())
                    .flatMap(p -> p.getWorldVertices().stream()).allMatch(v -> moved.getQueryBounds().inflate(1e-6).contains(v)));
        } catch (Exception failure) {
            error = failure.toString();
        } finally {
            if (aircraft != null) aircraft.discard();
        }
        boolean pass = error == null && rows.stream().allMatch(row -> Boolean.TRUE.equals(row.get("pass")));
        report.put("status", pass ? "PASS" : "FAIL"); report.put("type", type);
        report.put("time", Instant.now().toString()); report.put("error", error); report.put("checks", rows);
        report.put("method", "Disposable item entity with a 0.03m diagnostic probe box; ordinary Entity.move, no teleport during measured movement");
        try {
            Path path = Path.of("logs", "aircraft-entity-box-tests", type+"-"+player.server.getTickCount()+".json");
            Files.createDirectories(path.getParent());
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(report));
        } catch (Exception failure) { pass = false; }
        player.sendSystemMessage(Component.literal("Aircraft entity boxes " + type + ": " + (pass ? "PASS" : "FAIL")));
        return pass ? 1 : 0;
    }

    private static void moveProbe(ServerPlayer player, List<Map<String, Object>> rows, String name,
                                  double x, double y, double z, Vec3 requested, boolean expectBlocked) {
        var probe = new ItemEntity(player.serverLevel(), x, y-HALF, z, new ItemStack(Items.STONE));
        probe.setNoGravity(true); probe.noPhysics = false; probe.setDeltaMovement(Vec3.ZERO);
        try {
            if (!player.serverLevel().addFreshEntity(probe)) throw new IllegalStateException("Probe spawn failed");
            probe.setBoundingBox(new AABB(x-HALF,y-HALF,z-HALF,x+HALF,y+HALF,z+HALF));
            Vec3 before = probe.position();
            probe.move(MoverType.SELF, requested);
            Vec3 actual = probe.position().subtract(before);
            boolean blocked = actual.distanceTo(requested)>1e-5;
            var row = new LinkedHashMap<String, Object>();
            row.put("name", name); row.put("pass", blocked == expectBlocked);
            row.put("requested", List.of(requested.x,requested.y,requested.z));
            row.put("actual", List.of(actual.x,actual.y,actual.z)); rows.add(row);
        } finally { probe.discard(); }
    }

    private static boolean same(AABB a, AABB b) {
        return Math.abs(a.minX-b.minX)+Math.abs(a.minY-b.minY)+Math.abs(a.minZ-b.minZ)
                +Math.abs(a.maxX-b.maxX)+Math.abs(a.maxY-b.maxY)+Math.abs(a.maxZ-b.maxZ)<1e-5;
    }
    private static void check(List<Map<String, Object>> rows, String name, boolean pass) {
        rows.add(Map.of("name", name, "pass", pass));
    }
}
