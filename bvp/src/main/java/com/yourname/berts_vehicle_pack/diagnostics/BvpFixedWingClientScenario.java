package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.api.vehicle.flight.VehicleFlightInstrumentSnapshot;
import com.atsuishio.superbwarfare.client.FixedWingPilotIntentClient;
import com.atsuishio.superbwarfare.client.MouseMovementHandler;
import com.atsuishio.superbwarfare.client.camera.VehicleFreeCameraController;
import com.atsuishio.superbwarfare.client.camera.FixedWingDynamicCamera;
import com.atsuishio.superbwarfare.client.input.FixedWingJoystickSensitivity;
import com.atsuishio.superbwarfare.client.input.FixedWingPitchControl;
import com.atsuishio.superbwarfare.client.input.FixedWingMouseAimInput;
import com.atsuishio.superbwarfare.client.input.FixedWingMouseAimMath;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.event.ClientEventHandler;
import com.atsuishio.superbwarfare.event.ClientMouseHandler;
import com.atsuishio.superbwarfare.init.ModKeyMappings;
import com.atsuishio.superbwarfare.network.FixedWingPilotIntentClientStream;
import com.google.gson.Gson;
import com.mojang.blaze3d.platform.InputConstants;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
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

/** Private registered-input probe. Production handlers alone collect, queue and send pilot intent. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID, value = Dist.CLIENT)
public final class BvpFixedWingClientScenario {
    private static final int MAX_TICK_ROWS = 5_000;
    private static final int MAX_RENDER_ROWS = 2_700;
    private static final int MAX_AIM_FRAMES = 45_000;
    private static final int MAX_REPORT_BYTES = 48 * 1024 * 1024;
    private static final LinkedHashSet<UUID> COMPLETED = new LinkedHashSet<>();
    private static Active active;
    private static boolean injectedEdge;
    private BvpFixedWingClientScenario() { }

    private static final class InputPauseScreen extends Screen {
        InputPauseScreen() { super(Component.literal("Private flight input release probe")); }
        @Override public boolean isPauseScreen() { return false; }
    }

    private static final class Active {
        final BvpFlightClientControl.Session session;
        final ClientPacketListener connection;
        final ClientLevel level;
        final LocalPlayer player;
        final VehicleEntity vehicle;
        final KeyMapping[] keys;
        final InputConstants.Key[] bindings;
        final boolean[] injected;
        final List<Map<String, Object>> bindingEvidence = new ArrayList<>();
        final CameraType savedView;
        final List<Map<String, Object>> ticks = new ArrayList<>();
        final List<Map<String, Object>> renders = new ArrayList<>();
        final List<Map<String, Object>> aimFrames = new ArrayList<>();
        final Map<Integer, Map<String, Object>> framebufferCaptures = new LinkedHashMap<>();
        final long startedNanos = System.nanoTime();
        final String startedUtc = Instant.now().toString();
        String acknowledge = "ARM";
        int acknowledgementTick = Integer.MIN_VALUE;
        int armReadyTicks;
        int mismatchTicks;
        int mouseStallTicks;
        int drivenTick = Integer.MIN_VALUE;
        int drivenStage = Integer.MIN_VALUE;
        int observedTick = Integer.MIN_VALUE;
        long lastRenderNanos;
        long lastRenderInputNanos = startedNanos;
        long renderInputSamples;
        double renderInputX;
        double renderInputY;
        long epoch;
        long acceptedSequence = -1;
        boolean renderedBasis;
        boolean setupPoseReady;
        int setupPoseRenderTick = Integer.MIN_VALUE;
        boolean centerPendingSeen;
        boolean skipMouseOnce;
        double requestedX;
        double requestedY;
        Double waypointErrorDegrees;
        int expectedBits;
        int expectedManualMask;
        boolean expectedFreeCamera;
        InputPauseScreen pauseScreen;
        FixedWingPilotIntentClientStream.View previousIntent;

        Active(Minecraft mc, VehicleEntity vehicle, BvpFlightClientControl.Marker marker,
               BvpFlightClientPlan plan) {
            session = new BvpFlightClientControl.Session(marker, plan, System.nanoTime());
            connection = mc.getConnection(); level = mc.level; player = mc.player; this.vehicle = vehicle;
            keys = new KeyMapping[]{ModKeyMappings.FLIGHT_THROTTLE_UP, ModKeyMappings.FLIGHT_THROTTLE_DOWN,
                    ModKeyMappings.FIXED_WING_PITCH_DOWN, ModKeyMappings.FIXED_WING_PITCH_UP,
                    ModKeyMappings.FIXED_WING_ROLL_LEFT, ModKeyMappings.FIXED_WING_ROLL_RIGHT,
                    ModKeyMappings.FIXED_WING_LANDING_GEAR, ModKeyMappings.FREE_CAMERA, ModKeyMappings.FLIGHT_RECENTER};
            bindings = Arrays.stream(keys).map(KeyMapping::getKey).toArray(InputConstants.Key[]::new);
            injected = new boolean[keys.length];
            int[] defaults = {GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_W,
                    GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_G, GLFW.GLFW_KEY_C, GLFW.GLFW_KEY_HOME};
            String[] channels = {"THROTTLE_UP", "THROTTLE_DOWN", "PITCH_DOWN", "PITCH_UP",
                    "ROLL_LEFT", "ROLL_RIGHT", "GEAR_EDGE", "FREE_CAMERA", "RESET_FORWARD"};
            for (int index = 0; index < keys.length; index++) {
                KeyMapping key = keys[index];
                if (Arrays.stream(mc.options.keyMappings).noneMatch(registered -> registered == key)
                        || key.isDown() || key.getKeyModifier() != net.minecraftforge.client.settings.KeyModifier.NONE
                        || key.getKey().getType() != InputConstants.Type.KEYSYM || key.getKey().getValue() != defaults[index])
                    throw new IllegalStateException("Use released default registered flight bindings for the private fixture");
                bindingEvidence.add(Map.of("channel", channels[index], "mapping", key.getName(),
                        "category", key.getCategory(), "keyCode", bindings[index].getValue(),
                        "modifier", key.getKeyModifier().name()));
            }
            if (plan.stages.stream().anyMatch(stage -> stage.landingGear) && !vehicle.hasFixedWingLandingGear())
                throw new IllegalStateException("Landing-gear stage requires the authoritative typed capability");
            savedView = mc.options.getCameraType();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void message(ClientChatReceivedEvent event) {
        if (!BvpFlightClientControl.enabled() || !event.isSystem()) return;
        BvpFlightClientControl.Marker marker = BvpFlightClientControl.parse(event.getMessage().getString());
        if (marker == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (!privateClient(mc)) return;
        try {
            if (active == null) {
                if (!marker.phase().equals("ARM") || COMPLETED.contains(marker.run())) return;
                BvpFlightClientPlan.Loaded loaded = BvpFlightClientPlan.load(
                        mc.gameDirectory.toPath().resolve("config/bvp-flight-tests"), marker.label());
                if (!loaded.sha256().equals(marker.digest())) throw new IllegalStateException("Plan digest mismatch");
                if (!(mc.player.getVehicle() instanceof VehicleEntity vehicle)
                        || vehicle.getId() != marker.entityId() || !vehicle.getUUID().equals(marker.vehicle())
                        || !vehicle.isFixedWingFlightVehicle() || vehicle.getSeatIndex(mc.player) != 0
                        || vehicle.getNthEntity(0) != mc.player || !new ResourceLocation(loaded.plan().vehicleId).equals(
                                ForgeRegistries.ENTITY_TYPES.getKey(vehicle.getType())))
                    throw new IllegalStateException("Exact mounted plan aircraft/pilot required");
                active = new Active(mc, vehicle, marker, loaded.plan());
            } else if (active.session.matches(marker) && marker.phase().equals("STOP")) {
                stop(false, "SERVER_STOP");
            } else if (valid(mc) && active.session.advance(marker, System.nanoTime())) {
                active.acknowledge = marker.phase();
                active.acknowledgementTick = Integer.MIN_VALUE;
                active.centerPendingSeen = false;
                if (active.session.stage().expectFocusLoss) mc.player.displayClientMessage(Component.literal(
                        "Private focus-release probe armed: click outside this window without pressing a key."), false);
            }
            event.setCanceled(true);
        } catch (Exception failure) {
            if (active != null) stop(true, "ARM_OR_PHASE_ERROR_" + failure.getClass().getSimpleName());
            else {
                mc.getConnection().sendCommand("bvp_fixed_wing_client_ack " + marker.run() + " ABORT");
                mc.player.displayClientMessage(Component.literal("Flight client fixture refused: " + failure.getMessage()), false);
            }
        }
    }

    /** Runs before the normal END handlers; it never calls their collection or send methods itself. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void beforeInputs(TickEvent.ClientTickEvent event) {
        Active state = active;
        if (state == null || event.phase != TickEvent.Phase.END) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (!valid(mc)) { stop(true, lostReason(mc, state)); return; }
            if (state.drivenTick == state.player.tickCount) return;
            state.drivenTick = state.player.tickCount;
            if (state.acknowledgementTick == Integer.MIN_VALUE) state.acknowledgementTick = state.player.tickCount;
            BvpFlightClientPlan.Stage stage = state.session.stage();
            int tick = state.session.stageTick();
            boolean gui = stage != null && stage.gui(tick);
            if (gui && state.pauseScreen == null) {
                state.pauseScreen = new InputPauseScreen();
                // Preserve the previous held input until the real ScreenEvent/END cleanup releases it.
                mc.setScreen(state.pauseScreen);
            } else if (!gui && state.pauseScreen != null) {
                if (mc.screen == state.pauseScreen) mc.setScreen(null);
                state.pauseScreen = null;
                releaseKeys(state);
                state.skipMouseOnce = true;
            }
            // Opposing registered keys keep the axes neutral during setup while the
            // renderer catches up. They are released before the first measured input.
            boolean setupHold = stage == null && state.session.plan.preparePoseOnClientArm && state.epoch != 0;
            state.expectedBits = setupHold ? 3 : stage == null ? 0 : stage.expectedBits(tick);
            state.expectedManualMask = setupHold ? 15 : stage == null || gui ? 0 : stage.manualMask;
            state.expectedFreeCamera = stage != null && !gui && stage.freeCamera;
            if (!gui) {
                boolean[] desired = stage == null ? new boolean[state.keys.length] : stage.keyStates(tick);
                if (setupHold) for (int index = 2; index <= 5; index++) desired[index] = true;
                for (int index = 0; index < desired.length; index++) key(state, index, desired[index]);
            }
            state.requestedX = stage == null || gui || state.skipMouseOnce ? 0 : stage.mouseX(tick);
            state.requestedY = stage == null || gui || state.skipMouseOnce ? 0 : stage.mouseY(tick);
            state.waypointErrorDegrees = null;
            if (stage != null && stage.clientAimYaw != null && tick < stage.clientMouseTicks
                    && !gui && !state.skipMouseOnce && ClientEventHandler.modelViewMatrix != null) {
                var view = FixedWingPilotIntentClient.activeView(state.player);
                if (view != null && !view.getCenteringPending()) {
                    var current = com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingPilotIntent.Companion.normalized(
                            view.getDirectionX(), view.getDirectionY(), view.getDirectionZ(), view.getManualMask());
                    var camera = FixedWingMouseAimMath.INSTANCE.cameraBasis(ClientEventHandler.modelViewMatrix);
                    if (current != null && FixedWingMouseAimMath.INSTANCE.validBasis(camera)) {
                        var sample = BvpFlightAimWaypoint.sample(current, camera, stage.clientAimYaw,
                                stage.clientAimPitch, stage.clientAimToleranceDegrees,
                                state.vehicle.getMouseSensitivity() * FixedWingJoystickSensitivity.get(),
                                FixedWingPitchControl.isInverted());
                        state.requestedX = sample.x(); state.requestedY = sample.y();
                        state.waypointErrorDegrees = sample.errorDegrees();
                    }
                }
            }
            state.skipMouseOnce = false;
            Vec2 observed = MouseMovementHandler.INSTANCE.getMousePos();
            // Dispatch through the game's ordinary cursor callback. Frame-owned production input
            // reads its cumulative position; the fixture never calls an aim solver or packet sender.
            if (stage != null && !stage.clientMouseRenderRate && (state.requestedX != 0 || state.requestedY != 0)) {
                CursorCallback.move(mc, observed.x + state.requestedX, observed.y + state.requestedY);
            }
            if (stage != null) mc.options.setCameraType(CameraType.valueOf(stage.clientView.name()));
            state.drivenStage = state.session.stageIndex();
        } catch (RuntimeException | LinkageError failure) { stop(true, "INPUT_DRIVER_ERROR"); }
    }

    /** Runs before camera setup/production render sampling, not after the completed frame. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void renderInput(TickEvent.RenderTickEvent event) {
        Active state = active;
        if (state == null || event.phase != TickEvent.Phase.START) return;
        long now = System.nanoTime();
        double scale = renderMouseScale(now - state.lastRenderInputNanos);
        state.lastRenderInputNanos = now;
        state.renderInputX = 0; state.renderInputY = 0;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (!valid(mc)) { stop(true, lostReason(mc, state)); return; }
            var stage = state.session.stage();
            if (stage == null || !stage.clientMouseRenderRate || ownedGui(mc, state)
                    || state.drivenStage != state.session.stageIndex()) return;
            int tick = state.session.stageTick();
            state.renderInputX = stage.mouseX(tick) * scale;
            state.renderInputY = stage.mouseY(tick) * scale;
            if (state.renderInputX != 0 || state.renderInputY != 0) {
                var cursor = MouseMovementHandler.INSTANCE.getMousePos();
                CursorCallback.move(mc, cursor.x + state.renderInputX, cursor.y + state.renderInputY);
                state.renderInputSamples++;
            }
        } catch (RuntimeException | LinkageError failure) { stop(true, "RENDER_INPUT_DRIVER_ERROR"); }
    }

    static double renderMouseScale(long elapsedNanos) {
        if (elapsedNanos <= 0 || elapsedNanos > 250_000_000L) return 0;
        return Math.min(1.0, elapsedNanos / 50_000_000.0);
    }

    private static void key(Active state, int index, boolean down) {
        state.keys[index].setDown(down);
        if (state.injected[index] == down) return;
        state.injected[index] = down;
        injectedEdge = true;
        try {
            MinecraftForge.EVENT_BUS.post(new InputEvent.Key(state.bindings[index].getValue(), 0,
                    down ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE, 0));
        } finally { injectedEdge = false; }
    }

    /** Version-mapped access to the same callback GLFW invokes; no native cursor movement. */
    private static final class CursorCallback {
        private static final Method MOVE = ObfuscationReflectionHelper.findMethod(
                MouseHandler.class, "m_91561_", long.class, double.class, double.class);
        private static final Field ACCUMULATED_X = ObfuscationReflectionHelper.findField(MouseHandler.class, "f_91516_");
        private static final Field ACCUMULATED_Y = ObfuscationReflectionHelper.findField(MouseHandler.class, "f_91517_");

        static void move(Minecraft mc, double x, double y) {
            try { MOVE.invoke(mc.mouseHandler, mc.getWindow().getWindow(), x, y); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cursor callback failed", failure); }
        }

        static void restore(Minecraft mc) {
            double[] x = {0}, y = {0};
            GLFW.glfwGetCursorPos(mc.getWindow().getWindow(), x, y);
            move(mc, x[0], y[0]);
            try {
                ACCUMULATED_X.setDouble(mc.mouseHandler, 0);
                ACCUMULATED_Y.setDouble(mc.mouseHandler, 0);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cursor cleanup failed", failure);
            }
            FixedWingMouseAimInput.INSTANCE.clear(mc.player);
        }
    }

    private static void releaseKeys(Active state) {
        for (int index = 0; index < state.keys.length; index++) key(state, index, false);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void afterInputs(TickEvent.ClientTickEvent event) {
        Active state = active;
        if (state == null || event.phase != TickEvent.Phase.END) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (!valid(mc)) { stop(true, lostReason(mc, state)); return; }
            if (state.observedTick == state.player.tickCount) return;
            state.observedTick = state.player.tickCount;
            if (state.ticks.size() >= MAX_TICK_ROWS) { stop(true, "TICK_CAP"); return; }
            var intent = FixedWingPilotIntentClient.activeView(state.player);
            // Setup can replace the lease before its snapshot reaches the client. Do not
            // adopt a pre-setup snapshot; once adopted, all lease guards remain strict.
            boolean waitingForFreshLease = state.session.stageIndex() < 0 && state.epoch == 0
                    && (intent == null || intent.getServerTick() < state.session.identity.minimumServerTick());
            if (waitingForFreshLease) intent = null;
            if (intent != null) {
                if (state.epoch != 0 && state.epoch != intent.getControlEpoch()) { stop(true, "LEASE_CHANGED"); return; }
                if (intent.getAcceptedSequence() < state.acceptedSequence) { stop(true, "ACK_REGRESSED"); return; }
                state.epoch = intent.getControlEpoch(); state.acceptedSequence = intent.getAcceptedSequence();
                state.centerPendingSeen |= intent.getCenteringPending();
            } else if (state.epoch != 0) { stop(true, "LEASE_LOST"); return; }
            double change = directionChange(state.previousIntent, intent);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("clientTick", state.player.tickCount); row.put("stage", state.session.stageIndex());
            row.put("stageTick", state.session.stageTick()); row.put("requestedBits", state.expectedBits);
            row.put("handlerBits", ClientEventHandler.keysCache); row.put("requestedManualMask", state.expectedManualMask);
            row.put("requestedMousePixels", List.of(state.requestedX, state.requestedY));
            row.put("waypointErrorDegrees", state.waypointErrorDegrees);
            row.put("sampledMousePixels", List.of(ClientMouseHandler.posN.x - ClientMouseHandler.posO.x,
                    ClientMouseHandler.posN.y - ClientMouseHandler.posO.y));
            row.put("worldAim", intent(intent)); row.put("directionChangeDegrees", change);
            row.put("waitingForFreshLease", waitingForFreshLease);
            row.put("minimumServerTick", state.session.identity.minimumServerTick());
            row.put("setupPoseReady", state.setupPoseReady);
            row.put("zeroInputDirectionStable", state.requestedX != 0 || state.requestedY != 0
                    || intent == null || intent.getCenteringPending() || state.previousIntent == null
                    || state.previousIntent.getCenteringPending() || change < 0.001);
            row.put("guiReleaseProbe", ownedGui(mc, state));
            row.put("freeCameraHeld", VehicleFreeCameraController.isActive(state.player));
            row.put("requestedFreeCamera", state.expectedFreeCamera);
            row.put("freeCameraOffsets", List.of(ClientMouseHandler.freeCameraYaw, ClientMouseHandler.freeCameraPitch));
            row.put("authority", flight(state.vehicle.getVehicleFlightInstrumentSnapshot(1F)));
            state.ticks.add(row);
            boolean mismatch = ClientEventHandler.keysCache != state.expectedBits
                    || (intent != null && intent.getManualMask() != state.expectedManualMask)
                    || VehicleFreeCameraController.isActive(state.player) != state.expectedFreeCamera;
            state.mismatchTicks = mismatch ? state.mismatchTicks + 1 : 0;
            if (state.mismatchTicks >= 3) { stop(true, "PRODUCTION_KEY_PATH_MISMATCH"); return; }
            boolean mouseExpected = !VehicleFreeCameraController.hasPresentation(state.player, state.vehicle)
                    && intent != null && !intent.getCenteringPending()
                    && (state.requestedX != 0 || state.requestedY != 0) && state.previousIntent != null;
            state.mouseStallTicks = mouseExpected && change < 1e-7 ? state.mouseStallTicks + 1 : 0;
            if (state.mouseStallTicks >= 5) { stop(true, "WORLD_AIM_MOUSE_PATH_STALLED"); return; }
            if (state.acknowledge != null) {
                boolean ready;
                if (state.acknowledge.equals("ARM")) {
                    boolean poseReady = !state.session.plan.preparePoseOnClientArm
                            || (state.setupPoseReady && state.setupPoseRenderTick >= state.player.tickCount - 1
                            && intent != null && intent.getManualMask() == 15);
                    state.armReadyTicks = intent != null && intent.getAcceptedSequence() >= 0
                            && state.renderedBasis && poseReady && !mismatch ? state.armReadyTicks + 1 : 0;
                    ready = state.armReadyTicks >= 2;
                } else {
                    ready = intent != null && !mismatch && state.player.tickCount - state.acknowledgementTick
                            >= BvpFlightClientControl.ACK_DELAY_TICKS;
                    if (state.session.stage().recenter) ready = ready && state.centerPendingSeen && !intent.getCenteringPending();
                }
                if (ready) {
                    state.connection.sendCommand("bvp_fixed_wing_client_ack " + state.session.identity.run() + " " + state.acknowledge);
                    state.acknowledge = null;
                }
            }
            state.previousIntent = intent;
            state.session.tick();
        } catch (RuntimeException | LinkageError failure) { stop(true, "INPUT_OBSERVATION_ERROR"); }
    }

    private static double directionChange(FixedWingPilotIntentClientStream.View before,
                                          FixedWingPilotIntentClientStream.View after) {
        if (before == null || after == null) return 0;
        double dot = before.getDirectionX() * after.getDirectionX() + before.getDirectionY() * after.getDirectionY()
                + before.getDirectionZ() * after.getDirectionZ();
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
    }

    private static Object intent(FixedWingPilotIntentClientStream.View view) {
        if (view == null) return "WAITING_FOR_SERVER_LEASE";
        return Map.of("epoch", view.getControlEpoch(), "acceptedSequence", view.getAcceptedSequence(),
                "serverTick", view.getServerTick(), "worldDirection", List.of(view.getDirectionX(),
                        view.getDirectionY(), view.getDirectionZ()), "manualMask", view.getManualMask(),
                "centeringPending", view.getCenteringPending(), "firstPersonResponse", view.getFirstPerson());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void render(TickEvent.RenderTickEvent event) {
        Active state = active;
        if (state == null || event.phase != TickEvent.Phase.END) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (!valid(mc)) { stop(true, lostReason(mc, state)); return; }
            long now = System.nanoTime();
            captureFramebuffer(mc, state, now);
            if (state.aimFrames.size() >= MAX_AIM_FRAMES) { stop(true, "AIM_FRAME_CAP"); return; }
            var aim = FixedWingPilotIntentClient.activeView(state.player);
            var camera = mc.gameRenderer.getMainCamera();
            if (aim != null && ClientEventHandler.modelViewMatrix != null && ClientEventHandler.projectionMatrix != null) {
                var marker = FixedWingMouseAimMath.INSTANCE.project(aim.getDirectionX(), aim.getDirectionY(),
                        aim.getDirectionZ(), ClientEventHandler.modelViewMatrix, ClientEventHandler.projectionMatrix,
                        mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
                // Camera release evidence must survive a target behind the current view.
                {
                    Map<String, Object> frame = new LinkedHashMap<>();
                    frame.put("elapsedNanos", now - state.startedNanos);
                    frame.put("clientTick", state.player.tickCount);
                    frame.put("stage", state.session.stageIndex());
                    frame.put("stageTick", state.session.stageTick());
                    frame.put("view", mc.options.getCameraType().name());
                    frame.put("viewport", List.of(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()));
                    frame.put("marker", marker == null ? "BEHIND_CAMERA" : List.of(marker.getX(), marker.getY()));
                    frame.put("onScreen", marker != null && marker.getOnScreen());
                    frame.put("worldDirection", List.of(aim.getDirectionX(), aim.getDirectionY(), aim.getDirectionZ()));
                    frame.put("manualMask", aim.getManualMask());
                    frame.put("centering", aim.getCenteringPending());
                    frame.put("cameraAngles", List.of(camera.getYRot(), camera.getXRot()));
                    frame.put("freeCameraHeld", VehicleFreeCameraController.isActive(state.player));
                    frame.put("releaseSequence", VehicleFreeCameraController.getReleaseSequence());
                    frame.put("returnPhase", FixedWingDynamicCamera.getReturnPhase());
                    frame.put("returnBlockedGestures", FixedWingDynamicCamera.getSuppressedReturnGestures());
                    frame.put("returnRearms", FixedWingDynamicCamera.getReturnRearms());
                    frame.put("renderInputPixels", List.of(state.renderInputX, state.renderInputY));
                    frame.put("renderInputSamples", state.renderInputSamples);
                    frame.put("freeCameraOffsets", List.of(ClientMouseHandler.freeCameraYaw, ClientMouseHandler.freeCameraPitch));
                    var chaseOffset = FixedWingDynamicCamera.INSTANCE.offset(state.vehicle, event.renderTickTime);
                    frame.put("cameraChaseOffset", List.of(chaseOffset.x, chaseOffset.y));
                    frame.put("cameraStrength", FixedWingDynamicCamera.getStrength());
                    frame.put("renderBodyAngles", List.of(state.vehicle.getResolvedChassisYaw(event.renderTickTime),
                            state.vehicle.getPitch(event.renderTickTime), state.vehicle.getRoll(event.renderTickTime)));
                    if (state.session.stageIndex() < 0 && state.session.plan.preparePoseOnClientArm) {
                        var authority = state.vehicle.getVehicleFlightInstrumentSnapshot(1F);
                        state.setupPoseReady = state.session.plan.matchesSetupPose(
                                state.vehicle.getResolvedChassisYaw(event.renderTickTime),
                                state.vehicle.getPitch(event.renderTickTime), state.vehicle.getRoll(event.renderTickTime))
                                && state.session.plan.matchesSetupPose(authority.getBodyYaw(),
                                authority.getBodyPitch(), authority.getBodyRoll());
                        state.setupPoseRenderTick = state.player.tickCount;
                    }
                    frame.put("setupPoseReady", state.setupPoseReady);
                    frame.put("finalModelView", ClientEventHandler.modelViewMatrix.get(new float[16]));
                    var body = state.vehicle.getVehicleFlightPresentationSnapshot(event.renderTickTime);
                    frame.put("bodyAngles", List.of(body.getBodyYaw(), body.getBodyPitch(), body.getBodyRoll()));
                    var nose = Vec3.directionFromRotation(state.vehicle.getPitch(event.renderTickTime),
                            state.vehicle.getResolvedChassisYaw(event.renderTickTime));
                    var noseMarker = FixedWingMouseAimMath.INSTANCE.project(nose.x, nose.y, nose.z,
                            ClientEventHandler.modelViewMatrix, ClientEventHandler.projectionMatrix,
                            mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
                    frame.put("nose", noseMarker == null ? "BEHIND_CAMERA" : List.of(noseMarker.getX(), noseMarker.getY()));
                    frame.put("noseErrorDegrees", Math.toDegrees(Math.acos(Math.max(-1, Math.min(1,
                            nose.x * aim.getDirectionX() + nose.y * aim.getDirectionY() + nose.z * aim.getDirectionZ())))));
                    state.aimFrames.add(frame);
                }
            }
            if (now - state.lastRenderNanos < 100_000_000L) return;
            if (state.renders.size() >= MAX_RENDER_ROWS) { stop(true, "RENDER_CAP"); return; }
            state.lastRenderNanos = now;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("clientTick", state.player.tickCount); row.put("partial", event.renderTickTime);
            row.put("stage", state.session.stageIndex()); row.put("view", mc.options.getCameraType().name());
            row.put("body", flight(state.vehicle.getVehicleFlightPresentationSnapshot(event.renderTickTime)));
            row.put("cameraAngles", List.of(camera.getYRot(), camera.getXRot()));
            row.put("freeCameraHeld", VehicleFreeCameraController.isActive(state.player));
            row.put("freeCameraOffsets", List.of(ClientMouseHandler.freeCameraYaw, ClientMouseHandler.freeCameraPitch));
            row.put("cameraPosition", List.of(camera.getPosition().x, camera.getPosition().y, camera.getPosition().z));
            var vehiclePosition = state.vehicle.getPosition(event.renderTickTime);
            row.put("vehiclePosition", List.of(vehiclePosition.x, vehiclePosition.y, vehiclePosition.z));
            row.put("worldAim", intent(FixedWingPilotIntentClient.activeView(state.player)));
            row.put("mouseAimSensitivity", FixedWingJoystickSensitivity.get());
            row.put("pointingPitchInverted", FixedWingPitchControl.isInverted());
            row.put("gear", Map.of("available", state.vehicle.hasFixedWingLandingGear(),
                    "up", state.vehicle.getGearUp(), "fraction", state.vehicle.getSynchedGearRot()));
            if (ClientEventHandler.modelViewMatrix != null && camera.getEntity() == state.player) {
                float[] basis = ClientEventHandler.modelViewMatrix.get(new float[16]);
                for (float value : basis) if (!Float.isFinite(value)) throw new IllegalStateException("Nonfinite camera basis");
                row.put("finalModelView", basis); state.renderedBasis = true;
            }
            state.renders.add(row);
        } catch (RuntimeException | LinkageError failure) { stop(true, "PRESENTATION_OBSERVATION_ERROR"); }
    }

    /** Passive framebuffer evidence leaves physical-key interruption guards unchanged. */
    private static void captureFramebuffer(Minecraft mc, Active state, long now) {
        var stage = state.session.stage();
        int index = state.session.stageIndex();
        if (stage == null || mc.screen != null || state.framebufferCaptures.size() >= 12
                || state.framebufferCaptures.containsKey(index)
                || state.session.stageTick() < Math.min(10, stage.ticks / 2)
                || !(stage.name.endsWith("_point") || stage.name.endsWith("_capture")
                    || stage.name.endsWith("_manual_lock") || stage.name.equals("front_view"))) return;
        String filename = "bvp-flight-" + state.session.identity.run() + "-s" + index + "-"
                + mc.options.getCameraType().name() + ".png";
        state.framebufferCaptures.put(index, Map.of("filename", filename, "stage", stage.name,
                "stageTick", state.session.stageTick(), "elapsedNanos", now - state.startedNanos,
                "status", "REQUESTED_FILE_VERIFICATION_REQUIRED"));
        net.minecraft.client.Screenshot.grab(mc.gameDirectory, filename, mc.getMainRenderTarget(),
                message -> EliteDiagnostics.record(state.vehicle, "aircraft_presentation", "FRAMEBUFFER_RESULT",
                        "filename", filename, "result", message.getString()));
    }

    private static Map<String, Object> flight(VehicleFlightInstrumentSnapshot snapshot) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sequence", snapshot.getSequence()); row.put("serverTick", snapshot.getServerTick());
        row.put("bodyAngles", List.of(snapshot.getBodyYaw(), snapshot.getBodyPitch(), snapshot.getBodyRoll()));
        var controls = snapshot.getControlSurfaces();
        row.put("controls", controls == null ? "ABSENT_NEUTRAL" : Map.of("serverTick", controls.getServerTick(),
                "elevator", controls.getElevator(), "aileron", controls.getAileron(), "rudder", controls.getRudder(),
                "airbrake", controls.getAirbrake(), "throttle", controls.getThrottle(), "afterburner", controls.getAfterburnerActive()));
        return row;
    }

    private static boolean privateClient(Minecraft mc) {
        return BvpFlightClientControl.enabled() && mc.player != null && mc.level != null && mc.getConnection() != null
                && BvpFireTrafficControl.identity(mc.player.getGameProfile().getName(), mc.player.getUUID())
                && "0".equals(mc.getUser().getAccessToken())
                && BvpFireTrafficControl.loopback(mc.getConnection().getConnection().getRemoteAddress(), true)
                && mc.level.dimension() == Level.OVERWORLD;
    }

    private static boolean ownedGui(Minecraft mc, Active state) {
        return state.pauseScreen != null && mc.screen == state.pauseScreen && state.session.stage() != null
                && state.session.stage().clientGuiTicks > 0
                && state.session.stageTick() <= state.session.stage().clientGuiTicks;
    }

    private static String lostReason(Minecraft mc, Active state) {
        if (!mc.isWindowActive()) return state.session.stage() != null && state.session.stage().expectFocusLoss
                ? "EXPECTED_FOCUS_RELEASE" : "CONTEXT_WINDOW_INACTIVE";
        if (mc.screen != null && !ownedGui(mc, state)) return "CONTEXT_SCREEN_OPEN";
        if (!mc.mouseHandler.isMouseGrabbed() && !ownedGui(mc, state)) return "CONTEXT_MOUSE_RELEASED";
        if (state.vehicle.isRemoved()) return "CONTEXT_VEHICLE_REMOVED";
        if (state.player.getVehicle() != state.vehicle) return "CONTEXT_MOUNT_CHANGED";
        if (state.vehicle.getNthEntity(0) != state.player || state.vehicle.getSeatIndex(state.player) != 0)
            return "CONTEXT_SEAT_CHANGED";
        if (!EliteDiagnostics.isClientEnabled()) return "CONTEXT_DIAGNOSTICS_STOPPED";
        if (mc.getConnection() != state.connection || mc.level != state.level || mc.player != state.player)
            return "CONTEXT_WORLD_CHANGED";
        return "CONTEXT_LOST";
    }

    private static boolean valid(Minecraft mc) {
        Active state = active;
        if (state == null || !privateClient(mc) || mc.getConnection() != state.connection || mc.level != state.level
                || mc.player != state.player || (mc.screen != null && !ownedGui(mc, state)) || !mc.isWindowActive()
                || (!mc.mouseHandler.isMouseGrabbed() && !ownedGui(mc, state))
                || !state.player.isAlive() || state.player.isSpectator() || !state.player.getMainHandItem().isEmpty()
                || state.vehicle.isRemoved() || state.vehicle.isWreck() || !state.vehicle.isFixedWingFlightVehicle()
                || state.vehicle.getId() != state.session.identity.entityId()
                || !state.vehicle.getUUID().equals(state.session.identity.vehicle())
                || state.player.getVehicle() != state.vehicle || state.vehicle.getNthEntity(0) != state.player
                || state.vehicle.getSeatIndex(state.player) != 0 || ClientEventHandler.zoomVehicle
                || ClientEventHandler.holdFireVehicle
                || (VehicleFreeCameraController.hasPresentation(state.player, state.vehicle) && !state.injected[7])
                || !EliteDiagnostics.isClientEnabled() || state.session.expired(System.nanoTime())) return false;
        for (int i = 0; i < state.keys.length; i++) if (!state.keys[i].getKey().equals(state.bindings[i])
                || state.keys[i].getKeyModifier() != net.minecraftforge.client.settings.KeyModifier.NONE) return false;
        return true;
    }

    @SubscribeEvent public static void unload(LevelEvent.Unload event) {
        if (active != null && event.getLevel() == active.level) stop(false, "UNLOAD");
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void key(InputEvent.Key event) {
        if (active != null && !injectedEdge) stop(true, "PHYSICAL_KEY_INPUT");
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void button(InputEvent.MouseButton.Pre event) {
        if (active != null) stop(true, "PHYSICAL_MOUSE_BUTTON");
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void scroll(InputEvent.MouseScrollingEvent event) {
        if (active != null) stop(true, "PHYSICAL_SCROLL");
    }

    private static void stop(boolean notify, String reason) {
        Active state = active;
        if (state == null) return;
        active = null;
        COMPLETED.add(state.session.identity.run());
        while (COMPLETED.size() > 32) COMPLETED.remove(COMPLETED.iterator().next());
        Minecraft mc = Minecraft.getInstance();
        String cleanupError = null;
        // Keys were released at admission; restore them and dispatch real release edges to collectors.
        for (int index = 0; index < state.keys.length; index++) {
            try { key(state, index, false); }
            catch (RuntimeException | LinkageError failure) { cleanupError = "RELEASE_EDGE_FAILED"; }
        }
        if (mc.screen == state.pauseScreen && state.pauseScreen != null) mc.setScreen(null);
        mc.options.setCameraType(state.savedView);
        try {
            CursorCallback.restore(mc);
            Vec2 real = MouseMovementHandler.INSTANCE.getMousePos();
            ClientMouseHandler.posO = real; ClientMouseHandler.posN = real;
            if (notify && mc.getConnection() == state.connection && privateClient(mc))
                state.connection.sendCommand("bvp_fixed_wing_client_ack " + state.session.identity.run() + " ABORT");
        } catch (RuntimeException | LinkageError failure) { cleanupError = "BASELINE_OR_ABORT_FAILED"; }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", BvpFlightClientControl.REPORT_SCHEMA); report.put("reason", reason);
        report.put("run", state.session.identity.run()); report.put("label", state.session.identity.label());
        report.put("planSha256", state.session.identity.digest()); report.put("startedUtc", state.startedUtc);
        report.put("finishedUtc", Instant.now().toString()); report.put("ticks", state.ticks); report.put("renders", state.renders);
        report.put("aimFrames", state.aimFrames);
        report.put("framebufferCaptures", state.framebufferCaptures);
        report.put("cleanupError", cleanupError); report.put("inputBindings", state.bindingEvidence);
        report.put("inputBoundary", "Registered key states/Forge edges and ordinary cursor callback; production frame input and END-tick transport own aim and sends");
        report.put("limitations", "Cursor callback replay is not OS/GLFW input; no aircraft pose/surface writes or visual pixel assertion; release uses normal END transport or lease expiry");
        try {
            byte[] bytes = new Gson().toJson(report).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_REPORT_BYTES) throw new IOException("Client flight report cap exceeded");
            Path path = mc.gameDirectory.toPath().resolve("logs/fixed-wing-tests")
                    .resolve(state.session.identity.label() + "-" + state.session.identity.run() + "-client.json");
            Files.createDirectories(path.getParent()); Files.write(path, bytes);
            if (mc.player == state.player) state.player.displayClientMessage(Component.literal("Client flight fixture " + reason + "; " + path.getFileName()), false);
        } catch (IOException | RuntimeException failure) {
            if (mc.player == state.player) state.player.displayClientMessage(Component.literal("Client flight report failed: " + failure.getClass().getSimpleName()), false);
        }
    }
}
