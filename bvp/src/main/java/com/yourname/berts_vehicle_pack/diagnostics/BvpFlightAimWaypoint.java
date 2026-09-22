package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingPilotIntent;
import com.atsuishio.superbwarfare.client.input.FixedWingMouseAimMath;
import org.joml.Vector3d;

/** Pure diagnostic mouse search; predicts cursor samples without changing live pilot intent. */
public final class BvpFlightAimWaypoint {
    private BvpFlightAimWaypoint() { }

    public record Sample(double x, double y, double errorDegrees) { }

    public static Sample sample(FixedWingPilotIntent current, FixedWingMouseAimMath.Basis camera,
                                double yaw, double pitch, double toleranceDegrees,
                                double sensitivity, boolean inverted) {
        double yawRadians = Math.toRadians(yaw), pitchRadians = Math.toRadians(pitch);
        Vector3d target = new Vector3d(-Math.sin(yawRadians) * Math.cos(pitchRadians),
                -Math.sin(pitchRadians), Math.cos(yawRadians) * Math.cos(pitchRadians));
        double dot = score(current, target);
        double error = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
        if (error <= toleranceDegrees || sensitivity <= 0) return new Sample(0, 0, error);

        // Search the real displacement transform, including its circular sample clamp. A coarse
        // directional pass followed by local refinement handles rolled and near-sideways cameras.
        double bestX = 0, bestY = 0, bestScore = dot;
        double limit = FixedWingMouseAimMath.MAX_PIXELS_PER_SAMPLE;
        for (double radius : new double[]{8, 32, 128, limit}) {
            for (int direction = 0; direction < 32; direction++) {
                double angle = direction * Math.PI / 16;
                double x = radius * Math.cos(angle), y = radius * Math.sin(angle);
                double candidate = predictedScore(current, camera, target, x, y, sensitivity, inverted);
                if (candidate > bestScore) { bestScore = candidate; bestX = x; bestY = y; }
            }
        }
        for (double step : new double[]{32, 8, 2, 0.5}) {
            double originX = bestX, originY = bestY;
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
                double x = originX + dx * step, y = originY + dy * step;
                double scale = Math.min(1, limit / Math.max(1, Math.hypot(x, y)));
                x *= scale; y *= scale;
                double candidate = predictedScore(current, camera, target, x, y, sensitivity, inverted);
                if (candidate > bestScore) { bestScore = candidate; bestX = x; bestY = y; }
            }
        }
        return new Sample(bestX, bestY, error);
    }

    private static double predictedScore(FixedWingPilotIntent current, FixedWingMouseAimMath.Basis camera,
                                         Vector3d target, double x, double y,
                                         double sensitivity, boolean inverted) {
        var next = FixedWingMouseAimMath.INSTANCE.rotate(current, camera, x, y, sensitivity, inverted);
        return next == null ? -2 : score(next, target);
    }

    private static double score(FixedWingPilotIntent intent, Vector3d target) {
        return target.x * intent.getDirectionX() + target.y * intent.getDirectionY()
                + target.z * intent.getDirectionZ();
    }
}
