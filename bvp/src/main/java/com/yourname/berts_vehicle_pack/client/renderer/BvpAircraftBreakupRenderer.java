package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.api.vehicle.flight.AircraftWreckBreakup;
import com.atsuishio.superbwarfare.client.renderer.AircraftDetachedWings;
import com.atsuishio.superbwarfare.client.renderer.vehicle.VehicleRenderBackendContext;
import com.example.sbwmeshloader.core.PolyMesh;
import com.example.sbwmeshloader.core.PolyMeshModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.berts_vehicle_pack.mixin.BvpPolyMeshModelPrewarmAccessor;
import com.yourname.berts_vehicle_pack.mixin.BvpPolyMeshVertexAccessor;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Renders exactly the partitioned wing meshes; shared models are restored after every vehicle. */
final class BvpAircraftBreakupRenderer {
    private record Mesh(PolyMesh mesh, Matrix4f transform) {}
    record Hidden(List<BedrockBone> bones, List<Boolean> visibility) {
        void restore() { for (int i = 0; i < bones.size(); i++) bones.get(i).visible = visibility.get(i); }
    }
    static Hidden apply(VehicleRenderBackendContext context, PolyMeshModel model,
                        ResourceLocation texture, ResourceLocation blackened, BvpSuspendedStoreRenderer stores) {
        var vehicle = context.getVehicle();
        int mask = AircraftWreckBreakup.mask(vehicle);
        boolean fragmented = vehicle.getAircraftWreckImpactTime() >= 0;
        if (mask == 0 && !fragmented) return null;
        var hidden = new ArrayList<BedrockBone>();
        var visibility = new ArrayList<Boolean>();
        var accessor = (BvpPolyMeshModelPrewarmAccessor) (Object) model;
        for (int side : new int[]{1, 2, 4, 6, 7}) {
            if (side < 4 ? (mask & side) == 0 : !fragmented) continue;
            String prefix = side >= 4 ? "wreck_fuselage_" + (side - 4) + "__" :
                    side == 1 ? "wreck_wing_left__" : "wreck_wing_right__";
            var bones = model.getBoneMap().entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(prefix)).map(java.util.Map.Entry::getValue).toList();
            if (bones.isEmpty()) continue;
            if (AircraftDetachedWings.needsCapture(vehicle, side)) {
                var module = vehicle.computed().getAircraftSurfaceModules().stream()
                        .filter(entry -> entry.getId().equals(side == 1 ? "superbwarfare:wing_left" : "superbwarfare:wing_right"))
                        .findFirst().orElse(null);
                var terrain = vehicle.computed().getAircraftTerrainContact();
                var section = side >= 4 && terrain != null && terrain.getWreckSections().size() == 4 ?
                        terrain.getWreckSections().get(side - 4) : null;
                if (section != null || (side < 4 && module != null && !module.getHitboxes().isEmpty())) {
                    double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
                    double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
                    if (section != null) {
                        minX = section.getMinimum().f_82479_; maxX = section.getMaximum().f_82479_;
                        minY = section.getMinimum().f_82480_; maxY = section.getMaximum().f_82480_;
                        minZ = section.getMinimum().f_82481_; maxZ = section.getMaximum().f_82481_;
                    } else for (var box : module.getHitboxes()) {
                        minX = Math.min(minX, box.getMin().f_82479_); maxX = Math.max(maxX, box.getMax().f_82479_);
                        minY = Math.min(minY, box.getMin().f_82480_); maxY = Math.max(maxY, box.getMax().f_82480_);
                        minZ = Math.min(minZ, box.getMin().f_82481_); maxZ = Math.max(maxZ, box.getMax().f_82481_);
                    }
                    // Bedrock geometry flips X; the authored vehicle frame also flips Z.
                    Vector3f center = new Vector3f((float) -(minX + maxX) / 2,
                            (float) (minY + maxY) / 2, (float) -(minZ + maxZ) / 2);
                    PoseStack axis = new PoseStack();
                    BaseVehicleRenderer.applyVehicleRenderAxis((com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity) vehicle,
                            context.getEntityYaw(), context.getPartialTick(), axis, context.getChassisPresentation().getPose());
                    Matrix4f transform = new Matrix4f(axis.m_85850_().m_252922_());
                    var collected = new ArrayList<Mesh>();
                    for (BedrockBone bone : bones) {
                        boolean visible = true;
                        for (BedrockBone parent = bone; parent != null; parent = parent.parent)
                            visible &= parent.visible;
                        if (!visible) continue;
                        PoseStack local = new PoseStack();
                        local.m_85837_(-center.x, -center.y, -center.z);
                        var ancestors = new ArrayList<BedrockBone>();
                        for (BedrockBone parent = bone; parent != null; parent = parent.parent) ancestors.add(parent);
                        Collections.reverse(ancestors);
                        for (BedrockBone parent : ancestors) parent.translateAndRotateAndScale(local);
                        for (PolyMesh mesh : accessor.bvp$meshes().getOrDefault(bone, List.of()))
                            collected.add(new Mesh(mesh, new Matrix4f(local.m_85850_().m_252922_())));
                    }
                    if (!collected.isEmpty()) {
                        // Rest on the load-bearing core of the actual mesh; fins, props, gear legs and
                        // tip stores must not define the support box.
                        Vec3 half = new Vec3((maxX - minX) / 2, (maxY - minY) / 2, (maxZ - minZ) / 2);
                        double[] core = coreBox(collected, Math.max(half.f_82479_, Math.max(half.f_82480_, half.f_82481_)));
                        Vector3f shift = new Vector3f();
                        if (core != null) {
                            shift.set((float) ((core[0] + core[3]) / 2), (float) ((core[1] + core[4]) / 2),
                                    (float) ((core[2] + core[5]) / 2));
                            half = new Vec3(Math.max(MIN_CORE_HALF, (core[3] - core[0]) / 2),
                                    Math.max(MIN_CORE_HALF, (core[4] - core[1]) / 2),
                                    Math.max(MIN_CORE_HALF, (core[5] - core[2]) / 2));
                        }
                        Matrix4f recenter = new Matrix4f().translation(-shift.x, -shift.y, -shift.z);
                        var meshes = new ArrayList<Mesh>(collected.size());
                        for (Mesh mesh : collected) meshes.add(new Mesh(mesh.mesh, new Matrix4f(recenter).mul(mesh.transform)));
                        Vector3f pivot = new Vector3f(center).add(shift);
                        Vector3f worldCenter = transform.transformPosition(new Vector3f(pivot));
                        Vec3 anchor = context.getChassisPresentation().getAnchor();
                        Vec3 position = new Vec3(anchor.f_82479_ + worldCenter.x, anchor.f_82480_ + worldCenter.y,
                                anchor.f_82481_ + worldCenter.z);
                        int light = context.getPackedLight();
                        var attachedStores = side < 4 ? stores.captureWing((com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity) vehicle, side, light, context.getPartialTick()) : null;
                        AircraftDetachedWings.capture(vehicle, side, position,
                                transform.getNormalizedRotation(new Quaternionf()), half,
                                (pose, buffers, impacted) -> {
                                    ResourceLocation fragmentTexture = impacted
                                            ? com.atsuishio.superbwarfare.client.renderer.TextureBrightnessHandler.INSTANCE
                                                .getBrightenedTexture(texture, 0.7F) : texture;
                                    RenderType type = RenderType.m_110458_(fragmentTexture);
                                    type.m_110185_();
                                    try {
                                        for (Mesh mesh : meshes) mesh.mesh.drawVBO(
                                                new Matrix4f(pose.m_85850_().m_252922_()).mul(mesh.transform), light);
                                    } finally { type.m_110188_(); }
                                    if (attachedStores != null) {
                                        pose.m_85836_();
                                        try {
                                            pose.m_85837_(-pivot.x, -pivot.y, -pivot.z);
                                            attachedStores.render(pose, buffers, impacted);
                                        } finally { pose.m_85849_(); }
                                    }
                                });
                    }
                }
            }
            for (BedrockBone bone : bones) { hidden.add(bone); visibility.add(bone.visible); bone.visible = false; }
        }
        return new Hidden(hidden, visibility);
    }
    private static final double MIN_CORE_HALF = 0.06;
    private static final double CORE_TRIM = 0.06;
    private static final int CORE_BINS = 512;
    private static final int CORE_SAMPLE_LIMIT = 32768;

    /**
     * Area-weighted core of the captured geometry in piece-local blocks: each axis keeps the central
     * 88% of surface area, so a small high-detail protrusion cannot hold the piece up. Returns
     * {minX, minY, minZ, maxX, maxY, maxZ}, or null when vertices are unavailable or implausible.
     */
    private static double[] coreBox(List<Mesh> meshes, double sectionHalf) {
        int total = 0;
        for (Mesh mesh : meshes) {
            float[] xs = ((BvpPolyMeshVertexAccessor) (Object) mesh.mesh).bvp$positionsX();
            if (xs != null) total += xs.length;
        }
        if (total == 0) return null;
        int stride = Math.max(1, (total + CORE_SAMPLE_LIMIT - 1) / CORE_SAMPLE_LIMIT);
        var points = new float[Math.min(total, CORE_SAMPLE_LIMIT + 8) * 3];
        var weights = new float[points.length / 3];
        int count = 0;
        Vector3f[] face = {new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()};
        for (Mesh mesh : meshes) {
            var vertices = (BvpPolyMeshVertexAccessor) (Object) mesh.mesh;
            float[] xs = vertices.bvp$positionsX(), ys = vertices.bvp$positionsY(), zs = vertices.bvp$positionsZ();
            if (xs == null || ys == null || zs == null) continue;
            int length = Math.min(xs.length, Math.min(ys.length, zs.length));
            // Baked meshes are quads; fall back to triangles, then to unweighted points.
            int corners = length % 4 == 0 ? 4 : length % 3 == 0 ? 3 : 1;
            for (int first = 0; first + corners <= length; first += corners * stride) {
                if (count + corners > weights.length) break;
                for (int k = 0; k < corners; k++)
                    mesh.transform.transformPosition(xs[first + k], ys[first + k], zs[first + k], face[k]);
                float area;
                if (corners == 4) area = 0.5F * new Vector3f(face[2]).sub(face[0])
                        .cross(new Vector3f(face[3]).sub(face[1])).length();
                else if (corners == 3) area = 0.5F * new Vector3f(face[1]).sub(face[0])
                        .cross(new Vector3f(face[2]).sub(face[0])).length();
                else area = 1.0E-4F;
                for (int k = 0; k < corners; k++) {
                    points[count * 3] = face[k].x;
                    points[count * 3 + 1] = face[k].y;
                    points[count * 3 + 2] = face[k].z;
                    weights[count++] = Math.max(area / corners, 1.0E-6F);
                }
            }
        }
        if (count < 4) return null;
        double[] box = new double[6];
        for (int axis = 0; axis < 3; axis++) {
            float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
            double sum = 0;
            for (int i = 0; i < count; i++) {
                float value = points[i * 3 + axis];
                if (!Float.isFinite(value)) return null;
                low = Math.min(low, value); high = Math.max(high, value);
                sum += weights[i];
            }
            double range = high - low;
            if (range < 1.0E-6 || sum <= 0) { box[axis] = low; box[axis + 3] = high; continue; }
            double[] bins = new double[CORE_BINS];
            for (int i = 0; i < count; i++) {
                int bin = (int) ((points[i * 3 + axis] - low) / range * (CORE_BINS - 1));
                bins[Math.max(0, Math.min(CORE_BINS - 1, bin))] += weights[i];
            }
            double cumulative = 0;
            int lower = 0, upper = CORE_BINS - 1;
            for (int bin = 0; bin < CORE_BINS; bin++) {
                cumulative += bins[bin];
                if (cumulative >= sum * CORE_TRIM) { lower = bin; break; }
            }
            cumulative = 0;
            for (int bin = CORE_BINS - 1; bin >= 0; bin--) {
                cumulative += bins[bin];
                if (cumulative >= sum * CORE_TRIM) { upper = bin; break; }
            }
            if (upper < lower) { int swap = upper; upper = lower; lower = swap; }
            box[axis] = low + range * lower / (CORE_BINS - 1);
            box[axis + 3] = low + range * (upper + 1) / (CORE_BINS - 1);
            box[axis + 3] = Math.min(box[axis + 3], high);
        }
        double largest = Math.max(box[3] - box[0], Math.max(box[4] - box[1], box[5] - box[2])) / 2;
        // A unit mismatch or a corrupt mesh must not replace the authored section box.
        if (!(largest > 0.05) || largest > 64 || sectionHalf > 0 && (largest > sectionHalf * 4 || largest < sectionHalf * 0.1))
            return null;
        return box;
    }

    private BvpAircraftBreakupRenderer() {}
}
