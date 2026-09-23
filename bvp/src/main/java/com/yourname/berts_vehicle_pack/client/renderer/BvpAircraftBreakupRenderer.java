package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.api.vehicle.flight.AircraftWreckBreakup;
import com.atsuishio.superbwarfare.client.renderer.AircraftDetachedWings;
import com.atsuishio.superbwarfare.client.renderer.vehicle.VehicleRenderBackendContext;
import com.example.sbwmeshloader.core.PolyMesh;
import com.example.sbwmeshloader.core.PolyMeshModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.berts_vehicle_pack.mixin.BvpPolyMeshModelPrewarmAccessor;
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
                    Vector3f worldCenter = transform.transformPosition(new Vector3f(center));
                    Vec3 anchor = context.getChassisPresentation().getAnchor();
                    Vec3 position = new Vec3(anchor.f_82479_ + worldCenter.x, anchor.f_82480_ + worldCenter.y,
                            anchor.f_82481_ + worldCenter.z);
                    var meshes = new ArrayList<Mesh>();
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
                            meshes.add(new Mesh(mesh, new Matrix4f(local.m_85850_().m_252922_())));
                    }
                    if (!meshes.isEmpty()) {
                        int light = context.getPackedLight();
                        var attachedStores = side < 4 ? stores.captureWing((com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity) vehicle, side, light) : null;
                        AircraftDetachedWings.capture(vehicle, side, position,
                                transform.getNormalizedRotation(new Quaternionf()),
                                new Vec3((maxX - minX) / 2, (maxY - minY) / 2, (maxZ - minZ) / 2),
                                (pose, buffers, impacted) -> {
                                    RenderType type = RenderType.m_110458_(side >= 4 || impacted ? blackened : texture);
                                    type.m_110185_();
                                    try {
                                        for (Mesh mesh : meshes) mesh.mesh.drawVBO(
                                                new Matrix4f(pose.m_85850_().m_252922_()).mul(mesh.transform), light);
                                    } finally { type.m_110188_(); }
                                    if (attachedStores != null) {
                                        pose.m_85836_();
                                        try {
                                            pose.m_85837_(-center.x, -center.y, -center.z);
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
    private BvpAircraftBreakupRenderer() {}
}
