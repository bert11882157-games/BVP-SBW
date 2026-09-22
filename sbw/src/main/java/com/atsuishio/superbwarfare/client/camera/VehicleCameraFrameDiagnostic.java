package com.atsuishio.superbwarfare.client.camera;

import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.event.ClientMouseHandler;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4fc;

import java.util.Arrays;
import java.util.UUID;

/** Opt-in release-frame observer. Copies matrices at their consumers; never resolves or writes a camera. */
public final class VehicleCameraFrameDiagnostic {
    private static final int PRE_FRAMES = 4;
    private static final Window WINDOW = new Window();
    private static final Frame[] HISTORY = new Frame[PRE_FRAMES + 1];
    private static UUID session;
    private static VehicleEntity vehicle;
    private static Object level;
    private static Object player;
    private static int seat = -1;
    private static int next;
    private static int historyCount;
    private static long frameId;
    private static Frame current;
    private static PoseStack projectionStack;

    private VehicleCameraFrameDiagnostic() { }

    public static void beginFrame(float partialTick) {
        current = null;
        projectionStack = null;
        if (!EliteDiagnostics.isClientEnabled()) { reset(); return; }
        Minecraft mc = Minecraft.getInstance();
        UUID activeSession = EliteDiagnostics.clientSessionId();
        if (!java.util.Objects.equals(session, activeSession)) {
            reset(); session = activeSession;
        }
        if (mc.player == null || mc.level == null || !mc.player.isAlive()
                || !(mc.player.getVehicle() instanceof VehicleEntity mounted) || mounted.isRemoved()) {
            clearContext(); return;
        }
        int mountedSeat = mounted.getSeatIndex(mc.player);
        long release = VehicleFreeCameraController.getReleaseSequence();
        if (vehicle != mounted || level != mc.level || player != mc.player || seat != mountedSeat) {
            clearContext(); vehicle = mounted; level = mc.level; player = mc.player; seat = mountedSeat;
            WINDOW.clearContext(release);
        }
        long nanos = System.nanoTime();
        if (WINDOW.begin(release, nanos)) {
            for (int i = historyCount; i > 0; i--) emit(HISTORY[(next - i + HISTORY.length) % HISTORY.length], true);
        }
        if (HISTORY[next] == null) HISTORY[next] = new Frame();
        current = HISTORY[next];
        current.clear();
        current.frame = ++frameId; current.nanos = nanos; current.tick = mc.level.getGameTime();
        current.release = release; current.partial = partialTick; current.entity = mounted.getId();
        current.seat = mountedSeat; current.view = mc.options.getCameraType().name();
        current.held = VehicleFreeCameraController.isActive(mc.player);
    }

    public static PoseStack beforeProjectionEffects(PoseStack stack) {
        if (current != null) {
            projectionStack = stack;
            current.copy(0, stack.last().pose());
        }
        return stack;
    }

    public static void afterProjectionEffects() {
        if (current != null && projectionStack != null) current.copy(1, projectionStack.last().pose());
        projectionStack = null;
    }

    public static void requestedRotation(float yaw, float pitch) {
        if (current == null) return;
        current.requestedYaw = yaw; current.requestedPitch = pitch;
        current.requested = true;
    }

    public static void setupTail() {
        if (current != null) current.setupTail = true;
    }

    /** Records actual optional-mixin dispatch within the existing bounded opt-in frame window. */
    public static void foreignCameraUpdate(boolean owned, boolean suppressed) {
        if (current == null) return;
        current.foreignUpdates++;
        current.foreignOwner |= owned;
        current.foreignUpdateSuppressed |= suppressed;
    }

    public static void foreignCameraTransform(boolean owned, boolean suppressed) {
        if (current == null) return;
        current.foreignTransforms++;
        current.foreignOwner |= owned;
        current.foreignTransformSuppressed |= suppressed;
    }

    public static void afterCameraSetup(Camera camera, PoseStack view) {
        if (current == null) return;
        current.copy(2, view.last().pose());
        quaternion(camera, current.setupQuaternion);
        current.setupYaw = camera.getYRot(); current.setupPitch = camera.getXRot();
        current.bodyYaw = vehicle.getResolvedChassisYaw(current.partial);
        current.bodyPitch = vehicle.getPitch(current.partial);
        current.bodyRoll = vehicle.getRoll(current.partial);
        current.legacyYaw = ClientMouseHandler.freeCameraYaw;
        current.legacyPitch = ClientMouseHandler.freeCameraPitch;
        current.returnPhase = FixedWingDynamicCamera.getReturnPhase();
    }

    public static void afterVehicleBank(PoseStack view) {
        if (current != null) current.copy(3, view.last().pose());
    }

    /** Called at LevelRenderer entry with the actual caller-supplied view and projection. */
    public static void submitted(Camera camera, PoseStack view, Matrix4fc projection) {
        if (current == null) return;
        current.copy(4, view.last().pose()); current.copy(5, projection);
        quaternion(camera, current.finalQuaternion);
        current.finalYaw = camera.getYRot(); current.finalPitch = camera.getXRot();
        if (WINDOW.capturing()) { emit(current, false); WINDOW.recorded(); }
        next = (next + 1) % HISTORY.length;
        historyCount = Math.min(PRE_FRAMES, historyCount + 1);
        current = null;
    }

    private static void quaternion(Camera camera, float[] target) {
        var q = camera.rotation();
        target[0] = q.x(); target[1] = q.y(); target[2] = q.z(); target[3] = q.w();
    }

    private static void emit(Frame frame, boolean beforeRelease) {
        if (frame == null) return;
        EliteDiagnostics.recordClient(frame.tick, "vehicle_camera_frame", "RELEASE_FRAME",
                "capture_release", WINDOW.release(), "pre_release", beforeRelease,
                "frame", frame.frame, "nanos", frame.nanos, "partial", frame.partial,
                "entity_id", frame.entity, "seat", frame.seat, "view", frame.view,
                "held", frame.held, "frame_release", frame.release, "stage_mask", frame.mask,
                "foreign_camera_updates", frame.foreignUpdates, "foreign_camera_transforms", frame.foreignTransforms,
                "foreign_camera_owner", frame.foreignOwner,
                "foreign_camera_update_suppressed", frame.foreignUpdateSuppressed,
                "foreign_camera_transform_suppressed", frame.foreignTransformSuppressed,
                "requested", frame.requested, "requested_yaw", frame.requestedYaw, "requested_pitch", frame.requestedPitch,
                "setup_tail_reached", frame.setupTail, "setup_yaw", frame.setupYaw, "setup_pitch", frame.setupPitch,
                "setup_quaternion_xyzw", Arrays.toString(frame.setupQuaternion),
                "final_yaw", frame.finalYaw, "final_pitch", frame.finalPitch,
                "final_quaternion_xyzw", Arrays.toString(frame.finalQuaternion),
                "body_yaw", frame.bodyYaw, "body_pitch", frame.bodyPitch, "body_roll", frame.bodyRoll,
                "legacy_yaw", frame.legacyYaw, "legacy_pitch", frame.legacyPitch, "return_phase", frame.returnPhase,
                "projection_before_bob_hurt", Arrays.toString(frame.matrices[0]),
                "projection_after_bob_hurt", Arrays.toString(frame.matrices[1]),
                "view_after_setup_before_sbw_bank", Arrays.toString(frame.matrices[2]),
                "view_after_sbw_bank", Arrays.toString(frame.matrices[3]),
                "submitted_view", Arrays.toString(frame.matrices[4]),
                "submitted_projection", Arrays.toString(frame.matrices[5]));
    }

    private static void clearContext() {
        vehicle = null; level = null; player = null; seat = -1;
        current = null; projectionStack = null; next = 0; historyCount = 0;
        WINDOW.clearContext(Long.MIN_VALUE);
    }

    private static void reset() {
        if (session == null && vehicle == null) return;
        clearContext(); session = null; WINDOW.reset();
    }

    static final class Window {
        static final int MAX_RELEASES = 4;
        static final int POST_FRAMES = 64;
        static final long POST_NANOS = 1_000_000_000L;
        private long release = Long.MIN_VALUE;
        private long started;
        private int releases;
        private int frames;
        private boolean capturing;

        boolean begin(long sequence, long nanos) {
            boolean newCapture = false;
            if (release != sequence) {
                capturing = false;
                if (release != Long.MIN_VALUE && sequence > release && releases < MAX_RELEASES) {
                    releases++; frames = 0; started = nanos; capturing = true; newCapture = true;
                }
                release = sequence;
            }
            if (capturing && (frames >= POST_FRAMES || nanos - started < 0 || nanos - started > POST_NANOS))
                capturing = false;
            return newCapture;
        }

        boolean capturing() { return capturing; }
        long release() { return release; }
        void recorded() { if (capturing) frames++; }
        void clearContext(long sequence) { release = sequence; frames = 0; capturing = false; }
        void reset() { clearContext(Long.MIN_VALUE); releases = 0; }
    }

    static final class Frame {
        final float[][] matrices = new float[6][16];
        final float[] setupQuaternion = new float[4];
        final float[] finalQuaternion = new float[4];
        long frame, tick, nanos, release;
        int entity, seat, mask, foreignUpdates, foreignTransforms;
        float partial, requestedYaw, requestedPitch, setupYaw, setupPitch, finalYaw, finalPitch;
        float bodyYaw, bodyPitch, bodyRoll;
        double legacyYaw, legacyPitch;
        boolean requested, held, setupTail, foreignOwner, foreignUpdateSuppressed, foreignTransformSuppressed;
        String view, returnPhase;

        void copy(int stage, Matrix4fc matrix) { matrix.get(matrices[stage]); mask |= 1 << stage; }
        void clear() {
            mask = 0; requested = false; setupTail = false; returnPhase = null;
            foreignUpdates = foreignTransforms = 0;
            foreignOwner = foreignUpdateSuppressed = foreignTransformSuppressed = false;
            requestedYaw = requestedPitch = setupYaw = setupPitch = finalYaw = finalPitch = Float.NaN;
            bodyYaw = bodyPitch = bodyRoll = Float.NaN; legacyYaw = legacyPitch = Double.NaN;
            for (float[] matrix : matrices) Arrays.fill(matrix, Float.NaN);
            Arrays.fill(setupQuaternion, Float.NaN); Arrays.fill(finalQuaternion, Float.NaN);
        }
    }
}
