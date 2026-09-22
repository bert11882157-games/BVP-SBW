package com.atsuishio.superbwarfare.client.camera;

import org.joml.Matrix4d;

/** Continuous nose-following camera coordinates; aircraft roll is deliberately not inherited. */
public final class FixedWingThirdPersonAngles {
    private boolean initialized;
    private float yaw;
    private float pitch;

    public boolean update(float canonicalYaw, float canonicalPitch) {
        if (!Float.isFinite(canonicalYaw) || !Float.isFinite(canonicalPitch)) {
            initialized = false;
            return false;
        }
        if (!initialized) {
            yaw = canonicalYaw;
            pitch = canonicalPitch;
            initialized = true;
            return true;
        }
        // Both pairs point along exactly the same ray. Across a pitch pole, canonical
        // yaw flips 180 degrees; continuing its other branch avoids a camera-up flip.
        double yawA = nearest(canonicalYaw, yaw), pitchA = nearest(canonicalPitch, pitch);
        double yawB = nearest(canonicalYaw + 180.0, yaw), pitchB = nearest(180.0 - canonicalPitch, pitch);
        double distanceA = squared(yawA - yaw) + squared(pitchA - pitch);
        double distanceB = squared(yawB - yaw) + squared(pitchB - pitch);
        yaw = (float) Math.IEEEremainder(distanceB < distanceA ? yawB : yawA, 360.0);
        pitch = (float) Math.IEEEremainder(distanceB < distanceA ? pitchB : pitchA, 360.0);
        return true;
    }

    public float yaw() { return yaw; }
    public float pitch() { return pitch; }

    /** Free-look owns the visible camera, not the continuity of the body-relative return view. */
    public FixedWingThirdPersonAngles presentation(boolean freeCameraHeld) {
        return initialized && !freeCameraHeld ? this : null;
    }

    /** Keep the existing authored orbit pivot/translation; use the same branch as the view. */
    public void applyOrbitRotation(Matrix4d transform, double freeYaw, double freePitch) {
        transform.setRotationYXZ(Math.toRadians(-yaw + freeYaw), Math.toRadians(pitch + freePitch), 0.0);
    }

    private static double nearest(double value, double reference) {
        return reference + Math.IEEEremainder(value - reference, 360.0);
    }

    private static double squared(double value) { return value * value; }
}
