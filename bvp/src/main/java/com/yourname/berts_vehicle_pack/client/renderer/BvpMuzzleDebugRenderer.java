package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderGuiEvent;

import java.util.Locale;
import java.util.OptionalDouble;

/** Client-only view of the exact next-shot origin and pre-spread direction for the selected weapon. */
public final class BvpMuzzleDebugRenderer {
    private static final RenderType LINE_TYPE = MuzzleLineRenderType.createNoDepthLines();
    private static final double DIRECTION_LENGTH = 8.0D;
    private static final double MUZZLE_BOX_HALF_SIZE = 0.055D;
    private static final double MUZZLE_CROSS_HALF_SIZE = 0.14D;
    private static final double END_BOX_HALF_SIZE = 0.045D;
    private static boolean enabled;

    private BvpMuzzleDebugRenderer() {
    }

    public static boolean isEnabled() {
        return enabled && DebugFeaturePolicy.allowsDebugTools();
    }

    public static boolean toggle() {
        setEnabled(!enabled);
        return isEnabled();
    }

    public static void setEnabled(boolean enabled) {
        BvpMuzzleDebugRenderer.enabled = enabled && DebugFeaturePolicy.allowsDebugTools();
    }

    public static void render(ArmoredVehicleEntity entity, float partialTicks,
                              PoseStack poseStack, MultiBufferSource bufferSource) {
        if (!isEnabled()) {
            return;
        }

        MuzzleSample sample = sample(entity, partialTicks);
        if (sample == null) {
            return;
        }

        Vec3 entityPosition = entity.getResolvedChassisPosition(partialTicks);
        Vec3 muzzle = sample.position();
        Vec3 direction = sample.direction();
        Vec3 localMuzzle = new Vec3(
                muzzle.f_82479_ - entityPosition.f_82479_,
                muzzle.f_82480_ - entityPosition.f_82480_,
                muzzle.f_82481_ - entityPosition.f_82481_);
        Vec3 localEnd = new Vec3(
                localMuzzle.f_82479_ + direction.f_82479_ * DIRECTION_LENGTH,
                localMuzzle.f_82480_ + direction.f_82480_ * DIRECTION_LENGTH,
                localMuzzle.f_82481_ + direction.f_82481_ * DIRECTION_LENGTH);
        if (!isFinite(localMuzzle) || !isFinite(localEnd)) {
            return;
        }

        VertexConsumer lines = bufferSource.m_6299_(LINE_TYPE);
        poseStack.m_85836_();
        try {
            PoseStack.Pose pose = poseStack.m_85850_();
            lineBox(lines, pose, localMuzzle, MUZZLE_BOX_HALF_SIZE, 1.0F, 0.82F, 0.10F, 1.0F);
            axisCross(lines, pose, localMuzzle, MUZZLE_CROSS_HALF_SIZE, 1.0F, 0.82F, 0.10F, 1.0F);
            line(lines, pose, localMuzzle, localEnd, 0.15F, 1.0F, 0.20F, 1.0F);
            lineBox(lines, pose, localEnd, END_BOX_HALF_SIZE, 1.0F, 0.10F, 0.08F, 1.0F);
        } finally {
            poseStack.m_85849_();
            if (bufferSource instanceof MultiBufferSource.BufferSource immediate) {
                immediate.m_109912_(LINE_TYPE);
            }
        }
    }

    public static void renderHud(RenderGuiEvent.Post event) {
        if (!isEnabled()) {
            return;
        }

        Minecraft minecraft = Minecraft.m_91087_();
        Player player = minecraft == null ? null : minecraft.f_91074_;
        if (player == null || !(player.m_20202_() instanceof ArmoredVehicleEntity vehicle)) {
            return;
        }

        MuzzleSample sample = sample(vehicle, 1.0F);
        if (sample == null) {
            return;
        }

        String[] lines = {
                "MUZZLE FIRE@1  seat=" + sample.seatIndex() + "  weapon=" + sample.weaponName(),
                formatVector("P", sample.position()),
                formatVector("D", sample.direction())
        };
        int[] colors = {0xFFFFD54F, 0xFFFFE680, 0xFF66FF66};
        GuiGraphics graphics = event.getGuiGraphics();
        var font = minecraft.f_91062_;
        int x = 6;
        int y = 6;
        int maxWidth = 0;
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, font.m_92895_(line));
        }
        graphics.m_280509_(x - 3, y - 3, x + maxWidth + 3, y + lines.length * 10 + 1, 0xB0000000);
        for (int index = 0; index < lines.length; index++) {
            graphics.m_280056_(font, lines[index], x, y + index * 10, colors[index], true);
        }
    }

    private static MuzzleSample sample(ArmoredVehicleEntity expectedVehicle, float partialTicks) {
        Minecraft minecraft = Minecraft.m_91087_();
        Player player = minecraft == null ? null : minecraft.f_91074_;
        if (player == null || player.m_20202_() != expectedVehicle) {
            return null;
        }

        int seatIndex = expectedVehicle.getSeatIndex(player);
        String weaponName = expectedVehicle.getGunName(seatIndex);
        if (seatIndex < 0 || weaponName == null || expectedVehicle.getGunData(weaponName) == null) {
            return null;
        }

        Vec3 position = expectedVehicle.getShootPos(player, partialTicks);
        Vec3 rawDirection = expectedVehicle.getShootVec(player, partialTicks);
        if (!isFinite(position) || !isFinite(rawDirection)) {
            return null;
        }
        double lengthSquared = rawDirection.f_82479_ * rawDirection.f_82479_
                + rawDirection.f_82480_ * rawDirection.f_82480_
                + rawDirection.f_82481_ * rawDirection.f_82481_;
        if (!Double.isFinite(lengthSquared) || lengthSquared <= 1.0E-12D) {
            return null;
        }

        double inverseLength = 1.0D / Math.sqrt(lengthSquared);
        Vec3 direction = new Vec3(
                rawDirection.f_82479_ * inverseLength,
                rawDirection.f_82480_ * inverseLength,
                rawDirection.f_82481_ * inverseLength);
        return new MuzzleSample(seatIndex, weaponName, position, direction);
    }

    private static String formatVector(String label, Vec3 value) {
        return String.format(Locale.ROOT, "%s  %.6f  %.6f  %.6f",
                label, value.f_82479_, value.f_82480_, value.f_82481_);
    }

    private static boolean isFinite(Vec3 value) {
        return value != null
                && Double.isFinite(value.f_82479_)
                && Double.isFinite(value.f_82480_)
                && Double.isFinite(value.f_82481_);
    }

    private static void axisCross(VertexConsumer consumer, PoseStack.Pose pose, Vec3 center, double half,
                                  float red, float green, float blue, float alpha) {
        line(consumer, pose,
                new Vec3(center.f_82479_ - half, center.f_82480_, center.f_82481_),
                new Vec3(center.f_82479_ + half, center.f_82480_, center.f_82481_),
                red, green, blue, alpha);
        line(consumer, pose,
                new Vec3(center.f_82479_, center.f_82480_ - half, center.f_82481_),
                new Vec3(center.f_82479_, center.f_82480_ + half, center.f_82481_),
                red, green, blue, alpha);
        line(consumer, pose,
                new Vec3(center.f_82479_, center.f_82480_, center.f_82481_ - half),
                new Vec3(center.f_82479_, center.f_82480_, center.f_82481_ + half),
                red, green, blue, alpha);
    }

    private static void lineBox(VertexConsumer consumer, PoseStack.Pose pose, Vec3 center, double half,
                                float red, float green, float blue, float alpha) {
        double minX = center.f_82479_ - half;
        double maxX = center.f_82479_ + half;
        double minY = center.f_82480_ - half;
        double maxY = center.f_82480_ + half;
        double minZ = center.f_82481_ - half;
        double maxZ = center.f_82481_ + half;

        line(consumer, pose, new Vec3(minX, minY, minZ), new Vec3(maxX, minY, minZ), red, green, blue, alpha);
        line(consumer, pose, new Vec3(maxX, minY, minZ), new Vec3(maxX, maxY, minZ), red, green, blue, alpha);
        line(consumer, pose, new Vec3(maxX, maxY, minZ), new Vec3(minX, maxY, minZ), red, green, blue, alpha);
        line(consumer, pose, new Vec3(minX, maxY, minZ), new Vec3(minX, minY, minZ), red, green, blue, alpha);
        line(consumer, pose, new Vec3(minX, minY, maxZ), new Vec3(maxX, minY, maxZ), red, green, blue, alpha);
        line(consumer, pose, new Vec3(maxX, minY, maxZ), new Vec3(maxX, maxY, maxZ), red, green, blue, alpha);
        line(consumer, pose, new Vec3(maxX, maxY, maxZ), new Vec3(minX, maxY, maxZ), red, green, blue, alpha);
        line(consumer, pose, new Vec3(minX, maxY, maxZ), new Vec3(minX, minY, maxZ), red, green, blue, alpha);
        line(consumer, pose, new Vec3(minX, minY, minZ), new Vec3(minX, minY, maxZ), red, green, blue, alpha);
        line(consumer, pose, new Vec3(maxX, minY, minZ), new Vec3(maxX, minY, maxZ), red, green, blue, alpha);
        line(consumer, pose, new Vec3(maxX, maxY, minZ), new Vec3(maxX, maxY, maxZ), red, green, blue, alpha);
        line(consumer, pose, new Vec3(minX, maxY, minZ), new Vec3(minX, maxY, maxZ), red, green, blue, alpha);
    }

    private static void line(VertexConsumer consumer, PoseStack.Pose pose, Vec3 from, Vec3 to,
                             float red, float green, float blue, float alpha) {
        double normalX = to.f_82479_ - from.f_82479_;
        double normalY = to.f_82480_ - from.f_82480_;
        double normalZ = to.f_82481_ - from.f_82481_;
        double normalLength = Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
        if (normalLength > 0.0D) {
            normalX /= normalLength;
            normalY /= normalLength;
            normalZ /= normalLength;
        } else {
            normalX = 0.0D;
            normalY = 1.0D;
            normalZ = 0.0D;
        }
        lineVertex(consumer, pose, from, normalX, normalY, normalZ, red, green, blue, alpha);
        lineVertex(consumer, pose, to, normalX, normalY, normalZ, red, green, blue, alpha);
    }

    private static void lineVertex(VertexConsumer consumer, PoseStack.Pose pose, Vec3 point,
                                   double normalX, double normalY, double normalZ,
                                   float red, float green, float blue, float alpha) {
        consumer.m_252986_(pose.m_252922_(),
                        (float) point.f_82479_, (float) point.f_82480_, (float) point.f_82481_)
                .m_85950_(red, green, blue, alpha)
                .m_252939_(pose.m_252943_(), (float) normalX, (float) normalY, (float) normalZ)
                .m_5752_();
    }

    /** Accesses RenderType's protected state shards to isolate this overlay from vanilla's shared line batch. */
    private static final class MuzzleLineRenderType extends RenderType {
        private MuzzleLineRenderType(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                                     boolean affectsCrumbling, boolean sortOnUpload,
                                     Runnable setupState, Runnable clearState) {
            super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupState, clearState);
        }

        private static RenderType createNoDepthLines() {
            return RenderType.m_173215_(
                    "bvp_muzzle_debug_lines",
                    DefaultVertexFormat.f_166851_,
                    VertexFormat.Mode.LINES,
                    256,
                    false,
                    false,
                    RenderType.CompositeState.m_110628_()
                            .m_173292_(f_173095_)
                            .m_110673_(new RenderStateShard.LineStateShard(OptionalDouble.empty()))
                            .m_110669_(f_110119_)
                            .m_110685_(f_110139_)
                            .m_110663_(f_110111_)
                            .m_110675_(f_110129_)
                            .m_110687_(f_110115_)
                            .m_110661_(f_110110_)
                            .m_110691_(false));
        }
    }

    private record MuzzleSample(int seatIndex, String weaponName, Vec3 position, Vec3 direction) {
    }
}
