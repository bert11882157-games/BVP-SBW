package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.client.renderer.vehicle.RunningGearSide;
import com.atsuishio.superbwarfare.client.renderer.vehicle.TrackAutoProfile;
import com.atsuishio.superbwarfare.client.renderer.vehicle.TrackBeltLinks;
import com.atsuishio.superbwarfare.client.renderer.vehicle.TrackBeltPath;
import com.example.sbwmeshloader.core.PolyMesh;
import com.example.sbwmeshloader.core.PolyMeshModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.yourname.berts_vehicle_pack.mixin.BvpPolyMeshModelPrewarmAccessor;
import com.yourname.berts_vehicle_pack.mixin.BvpPolyMeshVertexAccessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * One side's track belt, traced at runtime around its wheels (the Superb Modern Combat track-path scheme).
 *
 * <p>Each wheel is measured from its own mesh: the circle round its geometry seen from the side (bone scale
 * applied; a {@code _rNNN} name suffix, NNN hundredths of a block, or the profile's {@code Radii} override it).
 * The belt's centre line runs one plate depth outside the wheels, so the plates' inner faces bear on them; the
 * plate depth comes from the link mesh (its full-width part, not the horns between the wheels). The belt's length
 * is measured, the number of links follows from it and the link pitch (the model's link spacing), and the model's
 * link bones are placed along it. Spare link bones are hidden. One offset applies to both sides (X outward).</p>
 */
final class AutoTrackLayout {
    private final BedrockBone[] move;
    private final BedrockBone[] rotation;
    private final float[] restY;
    private final float[] restZ;
    private final float xOffset;
    final TrackBeltLinks links;
    final int count;
    private final float[] pose = new float[4];

    private AutoTrackLayout(BedrockBone[] move, BedrockBone[] rotation, float[] restY, float[] restZ, float xOffset,
                            TrackBeltLinks links) {
        this.move = move;
        this.rotation = rotation;
        this.restY = restY;
        this.restZ = restZ;
        this.xOffset = xOffset;
        this.links = links;
        this.count = links.getCount();
    }

    /** Null when the side cannot be traced (no link bones, unmeasurable wheels): the authored path stays. */
    static AutoTrackLayout build(PolyMeshModel model, RunningGearSide side, TrackAutoProfile auto,
                                 List<String> wheelNames, BedrockBone[] moveBones, BedrockBone[] rotationBones) {
        if (auto == null || !auto.getEnabled() || rotationBones.length < 4) return null;
        Map<BedrockBone, List<PolyMesh>> meshes = ((BvpPolyMeshModelPrewarmAccessor) model).bvp$meshes();
        int n = rotationBones.length;
        float[] restY = new float[n];
        float[] restZ = new float[n];
        for (int i = 0; i < n; i++) {
            float[] pivot = restPivot(rotationBones[i]);
            restY[i] = pivot[1];
            restZ[i] = pivot[2];
        }
        float[] distances = new float[n - 1];
        for (int i = 0; i + 1 < n; i++) {
            distances[i] = (float) Math.hypot(restY[i + 1] - restY[i], restZ[i + 1] - restZ[i]);
        }
        Arrays.sort(distances);
        float pitch = distances[distances.length / 2];
        float[] plate = plate(meshes.get(rotationBones[0]));
        if (plate == null || !(pitch > 0.05F)) return null;
        float inner = plate[0];
        float outer = plate[1];

        List<String> order = auto.order(side);
        List<String> names = order.isEmpty() ? wheelNames : order;
        List<float[]> circles = new ArrayList<>();
        for (String name : names) {
            BedrockBone bone = model.getBone(name);
            float[] circle = bone == null ? null : wheelCircle(name, bone, meshes, auto.getRadii().get(name));
            if (circle != null) circles.add(circle);
        }
        if (circles.size() < 2) return null;
        float[] z = new float[circles.size()];
        float[] y = new float[circles.size()];
        float[] r = new float[circles.size()];
        for (int i = 0; i < z.length; i++) {
            float[] c = circles.get(i);
            z[i] = c[0];
            y[i] = c[1] + auto.getOffsetY();
            r[i] = c[2] - inner;   // inner is below the link centre (negative)
        }
        TrackBeltPath path = TrackBeltPath.fromWheels(z, y, r, !order.isEmpty());
        if (path == null) return null;
        int count = TrackBeltLinks.countFor(path.getLength(), pitch, n);
        TrackBeltLinks links = new TrackBeltLinks(path, count, pitch, (outer - inner) * 0.5F);
        // model x is mirrored in bone space: outward is -x on the left, +x on the right
        float xOffset = side == RunningGearSide.LEFT ? -auto.getOffsetX() : auto.getOffsetX();
        for (int i = count; i < n; i++) {
            RendererBones.hide(moveBones[i]);
            RendererBones.hide(rotationBones[i]);
        }
        return new AutoTrackLayout(Arrays.copyOf(moveBones, count), Arrays.copyOf(rotationBones, count),
                Arrays.copyOf(restY, count), Arrays.copyOf(restZ, count), xOffset, links);
    }

    /** Places the links for [travel] model pixels of belt movement. */
    void animate(float travel) {
        for (int i = 0; i < count; i++) {
            links.linkInto(i, travel, pose, 0);
            RendererBones.setPositionOffset(move[i], xOffset, pose[1] - restY[i], pose[0] - restZ[i]);
            RendererBones.setRotation(rotation[i], pose[2] * RendererBones.DEG_TO_RAD, 0.0F, 0.0F);
            RendererBones.setLongitudinalScale(rotation[i], pose[3]);
        }
    }

    float length() {
        return links.getPath().getLength();
    }

    /** Rest (bind-pose) pivot of a bone in model pixels: (bone-space x, y up, z). */
    private static float[] restPivot(BedrockBone bone) {
        float x = 0, y = 0, z = 0;
        for (BedrockBone b = bone; b != null; b = b.parent) {
            float[] base = RendererBones.basePosition(b);
            x += base[0];
            y += base[1];
            z += base[2];
        }
        return new float[]{x, y, z};
    }

    /** (inner, outer) of the link's full-width plate, in model pixels from the link centre. */
    private static float[] plate(List<PolyMesh> meshes) {
        if (meshes == null || meshes.isEmpty()) return null;
        float maxX = 0;
        for (PolyMesh mesh : meshes) {
            float[] xs = ((BvpPolyMeshVertexAccessor) mesh).bvp$positionsX();
            for (float v : xs) maxX = Math.max(maxX, Math.abs(v));
        }
        if (!(maxX > 0)) return null;
        float inner = Float.POSITIVE_INFINITY;
        float outer = Float.NEGATIVE_INFINITY;
        for (PolyMesh mesh : meshes) {
            BvpPolyMeshVertexAccessor v = (BvpPolyMeshVertexAccessor) mesh;
            float[] xs = v.bvp$positionsX();
            float[] ys = v.bvp$positionsY();
            for (int i = 0; i < xs.length; i++) {
                if (Math.abs(xs[i]) < 0.75F * maxX) continue;
                inner = Math.min(inner, ys[i] * 16.0F);
                outer = Math.max(outer, ys[i] * 16.0F);
            }
        }
        return inner < outer ? new float[]{inner, outer} : null;
    }

    /** (z, y, radius) of a wheel in model pixels, from its and its children's meshes. */
    private static float[] wheelCircle(String name, BedrockBone bone, Map<BedrockBone, List<PolyMesh>> meshes,
                                       Float override) {
        float[] bounds = {Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
        collect(bone, meshes, bounds);
        if (!(bounds[1] > bounds[0]) || !(bounds[3] > bounds[2])) return null;
        float cy = (bounds[0] + bounds[1]) * 0.5F;
        float cz = (bounds[2] + bounds[3]) * 0.5F;
        float radius = Math.max(bounds[1] - bounds[0], bounds[3] - bounds[2]) * 0.5F;
        float scale = Math.max(Math.abs(bone.yScale), Math.abs(bone.zScale));
        if (scale > 0 && Math.abs(scale - 1.0F) > 1.0E-4F) radius *= scale;
        Float suffix = suffixRadius(name);
        if (suffix != null) radius = suffix;
        if (override != null) radius = override;
        return radius > 0 ? new float[]{cz, cy, radius} : null;
    }

    private static void collect(BedrockBone bone, Map<BedrockBone, List<PolyMesh>> meshes, float[] bounds) {
        List<PolyMesh> own = meshes.get(bone);
        if (own != null) {
            float[] pivot = restPivot(bone);
            for (PolyMesh mesh : own) {
                BvpPolyMeshVertexAccessor v = (BvpPolyMeshVertexAccessor) mesh;
                float[] ys = v.bvp$positionsY();
                float[] zs = v.bvp$positionsZ();
                for (int i = 0; i < ys.length; i++) {
                    float y = pivot[1] + ys[i] * 16.0F;
                    float z = pivot[2] + zs[i] * 16.0F;
                    bounds[0] = Math.min(bounds[0], y);
                    bounds[1] = Math.max(bounds[1], y);
                    bounds[2] = Math.min(bounds[2], z);
                    bounds[3] = Math.max(bounds[3], z);
                }
            }
        }
        for (BedrockBone child : bone.getChildren()) collect(child, meshes, bounds);
    }

    /** Superb Modern Combat's radius suffix: "Lroll3_r45" is a 0.45 block (7.2 px) wheel. */
    private static Float suffixRadius(String name) {
        int at = name.lastIndexOf("_r");
        if (at < 0 || at + 2 >= name.length()) return null;
        try {
            int hundredths = Integer.parseInt(name.substring(at + 2));
            return hundredths > 0 ? hundredths * 0.16F : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
