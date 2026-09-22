package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.api.projectile.ResolvedProjectileProfile;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Packaged projectile mesh bounds in blocks; profile render scaling is applied exactly once. */
final class BvpMissileExhaustGeometry {
    private static final Shape ATAKA = read("mi24v_atgm_projectile");

    static Shape forProfile(ResolvedProjectileProfile profile) {
        if (profile == null) return null;
        // Both ATGM visual registrations use this packaged mesh.
        Shape shape = ATAKA;
        double scale = profile.getRenderScale();
        return shape == null || !Double.isFinite(scale) || scale <= 0 ? null
                : new Shape(shape.radius() * scale, shape.rear() * scale);
    }

    private static Shape read(String name) {
        String path = "/assets/berts_vehicle_pack/custom_geo/" + name + ".geo.json";
        try (var stream = BvpMissileExhaustGeometry.class.getResourceAsStream(path)) {
            if (stream == null) return null;
            var root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            double radius = 0, rear = 0;
            for (var geometry : root.getAsJsonArray("minecraft:geometry")) {
                for (var value : geometry.getAsJsonObject().getAsJsonArray("bones")) {
                    var bone = value.getAsJsonObject();
                    if (!bone.has("poly_mesh")) continue;
                    for (var point : bone.getAsJsonObject("poly_mesh").getAsJsonArray("positions")) {
                        var p = point.getAsJsonArray();
                        radius = Math.max(radius, Math.hypot(p.get(0).getAsDouble(), p.get(1).getAsDouble()) / 16.0);
                        rear = Math.max(rear, p.get(2).getAsDouble() / 16.0);
                    }
                }
            }
            return radius > 0 && Double.isFinite(radius) && Double.isFinite(rear) ? new Shape(radius, rear) : null;
        } catch (java.io.IOException | RuntimeException error) {
            com.mojang.logging.LogUtils.getLogger().warn("Could not read missile exhaust mesh bounds: {}", name, error);
            return null;
        }
    }

    record Shape(double radius, double rear) {}
}
