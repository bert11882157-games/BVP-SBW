package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.client.renderer.JettisonedParts;
import com.atsuishio.superbwarfare.client.renderer.vehicle.VehicleRenderBackendContext;
import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
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
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Jettisoned launcher tubes (owner 2026-09-29: the discarded 9P149 Shturm-S tube is not part of the reload animation;
 * the shot jettisons it toward the ground as a physics object that despawns after 5 seconds).
 *
 * {@link LauncherReloadAnimator} notices the shot and queues the aim the tube left with; the next render of that
 * vehicle captures the {@code launcher_tube_spent} bone's meshes at exactly that pose (where the fired tube sat on
 * the arm) and hands them to SBW's {@link JettisonedParts}, which throws, tumbles, lands and removes them. The bone
 * itself stays hidden on the vehicle.
 */
final class BvpLauncherJettison {
    static final String SPENT_BONE = "launcher_tube_spent";
    /** Despawn after 5 seconds. */
    private static final int LIFE_TICKS = 100;
    /** Throw in model space, blocks per tick: out toward the hatch side (+x), a little up, slightly back. */
    private static final float THROW_OUT = 0.10F, THROW_UP = 0.04F, THROW_BACK = 0.02F;
    /** End-over-end tumble and a little roll, radians per tick about the tube's own axes. */
    private static final double TUMBLE = 0.22, ROLL = 0.05;

    /** Vehicles whose tube was just fired: the aim (yaw, pitch in model radians) it left the arm with. */
    private static final Map<VehicleEntity, float[]> PENDING = new WeakHashMap<>();

    private BvpLauncherJettison() {
    }

    static void queue(VehicleEntity entity, float yawRad, float pitchRad) {
        PENDING.put(entity, new float[]{yawRad, pitchRad});
    }

    /** Spawns a queued tube for this vehicle; call after the model animations of this frame were applied. */
    static void capture(VehicleRenderBackendContext context, PolyMeshModel model, ResourceLocation texture) {
        VehicleEntity vehicle = context.getVehicle();
        float[] aim = PENDING.remove(vehicle);
        if (aim == null || !(vehicle instanceof GeoVehicleEntity geo)) return;
        BedrockBone spent = model.getBone(SPENT_BONE);
        if (spent == null) return;
        List<PolyMesh> meshes = ((BvpPolyMeshModelPrewarmAccessor) (Object) model).bvp$meshes()
                .getOrDefault(spent, List.of());
        if (meshes.isEmpty()) return;
        try {
            // the tube as it sat on the arm when it fired
            RendererBones.setPositionOffset(spent, 0.0F, 0.0F, 0.0F);
            RendererBones.setRotation(spent, aim[1], aim[0], 0.0F);

            PoseStack axis = new PoseStack();
            BaseVehicleRenderer.applyVehicleRenderAxis(geo, context.getEntityYaw(), context.getPartialTick(), axis,
                    context.getChassisPresentation().getPose());
            Matrix4f transform = new Matrix4f(axis.m_85850_().m_252922_());

            PoseStack local = new PoseStack();
            List<BedrockBone> chain = new ArrayList<>();
            for (BedrockBone bone = spent; bone != null; bone = bone.parent) chain.add(bone);
            Collections.reverse(chain);
            for (BedrockBone bone : chain) bone.translateAndRotateAndScale(local);
            Matrix4f boneToModel = new Matrix4f(local.m_85850_().m_252922_());

            float[] box = bounds(meshes);
            if (box == null) return;
            Vector3f center = new Vector3f((box[0] + box[3]) / 2, (box[1] + box[4]) / 2, (box[2] + box[5]) / 2);
            Vec3 half = new Vec3(Math.max(0.05, (box[3] - box[0]) / 2), Math.max(0.05, (box[4] - box[1]) / 2),
                    Math.max(0.05, (box[5] - box[2]) / 2));

            Vector3f modelCenter = boneToModel.transformPosition(new Vector3f(center));
            Vector3f worldOffset = transform.transformPosition(new Vector3f(modelCenter));
            Vec3 anchor = context.getChassisPresentation().getAnchor();
            Vec3 position = new Vec3(anchor.f_82479_ + worldOffset.x, anchor.f_82480_ + worldOffset.y,
                    anchor.f_82481_ + worldOffset.z);
            Quaternionf orientation = transform.getNormalizedRotation(new Quaternionf())
                    .mul(boneToModel.getNormalizedRotation(new Quaternionf()));

            Vector3f thrown = transform.transformDirection(new Vector3f(THROW_OUT, THROW_UP, THROW_BACK));
            Vec3 carried = vehicle.m_20184_();
            Vec3 velocity = new Vec3(thrown.x + carried.f_82479_, thrown.y + carried.f_82480_,
                    thrown.z + carried.f_82481_);
            Vec3 spin = new Vec3(TUMBLE, 0.0, ROLL);

            Matrix4f recenter = new Matrix4f().translation(-center.x, -center.y, -center.z);
            JettisonedParts.spawn(position, velocity, orientation, spin, half, LIFE_TICKS,
                    (pose, buffers, light) -> {
                        RenderType type = RenderType.m_110458_(texture);
                        type.m_110185_();
                        try {
                            for (PolyMesh mesh : meshes)
                                mesh.drawVBO(new Matrix4f(pose.m_85850_().m_252922_()).mul(recenter), light);
                        } finally {
                            type.m_110188_();
                        }
                    });
        } finally {
            RendererBones.hide(spent);
        }
    }

    /** Bone-local bounds of the meshes' vertices: {minX, minY, minZ, maxX, maxY, maxZ}, or null. */
    private static float[] bounds(List<PolyMesh> meshes) {
        float[] box = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        int count = 0;
        for (PolyMesh mesh : meshes) {
            var vertices = (BvpPolyMeshVertexAccessor) (Object) mesh;
            float[] xs = vertices.bvp$positionsX(), ys = vertices.bvp$positionsY(), zs = vertices.bvp$positionsZ();
            if (xs == null || ys == null || zs == null) continue;
            int n = Math.min(xs.length, Math.min(ys.length, zs.length));
            for (int i = 0; i < n; i++) {
                if (!Float.isFinite(xs[i]) || !Float.isFinite(ys[i]) || !Float.isFinite(zs[i])) continue;
                box[0] = Math.min(box[0], xs[i]); box[3] = Math.max(box[3], xs[i]);
                box[1] = Math.min(box[1], ys[i]); box[4] = Math.max(box[4], ys[i]);
                box[2] = Math.min(box[2], zs[i]); box[5] = Math.max(box[5], zs[i]);
                count++;
            }
        }
        return count >= 3 ? box : null;
    }
}
