package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.client.aircraft.AircraftArmamentClient;
import com.atsuishio.superbwarfare.client.input.VehicleControlProfile;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.event.ClientEventHandler;
import com.atsuishio.superbwarfare.init.ModKeyMappings;
import com.atsuishio.superbwarfare.tools.EntityFindUtil;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.InputConstants;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Private tick-spanning registered-input observations for already prepared disposable vehicles. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID, value = Dist.CLIENT)
public final class BvpVehicleControlProbe {
    private static final String PREFIX = "BVP_CONTROL_PROBE ";
    private static Active active;
    private static boolean injectedEdge;
    private static final LinkedHashSet<UUID> COMPLETED = new LinkedHashSet<>();

    private BvpVehicleControlProbe() { }

    private static final class Active {
        final String mode;
        final UUID run;
        final LocalPlayer player;
        final ClientLevel level;
        final ClientPacketListener connection;
        final VehicleEntity vehicle;
        final KeyMapping[] keys;
        final InputConstants.Key[] bindings;
        final boolean[] injected;
        final String[] labels;
        final int[] expectedBits;
        final List<Map<String, Object>> rows = new ArrayList<>();
        final long started = System.nanoTime();
        int tick;
        int mismatch;

        Active(Minecraft mc, String mode, UUID run, VehicleEntity vehicle) {
            this.mode = mode; this.run = run; player = mc.player; level = mc.level;
            connection = mc.getConnection(); this.vehicle = vehicle;
            if (mode.equals("drone")) {
                keys = new KeyMapping[]{ModKeyMappings.DRONE_ASCEND, ModKeyMappings.DRONE_FORWARD,
                        ModKeyMappings.DRONE_BACKWARD, ModKeyMappings.DRONE_LEFT,
                        ModKeyMappings.DRONE_RIGHT, ModKeyMappings.DRONE_DESCEND};
                labels = new String[]{"ascend", "forward", "backward", "left", "right", "descend"};
                expectedBits = new int[]{16, 4, 8, 1, 2, 32};
            } else {
                keys = new KeyMapping[]{ModKeyMappings.FLIGHT_THROTTLE_UP,
                        ModKeyMappings.FIXED_WING_PITCH_DOWN, ModKeyMappings.FIXED_WING_PITCH_UP,
                        ModKeyMappings.FIXED_WING_ROLL_LEFT, ModKeyMappings.FIXED_WING_ROLL_RIGHT};
                labels = new String[]{"throttle", "pitch_down", "pitch_up", "roll_left", "roll_right"};
                expectedBits = new int[]{4, 0, 0, 1, 2};
            }
            bindings = Arrays.stream(keys).map(KeyMapping::getKey).toArray(InputConstants.Key[]::new);
            injected = new boolean[keys.length];
            for (KeyMapping key : keys) {
                if (key.isDown() || key.getKey().getType() != InputConstants.Type.KEYSYM
                        || key.getKey().equals(InputConstants.UNKNOWN)
                        || key.getKeyModifier() != net.minecraftforge.client.settings.KeyModifier.NONE
                        || Arrays.stream(mc.options.keyMappings).noneMatch(k -> k == key))
                    throw new IllegalStateException("Released registered keyboard bindings required");
            }
        }
    }

    private static boolean privateClient(Minecraft mc) {
        return BvpFlightClientControl.enabled() && mc.player != null && mc.level != null
                && mc.getConnection() != null && mc.player.isCreative() && !mc.player.isSpectator()
                && BvpFireTrafficControl.identity(mc.player.getGameProfile().getName(), mc.player.getUUID())
                && "0".equals(mc.getUser().getAccessToken())
                && BvpFireTrafficControl.loopback(mc.getConnection().getConnection().getRemoteAddress(), true)
                && mc.level.dimension() == Level.OVERWORLD;
    }

    private static VehicleEntity target(Minecraft mc, String mode) {
        if (mode.equals("drone") && VehicleControlProfile.controlsLinkedDrone(mc.player)) {
            VehicleEntity drone = EntityFindUtil.findDrone(mc.level,
                    mc.player.getMainHandItem().getTag().getString("LinkedDrone"));
            return drone != null
                    && "superbwarfare:drone".equals(String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(drone.getType())))
                    && mc.player.getVehicle() == null ? drone : null;
        }
        if (mode.equals("pod") && mc.player.getVehicle() instanceof VehicleEntity vehicle
                && vehicle.isFixedWingFlightVehicle()
                && vehicle.getNthEntity(0) == mc.player && AircraftArmamentClient.isPodActive(vehicle)) return vehicle;
        return null;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void message(ClientChatReceivedEvent event) {
        String text = event.getMessage().getString();
        Minecraft mc = Minecraft.getInstance();
        if (!event.isSystem() || !text.startsWith(PREFIX) || !privateClient(mc)) return;
        String marker = text.substring(PREFIX.length());
        if (marker.equals("stop")) { stop("OPERATOR_STOP"); event.setCanceled(true); return; }
        if (active != null || marker.length() > 160) return;
        String[] fields = marker.split(" ", -1);
        if (fields.length != 4 || !(fields[0].equals("drone") || fields[0].equals("pod"))) return;
        String mode = fields[0];
        try {
            UUID run = UUID.fromString(fields[1]);
            UUID expectedVehicle = UUID.fromString(fields[2]);
            int entityId = Integer.parseInt(fields[3]);
            if (COMPLETED.contains(run)) return;
            VehicleEntity vehicle = target(mc, mode);
            if (vehicle == null || !vehicle.getUUID().equals(expectedVehicle) || vehicle.getId() != entityId
                    || !EliteDiagnostics.isClientEnabled() || mc.screen != null
                    || !mc.isWindowActive() || !mc.mouseHandler.isMouseGrabbed())
                throw new IllegalStateException("Exact server-admitted drone monitor or pilot pod, capture and focus required");
            active = new Active(mc, mode, run, vehicle);
            mc.player.displayClientMessage(Component.literal("Private " + mode + " hold probe started"), false);
        } catch (RuntimeException failure) {
            mc.player.displayClientMessage(Component.literal("Control probe refused: " + failure.getMessage()), false);
        }
        event.setCanceled(true);
    }

    private static boolean valid(Minecraft mc, Active state) {
        if (!privateClient(mc) || mc.player != state.player || mc.level != state.level
                || mc.getConnection() != state.connection || target(mc, state.mode) != state.vehicle
                || state.vehicle.isRemoved() || state.vehicle.isWreck() || !state.player.isAlive()
                || mc.screen != null || !mc.isWindowActive() || !mc.mouseHandler.isMouseGrabbed()
                || !EliteDiagnostics.isClientEnabled() || System.nanoTime() - state.started > 25_000_000_000L)
            return false;
        for (int i = 0; i < state.keys.length; i++)
            if (!state.keys[i].getKey().equals(state.bindings[i])) return false;
        return true;
    }

    private static void key(Active state, int index, boolean down) {
        state.keys[index].setDown(down);
        if (state.injected[index] == down) return;
        state.injected[index] = down; injectedEdge = true;
        try {
            MinecraftForge.EVENT_BUS.post(new InputEvent.Key(state.bindings[index].getValue(), 0,
                    down ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE, 0));
        } finally { injectedEdge = false; }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void before(TickEvent.ClientTickEvent event) {
        Active state = active;
        if (state == null || event.phase != TickEvent.Phase.END) return;
        try {
            if (!valid(Minecraft.getInstance(), state)) { stop("CONTEXT_LOST"); return; }
            if (state.tick >= 20 + state.keys.length * 40) { stop("COMPLETE_REVIEW_MOVEMENT"); return; }
            int index = state.tick < 20 ? -1 : (state.tick - 20) / 40;
            boolean held = state.tick >= 20 && (state.tick - 20) % 40 < 16;
            for (int i = 0; i < state.keys.length; i++) key(state, i, held && i == index);
        } catch (RuntimeException | LinkageError failure) { stop("INPUT_ERROR_" + failure.getClass().getSimpleName()); }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void after(TickEvent.ClientTickEvent event) {
        Active state = active;
        if (state == null || event.phase != TickEvent.Phase.END) return;
        try {
            int index = state.tick < 20 ? -1 : (state.tick - 20) / 40;
            boolean held = state.tick >= 20 && (state.tick - 20) % 40 < 16;
            int expected = held ? state.expectedBits[index] : 0;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tick", state.tick); row.put("gameTick", state.level.getGameTime());
            row.put("stage", index < 0 ? "baseline" : state.labels[index]); row.put("held", held);
            row.put("expectedBits", expected); row.put("handlerBits", ClientEventHandler.keysCache);
            row.put("position", List.of(state.vehicle.getX(), state.vehicle.getY(), state.vehicle.getZ()));
            var motion = state.vehicle.getDeltaMovement();
            row.put("motion", List.of(motion.x, motion.y, motion.z));
            row.put("podActive", AircraftArmamentClient.isPodActive(state.vehicle));
            if (state.mode.equals("pod")) {
                var snapshot = state.vehicle.getVehicleFlightInstrumentSnapshot(1F);
                row.put("serverTick", snapshot.getServerTick());
                var controls = snapshot.getControlSurfaces();
                if (controls != null) row.put("controls", List.of(controls.getElevator(), controls.getAileron(),
                        controls.getRudder(), controls.getThrottle()));
            }
            state.rows.add(row);
            state.mismatch = ClientEventHandler.keysCache == expected ? 0 : state.mismatch + 1;
            if (state.mismatch >= 3) { stop("INPUT_PATH_MISMATCH"); return; }
            state.tick++;
        } catch (RuntimeException | LinkageError failure) { stop("OBSERVATION_ERROR_" + failure.getClass().getSimpleName()); }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void key(InputEvent.Key event) { if (active != null && !injectedEdge) stop("PHYSICAL_KEY_INPUT"); }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void button(InputEvent.MouseButton.Pre event) { if (active != null) stop("PHYSICAL_MOUSE_INPUT"); }
    @SubscribeEvent public static void unload(LevelEvent.Unload event) {
        if (active != null && event.getLevel() == active.level) stop("UNLOAD");
    }

    private static void stop(String reason) {
        Active state = active;
        if (state == null) return;
        active = null;
        COMPLETED.add(state.run);
        while (COMPLETED.size() > 32) COMPLETED.remove(COMPLETED.iterator().next());
        List<String> cleanupErrors = new ArrayList<>();
        for (int i = 0; i < state.keys.length; i++) {
            try { key(state, i, false); }
            catch (RuntimeException | LinkageError failure) { cleanupErrors.add(failure.toString()); }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", "bvp-vehicle-control-probe-v1"); report.put("run", state.run);
        report.put("mode", state.mode); report.put("reason", reason); report.put("finishedUtc", Instant.now().toString());
        report.put("vehicleUuid", state.vehicle.getUUID()); report.put("rows", state.rows);
        report.put("cleanupErrors", cleanupErrors);
        report.put("keysReleased", Arrays.stream(state.keys).noneMatch(KeyMapping::isDown));
        report.put("motionForced", false);
        report.put("inputPath", "registered_key_states_and_Forge_edges_normal_END_transport");
        try {
            Path path = Minecraft.getInstance().gameDirectory.toPath().resolve("logs/fixed-wing-tests")
                    .resolve("control-probe-" + state.mode + "-" + state.run + ".json");
            Files.createDirectories(path.getParent());
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(report));
            state.player.displayClientMessage(Component.literal("Private control probe: " + reason), false);
        } catch (Exception failure) {
            state.player.displayClientMessage(Component.literal("Control probe report failed: " + failure), false);
        }
    }
}
