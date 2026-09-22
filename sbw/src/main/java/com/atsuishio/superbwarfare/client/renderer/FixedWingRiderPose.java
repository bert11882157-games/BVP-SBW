package com.atsuishio.superbwarfare.client.renderer;

import org.joml.Matrix4dc;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Fixed seats inherit their full attachment attitude; world-heading projection is undefined at the poles. */
public final class FixedWingRiderPose {
    private FixedWingRiderPose() {}

    /** Replace the independently interpolated passenger origin with the current rendered seat. */
    public static Vector3d translation(Matrix4dc bodyTransform, Vector3dc seat, Vector3dc riderOrigin) {
        if (!bodyTransform.isFinite() || !seat.isFinite() || !riderOrigin.isFinite()) return new Vector3d();
        return bodyTransform.transformPosition(seat, new Vector3d()).sub(riderOrigin);
    }

    public static Quaternionf rotation(Matrix4dc bodyTransform, float seatOrientation) {
        if (!bodyTransform.isFinite() || !Float.isFinite(seatOrientation)) return new Quaternionf();
        Quaternionf result = bodyTransform.getNormalizedRotation(new Quaternionf())
                .rotateY((float) Math.toRadians(180.0 - seatOrientation)).normalize();
        return Float.isFinite(result.x) && Float.isFinite(result.y) && Float.isFinite(result.z)
                && Float.isFinite(result.w) ? result : new Quaternionf();
    }
}
