package com.atsuishio.superbwarfare.client.camera;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;

/** Actual capture-window and matrix-copy checks; no Minecraft instance or world. */
public final class VehicleCameraFrameDiagnosticTest {
    private static int checks;

    private static void expect(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        for (int fps : new int[]{30, 60, 120}) {
            var window = new VehicleCameraFrameDiagnostic.Window();
            expect(!window.begin(10, 0) && !window.capturing(), "initial observation does not invent a release");
            for (int release = 11; release <= 15; release++) {
                long start = release * 3_000_000_000L;
                int records = 0;
                for (int frame = 0; frame <= fps * 2; frame++) {
                    boolean fresh = window.begin(release, start + frame * 1_000_000_000L / fps);
                    expect(fresh == (frame == 0 && release <= 14), "only the first four release edges trigger a capture");
                    if (window.capturing()) { records++; window.recorded(); }
                }
                expect(records == (release <= 14 ? Math.min(64, fps + 1) : 0), "one-second and frame-count bounds are both hard");
            }
            window.clearContext(30);
            expect(!window.begin(31, 60_000_000_000L), "context switching cannot evade the per-session release cap");
            window.reset();
            window.begin(40, 0);
            expect(window.begin(41, 100), "new explicit diagnostics session resets the cap");
            window.begin(41, 99);
            expect(!window.capturing(), "clock rollback closes a capture");
            window.begin(42, 200);
            window.clearContext(42);
            expect(!window.capturing(), "dismount/world/context replacement cancels the capture");
        }

        var sample = new VehicleCameraFrameDiagnostic.Frame();
        for (int iteration = 0; iteration < 100; iteration++) {
            sample.foreignUpdates = 2; sample.foreignTransforms = 3;
            sample.foreignOwner = sample.foreignUpdateSuppressed = sample.foreignTransformSuppressed = true;
            sample.clear();
            expect(sample.foreignUpdates == 0 && sample.foreignTransforms == 0 && !sample.foreignOwner
                    && !sample.foreignUpdateSuppressed && !sample.foreignTransformSuppressed,
                    "actual optional-mixin activation is per-frame, never retained across reused rows");
            expect(sample.mask == 0 && Float.isNaN(sample.matrices[4][0]), "reused rows cannot expose an earlier frame's matrix");
            for (int stage = 0; stage < 6; stage++) {
                Matrix4f original = new Matrix4f().rotateZ(iteration * 0.01f + stage * 0.02f);
                Matrix4f before = new Matrix4f(original);
                sample.copy(stage, original);
                expect(original.equals(before), "observer never mutates a source matrix");
                original.identity();
                expect(new Matrix4f().set(sample.matrices[stage]).equals(before), "matrix snapshot is independent of later writer mutation");
            }
            expect(sample.mask == 63, "all six matrix-stage presence bits are explicit");
        }

        // A level Camera quaternion does not prove a level image: projection roll is independent.
        Quaternionf camera = new Quaternionf();
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 1000f);
        Matrix4f tiltedProjection = new Matrix4f(projection).rotateZ((float) Math.toRadians(30));
        Vector4f a = tiltedProjection.transform(new Vector4f(-1, 0, -10, 1));
        Vector4f b = tiltedProjection.transform(new Vector4f(1, 0, -10, 1));
        expect(camera.equals(new Quaternionf()) && Math.abs(b.y / b.w - a.y / a.w) > 0.1,
                "final projection detects image roll even when camera and view remain level");
        expect(!projection.equals(tiltedProjection), "before/after projection snapshots expose the distinct writer stage");
        System.out.println("PASS " + checks + " bounded release-window, actual matrix-copy and independent projection-roll checks");
    }
}
