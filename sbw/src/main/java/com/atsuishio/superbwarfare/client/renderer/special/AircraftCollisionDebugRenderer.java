package com.atsuishio.superbwarfare.client.renderer.special;

import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftCollisionRole;
import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftCollisionSnapshot;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;

/** Draws only the physical parts, never their enclosing discovery AABB or projectile hitboxes. */
public final class AircraftCollisionDebugRenderer {
    private AircraftCollisionDebugRenderer() {
    }

    public static void render(AircraftCollisionSnapshot snapshot, Vec3 renderOrigin,
                              PoseStack poses, VertexConsumer buffer) {
        var pose = poses.last();
        for (var part : snapshot.getParts()) {
            if (!part.getActive()) continue;
            var vertices = part.getWorldVertices();
            float green = part.getRole() == AircraftCollisionRole.LANDING_GEAR ? 1F : 0F;
            float blue = part.getRole() == AircraftCollisionRole.FUSELAGE ? 1F : 0F;
            // Snapshot corners use local-axis bit order; each one-bit pair is one box edge.
            for (int corner = 0; corner < 8; corner++) {
                for (int axis = 1; axis <= 4; axis <<= 1) {
                    if ((corner & axis) != 0) continue;
                    Vec3 from = vertices.get(corner);
                    Vec3 to = vertices.get(corner | axis);
                    double dx = to.x - from.x;
                    double dy = to.y - from.y;
                    double dz = to.z - from.z;
                    double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (!(length > 0.0) || !Double.isFinite(length)) continue;
                    float nx = (float) (dx / length);
                    float ny = (float) (dy / length);
                    float nz = (float) (dz / length);
                    buffer.vertex(pose.pose(), (float) (from.x - renderOrigin.x),
                                    (float) (from.y - renderOrigin.y), (float) (from.z - renderOrigin.z))
                            .color(0F, green, blue, 1F).normal(pose.normal(), nx, ny, nz).endVertex();
                    buffer.vertex(pose.pose(), (float) (to.x - renderOrigin.x),
                                    (float) (to.y - renderOrigin.y), (float) (to.z - renderOrigin.z))
                            .color(0F, green, blue, 1F).normal(pose.normal(), nx, ny, nz).endVertex();
                }
            }
        }
    }
}
