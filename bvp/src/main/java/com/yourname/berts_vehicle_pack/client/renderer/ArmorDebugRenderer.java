package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.api.vehicle.pose.VehiclePoseSnapshot;
import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;
import com.atsuishio.superbwarfare.client.renderer.vehicle.VehicleRenderPartSnapshot;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorBox;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorProfile;
import com.yourname.berts_vehicle_pack.armor.EraBrickIds;
import com.yourname.berts_vehicle_pack.armor.ArmorCoordinateFrame;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;

import java.util.Set;

public final class ArmorDebugRenderer {
    private static final int HIT_HIGHLIGHT_TICKS = 80;
    private static final float ARMOR_FILL_ALPHA = 0.74F;
    private static final float ARMOR_LINE_ALPHA = 0.88F;
    private static final float INTERNAL_FILL_ALPHA = 0.58F;
    private static final float INTERNAL_LINE_ALPHA = 0.78F;
    private static final float ENGINE_FILL_ALPHA = 0.64F;
    private static final float ENGINE_LINE_ALPHA = 0.84F;
    private static final float MODULE_FILL_ALPHA = 0.62F;
    private static final float MODULE_LINE_ALPHA = 0.9F;
    private static final float AMMO_RACK_FILL_ALPHA = 0.68F;
    private static final float AMMO_RACK_LINE_ALPHA = 0.86F;
    private static final float TRACK_FILL_ALPHA = 0.7F;
    private static final float TRACK_LINE_ALPHA = 0.9F;
    private static final float ERA_FILL_ALPHA = 0.64F;
    private static final float ERA_LINE_ALPHA = 0.9F;
    private static final RenderType ARMOR_XRAY_FILL = RenderType.m_286086_();
    private static final RenderType ARMOR_XRAY_LINE = RenderType.m_110504_();
    private static boolean commandXrayEnabled;

    private ArmorDebugRenderer() {
    }

    public static boolean shouldRender() {
        return DebugFeaturePolicy.allowsDebugTools() && (commandXrayEnabled
                || Minecraft.m_91087_().m_91290_().m_114377_());
    }

    public static boolean toggleCommandXray() {
        commandXrayEnabled = DebugFeaturePolicy.allowsDebugTools() && !commandXrayEnabled;
        return commandXrayEnabled;
    }

    public static void setCommandXrayEnabled(boolean enabled) {
        commandXrayEnabled = enabled && DebugFeaturePolicy.allowsDebugTools();
    }

    /**
     * Renders against the exact immutable part sample already consumed by the visible model.
     * The renderer transaction is the only caller, so a debug frame can never be reconstructed
     * from a second mutable/interpolated turret-axis calculation.
     */
    static void render(ArmoredVehicleEntity entity, float entityYaw, float partialTicks, PoseStack poseStack,
                       MultiBufferSource bufferSource, VehiclePoseSnapshot presentationPose,
                       VehicleRenderPartSnapshot renderParts) {
        if (!shouldRender()) {
            return;
        }

        ArmorProfile profile = ArmorProfiles.get(entity.getArmorProfileId());
        if (!profile.hasDebugVolumes()) {
            return;
        }

        double minArmor = Double.MAX_VALUE;
        double maxArmor = -Double.MAX_VALUE;
        for (ArmorBox plate : profile.plates) {
            minArmor = Math.min(minArmor, plate.armorMm);
            maxArmor = Math.max(maxArmor, plate.armorMm);
        }
        if (minArmor == Double.MAX_VALUE) {
            minArmor = 0.0D;
            maxArmor = 0.0D;
        }

        String hitPlate = entity.getLastArmorHitAge() <= HIT_HIGHLIGHT_TICKS ? entity.getLastArmorHitPlate() : "";
        RenderType fillType = ARMOR_XRAY_FILL;
        RenderType lineType = ARMOR_XRAY_LINE;
        // F3+B adds wireframes for the real damage volumes; the explicit X-ray command
        // retains its filled armor view. Both consume the model's same pose snapshot.
        VertexConsumer fill = commandXrayEnabled ? bufferSource.m_6299_(fillType) : null;
        VertexConsumer lines = bufferSource.m_6299_(lineType);
        boolean passengerStation = entity.isHullParentedPassengerWeaponStation();
        float turretFrameYaw = renderParts == null
                ? 0.0F
                : passengerStation ? renderParts.getStationYawRelativeToTurretDegrees()
                        + entity.getPassengerWeaponStationBaseYawDegrees()
                        : renderParts.getTurretYawFromRenderedHullDegrees();
        String profileId = entity.getArmorProfileId();
        ArmorCoordinateFrame.BarrelFrame barrelFrame = renderParts == null ? null
                : ArmorCoordinateFrame.renderedBarrelFrame(
                        passengerStation ? entity.getPassengerWeaponStationPosition() : entity.getTurretPos(),
                        passengerStation ? entity.getPassengerWeaponStationBarrelPosition() : entity.getBarrelPosition(),
                        turretFrameYaw, passengerStation ? renderParts.getStationPitchDegrees()
                                : renderParts.getBarrelPitchDegrees(),
                        ArmorProfiles.mirrorsProfileX(profileId));
        Set<String> spentEra = EraBrickIds.parseStateIds(entity.getBvpSpentEraBricks());

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        poseStack.m_85836_();
        try {
            BaseTankRenderer.applyVehicleRenderAxis(entity, entityYaw, partialTicks, poseStack, presentationPose);

            for (ArmorBox plate : profile.plates) {
                renderArmorPlate(entity, plate, hitPlate, minArmor, maxArmor, turretFrameYaw, barrelFrame,
                        poseStack, fill, lines);
            }

            for (ArmorBox internal : profile.sensitiveInternals) {
                renderBox(entity, internal, turretFrameYaw, barrelFrame, poseStack, fill, lines,
                        0.15F, 0.65F, 1.0F, INTERNAL_FILL_ALPHA, INTERNAL_LINE_ALPHA);
            }

            for (ArmorBox engine : profile.engineBoxes) {
                renderBox(entity, engine, turretFrameYaw, barrelFrame, poseStack, fill, lines,
                        0.75F, 0.45F, 0.08F, ENGINE_FILL_ALPHA, ENGINE_LINE_ALPHA);
            }

            // Module boxes include weapon systems and other damageable internals.  They are
            // already loaded by ArmorProfiles and consumed by the hit resolver; draw the same
            // boxes here so armor X-ray reflects the authoritative damage model.
            for (ArmorBox module : profile.moduleBoxes) {
                renderBox(entity, module, turretFrameYaw, barrelFrame, poseStack, fill, lines,
                        0.82F, 0.18F, 1.0F, MODULE_FILL_ALPHA, MODULE_LINE_ALPHA);
            }

            for (ArmorBox ammoRack : profile.ammoRacks) {
                renderBox(entity, ammoRack, turretFrameYaw, barrelFrame, poseStack, fill, lines,
                        1.0F, 0.62F, 0.05F, AMMO_RACK_FILL_ALPHA, AMMO_RACK_LINE_ALPHA);
            }

            for (ArmorBox track : profile.trackBoxes) {
                renderBox(entity, track, turretFrameYaw, barrelFrame, poseStack, fill, lines,
                        0.08F, 0.82F, 1.0F, TRACK_FILL_ALPHA, TRACK_LINE_ALPHA);
            }

            for (ArmorBox era : profile.eraBoxes) {
                boolean spent = spentEra.contains(EraBrickIds.stateId(era.name));
                float green = spent ? 0.0F : 0.72F;
                float blue = spent ? 0.0F : 0.08F;
                renderBox(entity, era, turretFrameYaw, barrelFrame, poseStack, fill, lines,
                        1.0F,
                        green,
                        blue,
                        ERA_FILL_ALPHA,
                        ERA_LINE_ALPHA);
            }
        } finally {
            poseStack.m_85849_();
            RenderSystem.disableDepthTest();
            RenderSystem.depthFunc(GL11.GL_ALWAYS);
            flushXrayBuffers(bufferSource, fillType, lineType, fill != null);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
        }
    }

    private static void flushXrayBuffers(MultiBufferSource bufferSource, RenderType fillType, RenderType lineType,
                                         boolean filled) {
        if (bufferSource instanceof MultiBufferSource.BufferSource immediate) {
            RenderSystem.disableDepthTest();
            RenderSystem.depthFunc(GL11.GL_ALWAYS);
            if (filled) immediate.m_109912_(fillType);
            RenderSystem.disableDepthTest();
            RenderSystem.depthFunc(GL11.GL_ALWAYS);
            immediate.m_109912_(lineType);
        }
    }

    private static void renderArmorPlate(ArmoredVehicleEntity entity, ArmorBox plate, String hitPlate,
                                         double minArmor, double maxArmor, float turretFrameYaw,
                                         ArmorCoordinateFrame.BarrelFrame barrelFrame,
                                         PoseStack poseStack, VertexConsumer fill, VertexConsumer lines) {
        float red = 1.0F;
        float green = 0.0F;
        float blue = 0.0F;
        if (!plate.name.equals(hitPlate) && maxArmor > minArmor + 1.0E-6D) {
            float t = (float) Math.max(0.0D, Math.min(1.0D,
                    (plate.armorMm - minArmor) / (maxArmor - minArmor)));
            red = t;
            green = 1.0F - t;
        }
        renderBox(entity, plate, turretFrameYaw, barrelFrame, poseStack, fill, lines,
                red, green, blue, ARMOR_FILL_ALPHA, ARMOR_LINE_ALPHA);
    }

    private static void renderBox(ArmoredVehicleEntity entity, ArmorBox box, float turretFrameYaw,
                                  ArmorCoordinateFrame.BarrelFrame barrelFrame,
                                  PoseStack poseStack, VertexConsumer fill, VertexConsumer lines,
                                  float red, float green, float blue, float fillAlpha, float lineAlpha) {
        if (box.isBarrelFrame() && barrelFrame == null) return;
        if (box.isMesh()) {
            renderMesh(entity, box, turretFrameYaw, barrelFrame, poseStack, fill, lines,
                    red, green, blue, fillAlpha, lineAlpha);
            return;
        }
        DebugVec[] corners = corners(entity, box, turretFrameYaw, barrelFrame);
        if (fill != null) filledBox(fill, poseStack, corners, red, green, blue, fillAlpha);
        lineBox(lines, poseStack, corners, red, green, blue, lineAlpha);
    }

    /**
     * A mesh volume: translucent triangles (as degenerate debug quads) and its feature edges
     * (outline and creases, not the diagonals of flat faces), in the same colours as boxes.
     */
    private static void renderMesh(ArmoredVehicleEntity entity, ArmorBox box, float turretFrameYaw,
                                   ArmorCoordinateFrame.BarrelFrame barrelFrame,
                                   PoseStack poseStack, VertexConsumer fill, VertexConsumer lines,
                                   float red, float green, float blue, float fillAlpha, float lineAlpha) {
        PoseStack.Pose pose = poseStack.m_85850_();
        if (fill != null) {
            box.volume.forEachTriangle((ax, ay, az, bx, by, bz, cx, cy, cz) -> {
                DebugVec a = framePoint(entity, box, turretFrameYaw, barrelFrame, ax, ay, az);
                DebugVec b = framePoint(entity, box, turretFrameYaw, barrelFrame, bx, by, bz);
                DebugVec c = framePoint(entity, box, turretFrameYaw, barrelFrame, cx, cy, cz);
                quad(fill, pose, a, b, c, c, red, green, blue, fillAlpha);
            });
        }
        box.volume.forEachFeatureEdge((ax, ay, az, bx, by, bz) -> line(lines, pose,
                framePoint(entity, box, turretFrameYaw, barrelFrame, ax, ay, az),
                framePoint(entity, box, turretFrameYaw, barrelFrame, bx, by, bz),
                red, green, blue, lineAlpha));
    }

    private static DebugVec[] corners(ArmoredVehicleEntity entity, ArmorBox box, float turretFrameYaw,
                                      ArmorCoordinateFrame.BarrelFrame barrelFrame) {
        double hx = box.halfSize.x;
        double hy = box.halfSize.y;
        double hz = box.halfSize.z;
        return new DebugVec[]{
                boxPoint(entity, box, turretFrameYaw, barrelFrame, -hx, -hy, -hz),
                boxPoint(entity, box, turretFrameYaw, barrelFrame, hx, -hy, -hz),
                boxPoint(entity, box, turretFrameYaw, barrelFrame, hx, hy, -hz),
                boxPoint(entity, box, turretFrameYaw, barrelFrame, -hx, hy, -hz),
                boxPoint(entity, box, turretFrameYaw, barrelFrame, -hx, -hy, hz),
                boxPoint(entity, box, turretFrameYaw, barrelFrame, hx, -hy, hz),
                boxPoint(entity, box, turretFrameYaw, barrelFrame, hx, hy, hz),
                boxPoint(entity, box, turretFrameYaw, barrelFrame, -hx, hy, hz),
        };
    }

    private static DebugVec boxPoint(ArmoredVehicleEntity entity, ArmorBox box, float turretFrameYaw,
                                     ArmorCoordinateFrame.BarrelFrame barrelFrame,
                                     double x, double y, double z) {
        DebugVec rotated = rotate(new DebugVec(x, y, z), box.rotationDeg.x, box.rotationDeg.y, box.rotationDeg.z);
        return framePoint(entity, box, turretFrameYaw, barrelFrame,
                box.center.x + rotated.x, box.center.y + rotated.y, box.center.z + rotated.z);
    }

    /** A point in the volume's own frame (hull, turret or barrel, rest pose) in the rendered visual frame. */
    private static DebugVec framePoint(ArmoredVehicleEntity entity, ArmorBox box, float turretFrameYaw,
                                       ArmorCoordinateFrame.BarrelFrame barrelFrame,
                                       double x, double y, double z) {
        if (box.isBarrelFrame()) {
            return armorLocalToVisualLocal(entity, fromArmorVec(barrelFrame.toHullPoint(x, y, z)));
        }
        DebugVec visualPoint = armorLocalToVisualLocal(entity, new DebugVec(x, y, z));
        if (!box.isTurretFrame()) {
            return visualPoint;
        }
        return rotateAround(visualPoint, armorLocalToVisualLocal(entity, turretPivot(entity)), turretFrameYaw);
    }

    private static DebugVec armorLocalToVisualLocal(ArmoredVehicleEntity entity, DebugVec point) {
        if (ArmorProfiles.mirrorsProfileX(entity.getArmorProfileId())) {
            return new DebugVec(-point.x, point.y, point.z);
        }
        return point;
    }

    private static DebugVec rotateAround(DebugVec point, DebugVec pivot, double yawDegrees) {
        DebugVec relative = new DebugVec(point.x - pivot.x, point.y - pivot.y, point.z - pivot.z);
        DebugVec rotated = rotateY(relative, yawDegrees);
        return new DebugVec(pivot.x + rotated.x, pivot.y + rotated.y, pivot.z + rotated.z);
    }

    private static DebugVec turretPivot(ArmoredVehicleEntity entity) {
        Vec3 pivot = entity.isHullParentedPassengerWeaponStation()
                ? entity.getPassengerWeaponStationPosition() : entity.getTurretPos();
        return pivot == null
                ? new DebugVec(0.0D, 0.0D, 0.0D)
                : fromArmorVec(ArmorCoordinateFrame.sbwVehicleLocalToVisualLocal(pivot));
    }

    private static DebugVec fromArmorVec(ArmorProfiles.Vec value) {
        return new DebugVec(value.x, value.y, value.z);
    }

    private static DebugVec rotate(DebugVec value, double xDeg, double yDeg, double zDeg) {
        return rotateZ(rotateY(rotateX(value, xDeg), yDeg), zDeg);
    }

    private static DebugVec rotateX(DebugVec value, double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new DebugVec(value.x, value.y * cos - value.z * sin, value.y * sin + value.z * cos);
    }

    private static DebugVec rotateY(DebugVec value, double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new DebugVec(value.x * cos + value.z * sin, value.y, -value.x * sin + value.z * cos);
    }

    private static DebugVec rotateZ(DebugVec value, double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new DebugVec(value.x * cos - value.y * sin, value.x * sin + value.y * cos, value.z);
    }

    private static void filledBox(VertexConsumer consumer, PoseStack poseStack, DebugVec[] c,
                                  float red, float green, float blue, float alpha) {
        PoseStack.Pose pose = poseStack.m_85850_();
        quad(consumer, pose, c[0], c[1], c[2], c[3], red, green, blue, alpha);
        quad(consumer, pose, c[5], c[4], c[7], c[6], red, green, blue, alpha);
        quad(consumer, pose, c[4], c[0], c[3], c[7], red, green, blue, alpha);
        quad(consumer, pose, c[1], c[5], c[6], c[2], red, green, blue, alpha);
        quad(consumer, pose, c[3], c[2], c[6], c[7], red, green, blue, alpha);
        quad(consumer, pose, c[4], c[5], c[1], c[0], red, green, blue, alpha);
    }

    private static void lineBox(VertexConsumer consumer, PoseStack poseStack, DebugVec[] c,
                                float red, float green, float blue, float alpha) {
        PoseStack.Pose pose = poseStack.m_85850_();
        line(consumer, pose, c[0], c[1], red, green, blue, alpha);
        line(consumer, pose, c[1], c[2], red, green, blue, alpha);
        line(consumer, pose, c[2], c[3], red, green, blue, alpha);
        line(consumer, pose, c[3], c[0], red, green, blue, alpha);
        line(consumer, pose, c[4], c[5], red, green, blue, alpha);
        line(consumer, pose, c[5], c[6], red, green, blue, alpha);
        line(consumer, pose, c[6], c[7], red, green, blue, alpha);
        line(consumer, pose, c[7], c[4], red, green, blue, alpha);
        line(consumer, pose, c[0], c[4], red, green, blue, alpha);
        line(consumer, pose, c[1], c[5], red, green, blue, alpha);
        line(consumer, pose, c[2], c[6], red, green, blue, alpha);
        line(consumer, pose, c[3], c[7], red, green, blue, alpha);
    }

    private static void quad(VertexConsumer consumer, PoseStack.Pose pose,
                             DebugVec v1, DebugVec v2, DebugVec v3, DebugVec v4,
                             float red, float green, float blue, float alpha) {
        vertex(consumer, pose, v1, red, green, blue, alpha);
        vertex(consumer, pose, v2, red, green, blue, alpha);
        vertex(consumer, pose, v3, red, green, blue, alpha);
        vertex(consumer, pose, v4, red, green, blue, alpha);
    }

    private static void line(VertexConsumer consumer, PoseStack.Pose pose, DebugVec from, DebugVec to,
                             float red, float green, float blue, float alpha) {
        DebugVec normal = new DebugVec(to.x - from.x, to.y - from.y, to.z - from.z).normalize();
        lineVertex(consumer, pose, from, normal, red, green, blue, alpha);
        lineVertex(consumer, pose, to, normal, red, green, blue, alpha);
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, DebugVec point,
                               float red, float green, float blue, float alpha) {
        consumer.m_252986_(pose.m_252922_(), (float) point.x, (float) point.y, (float) point.z)
                .m_85950_(red, green, blue, alpha)
                .m_5752_();
    }

    private static void lineVertex(VertexConsumer consumer, PoseStack.Pose pose, DebugVec point, DebugVec normal,
                                   float red, float green, float blue, float alpha) {
        consumer.m_252986_(pose.m_252922_(), (float) point.x, (float) point.y, (float) point.z)
                .m_85950_(red, green, blue, alpha)
                .m_252939_(pose.m_252943_(), (float) normal.x, (float) normal.y, (float) normal.z)
                .m_5752_();
    }

    private static final class DebugVec {
        final double x;
        final double y;
        final double z;

        DebugVec(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        DebugVec normalize() {
            double length = Math.sqrt(this.x * this.x + this.y * this.y + this.z * this.z);
            if (length < 1.0E-7D) {
                return new DebugVec(0.0D, 1.0D, 0.0D);
            }
            return new DebugVec(this.x / length, this.y / length, this.z / length);
        }
    }
}
