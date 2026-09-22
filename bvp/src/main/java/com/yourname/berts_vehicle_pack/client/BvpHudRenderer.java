package com.yourname.berts_vehicle_pack.client;

import com.atsuishio.superbwarfare.client.overlay.VehicleTargetSnapshot;
import com.atsuishio.superbwarfare.client.overlay.VehicleTeamOverlay;
import com.atsuishio.superbwarfare.client.overlay.VehicleHudLayout;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.tools.VectorToolKt;
import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.armor.VehicleModuleHealth;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import com.yourname.berts_vehicle_pack.entity.Ka50Entity;
import com.yourname.berts_vehicle_pack.entity.Mi24VEntity;
import com.yourname.berts_vehicle_pack.entity.armored.damage.BvpFieldRepairAction;
import com.yourname.berts_vehicle_pack.entity.helicopter.BvpHelicopterEntity;
import com.atsuishio.superbwarfare.api.vehicle.action.VehicleActionSnapshot;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderGuiEvent;

import java.util.Locale;

public final class BvpHudRenderer {
    private static final ResourceLocation TARGET_CARD_RENDERER_ID =
            new ResourceLocation(BertsVehiclePack.MODID, "target_card");
    private static final float TARGET_CARD_SCALE = 0.55F;
    private static final int TARGET_CARD_WIDTH = 76;
    private static final int TARGET_BAR_HEIGHT = 5;
    private static final int AUTOCANNON_HEAT_BAR_WIDTH = 86;
    private static final int AUTOCANNON_HEAT_BAR_HEIGHT = 6;
    private static final int HELI_GAUGE_HEIGHT = 124;
    private static final int HELI_GAUGE_BAR_WIDTH = 4;
    private static final int HELI_GAUGE_SPACING = 34;
    private static final int HELI_GAUGE_COLOR = 0xFFFFC700;

    private static boolean targetCardRendererRegistered;

    private BvpHudRenderer() {
    }

    public static void registerTargetCardRenderer() {
        if (targetCardRendererRegistered) {
            return;
        }
        targetCardRendererRegistered = true;
        VehicleTeamOverlay.registerTargetRenderer(TARGET_CARD_RENDERER_ID, BvpHudRenderer::renderTargetCard);
    }

    public static void renderPost(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.m_91087_();
        if (minecraft == null || minecraft.f_91074_ == null) {
            return;
        }

        BvpClientArmorEventStatus.render(event.getGuiGraphics());

        Entity vehicle = minecraft.f_91074_.m_20202_();
        if (!(vehicle instanceof ArmoredVehicleEntity armored)) {
            return;
        }

        renderFieldRepairHud(event.getGuiGraphics(), armored);
        renderHelicopterPowerGauges(event.getGuiGraphics(), armored);
        if (minecraft.f_91066_.m_92176_() != CameraType.FIRST_PERSON) {
            return;
        }

        renderAutocannonHeatHud(event.getGuiGraphics(), armored, minecraft.f_91074_);
    }

    private static boolean renderTargetCard(VehicleTargetSnapshot snapshot, GuiGraphics graphics,
                                            float partialTick, int screenWidth, int screenHeight) {
        if (!(snapshot.getTarget() instanceof ArmoredVehicleEntity target)) {
            return false;
        }
        return renderSmallTargetCard(graphics, target, snapshot.getRange());
    }

    private static boolean renderSmallTargetCard(GuiGraphics graphics, ArmoredVehicleEntity target, double range) {
        Minecraft minecraft = Minecraft.m_91087_();
        if (minecraft == null || minecraft.f_91074_ == null) {
            return false;
        }

        Vec3 anchor = target.m_20182_().m_82520_(0.0D, target.m_20206_() + 0.55D, 0.0D);
        if (!VectorToolKt.canSee(anchor)) {
            return false;
        }

        Vec3 screen = VectorToolKt.worldToScreen(anchor);
        if (!Double.isFinite(screen.f_82479_) || !Double.isFinite(screen.f_82480_)) {
            return false;
        }

        Font font = minecraft.f_91062_;
        PoseStack poseStack = graphics.m_280168_();
        poseStack.m_85836_();
        try {
            poseStack.m_252880_((float) screen.f_82479_, (float) screen.f_82480_ - 10.0F, 0.0F);
            poseStack.m_85841_(TARGET_CARD_SCALE, TARGET_CARD_SCALE, 1.0F);

            String name = target.m_5446_().getString();
            String distance = String.format(Locale.ROOT, "%.1fM", range);
            int nameColor = 0xFFECECEC;
            int distanceColor = 0xFFDADADA;

            drawCentered(graphics, font, name, -18, nameColor);
            renderHealthBar(graphics, target);
            drawCentered(graphics, font, distance, TARGET_BAR_HEIGHT + 4, distanceColor);
        } finally {
            poseStack.m_85849_();
        }
        return true;
    }

    private static void renderAutocannonHeatHud(GuiGraphics graphics, ArmoredVehicleEntity vehicle, Player player) {
        if (!(vehicle instanceof Mi24VEntity helicopter)
                || helicopterWeaponMode(vehicle, player) != HelicopterWeaponMode.AUTOCANNON) {
            return;
        }
        Minecraft minecraft = Minecraft.m_91087_();
        if (minecraft == null) {
            return;
        }

        double heat = clamp(helicopter.getBvpAutocannonHeatFraction(), 0.0D, 1.0D);
        int overheatTicks = helicopter.getBvpAutocannonOverheatTicks();
        int width = minecraft.m_91268_().m_85445_();
        int height = minecraft.m_91268_().m_85446_();
        int x = width / 2 - AUTOCANNON_HEAT_BAR_WIDTH / 2;
        int y = height - 69;
        int fillWidth = (int) Math.round(AUTOCANNON_HEAT_BAR_WIDTH * heat);
        int heatColor = autocannonHeatColor(heat, overheatTicks > 0);

        graphics.m_280509_(x - 1, y - 1, x + AUTOCANNON_HEAT_BAR_WIDTH + 1, y + AUTOCANNON_HEAT_BAR_HEIGHT + 1, 0xD0000000);
        graphics.m_280509_(x, y, x + AUTOCANNON_HEAT_BAR_WIDTH, y + AUTOCANNON_HEAT_BAR_HEIGHT, 0xA0202020);
        graphics.m_280509_(x, y, x + fillWidth, y + AUTOCANNON_HEAT_BAR_HEIGHT, heatColor);

        Font font = minecraft.f_91062_;
        String label = overheatTicks > 0
                ? String.format(Locale.ROOT, "AUTOCANNON OVERHEAT %.1fs", overheatTicks / 20.0F)
                : String.format(Locale.ROOT, "HEAT LEVEL %d%%", Math.round(heat * 100.0D));
        graphics.m_280056_(font, label, width / 2 - font.m_92895_(label) / 2, y - 11, heatColor, true);
    }

    private static int autocannonHeatColor(double heat, boolean overheated) {
        if (overheated || heat >= 0.90D) {
            return 0xFFFF4040;
        }
        if (heat >= 0.75D) {
            return 0xFFFF8A20;
        }
        if (heat >= 0.50D) {
            return 0xFFFFD34D;
        }
        return 0xFF58E676;
    }

    private static void renderHelicopterPowerGauges(GuiGraphics graphics, ArmoredVehicleEntity vehicle) {
        if (!(vehicle instanceof BvpHelicopterEntity helicopter)) {
            return;
        }
        Minecraft minecraft = Minecraft.m_91087_();
        if (minecraft == null) {
            return;
        }

        Font font = minecraft.f_91062_;
        int width = minecraft.m_91268_().m_85445_();
        int height = minecraft.m_91268_().m_85446_();
        int gaugeTop = height / 2 - 64;
        int collX = width / 2 + 130;
        int thrustX = collX + HELI_GAUGE_SPACING;
        double collective = clamp(helicopter.getBvpCollectiveTarget(), 0.0D, 1.0D);
        double thrustFraction = clamp(helicopter.getBvpThrottleTarget(), 0.0D, 1.0D);

        renderHelicopterGauge(graphics, font, "COLL", collective, collX, gaugeTop);
        renderHelicopterGauge(graphics, font, "THRUST", thrustFraction, thrustX, gaugeTop);
    }

    private static void renderHelicopterGauge(GuiGraphics graphics, Font font, String label,
                                              double fraction, int barX, int top) {
        fraction = clamp(fraction, 0.0D, 1.0D);
        int bottom = top + HELI_GAUGE_HEIGHT;
        int fillHeight = (int) Math.round(HELI_GAUGE_HEIGHT * fraction);
        int fillTop = bottom - fillHeight;

        drawCenteredAt(graphics, font, label, barX + HELI_GAUGE_BAR_WIDTH / 2, top - 12, 0xFFE6F0F2);
        for (int tick = 0; tick <= 10; tick++) {
            int y = bottom - (HELI_GAUGE_HEIGHT * tick / 10);
            int tickWidth = tick % 5 == 0 ? 7 : 4;
            graphics.m_280509_(barX - tickWidth, y, barX - 1, y + 1, HELI_GAUGE_COLOR);
            graphics.m_280509_(barX + HELI_GAUGE_BAR_WIDTH + 1, y,
                    barX + HELI_GAUGE_BAR_WIDTH + tickWidth, y + 1, HELI_GAUGE_COLOR);
        }

        graphics.m_280509_(barX - 1, top - 1, barX + HELI_GAUGE_BAR_WIDTH + 1, bottom + 1, 0xE0000000);
        graphics.m_280509_(barX, top, barX + HELI_GAUGE_BAR_WIDTH, bottom, 0x90202020);
        graphics.m_280509_(barX, fillTop, barX + HELI_GAUGE_BAR_WIDTH, bottom, helicopterInstrumentColor(fraction));
        drawCenteredAt(graphics, font, String.format(Locale.ROOT, "%d%%", Math.round(fraction * 100.0D)),
                barX + HELI_GAUGE_BAR_WIDTH / 2, bottom + 5, 0xFFE6F0F2);
    }

    private static int helicopterInstrumentColor(double fraction) {
        if (fraction >= 0.90D) {
            return 0xFFFFD15C;
        }
        if (fraction >= 0.55D) {
            return 0xFF69E890;
        }
        if (fraction >= 0.20D) {
            return 0xFF5CB6FF;
        }
        return 0xFF8C98A0;
    }

    private static HelicopterWeaponMode helicopterWeaponMode(ArmoredVehicleEntity vehicle, Player player) {
        if (!(vehicle instanceof Mi24VEntity) || player == null) {
            return HelicopterWeaponMode.OTHER;
        }
        int seat = vehicle.getSeatIndex(player);
        int selectedWeapon = vehicle.getSelectedWeapon(seat);
        if (vehicle instanceof Ka50Entity) {
            if (seat == 0 && selectedWeapon == 0) {
                return HelicopterWeaponMode.ROCKET;
            }
            if (seat == 0 && selectedWeapon == 1) {
                return HelicopterWeaponMode.AUTOCANNON;
            }
            if (seat == 0 && selectedWeapon == 2) {
                return HelicopterWeaponMode.ATGM;
            }
            return HelicopterWeaponMode.OTHER;
        }
        if (seat == 1 && selectedWeapon == 0) {
            return HelicopterWeaponMode.AUTOCANNON;
        }
        if ((seat == 1 && selectedWeapon == 1) || (seat == 0 && selectedWeapon == 2)) {
            return HelicopterWeaponMode.ATGM;
        }
        if (seat == 0 && (selectedWeapon == 0 || selectedWeapon == 1)) {
            return HelicopterWeaponMode.ROCKET;
        }
        return HelicopterWeaponMode.OTHER;
    }

    private enum HelicopterWeaponMode {
        AUTOCANNON,
        ATGM,
        ROCKET,
        OTHER
    }

    private static void renderFieldRepairHud(GuiGraphics graphics, ArmoredVehicleEntity vehicle) {
        Minecraft minecraft = Minecraft.m_91087_();
        if (minecraft == null) {
            return;
        }
        // The normalized Info1 panel is for ground vehicles; helicopters use dedicated gauges.
        if (!"@Land".equals(vehicle.computed().getHudType())) {
            return;
        }

        Font font = minecraft.f_91062_;
        int width = minecraft.m_91268_().m_85445_();
        int height = minecraft.m_91268_().m_85446_();

        int left = 12;
        int maxTextWidth = com.atsuishio.superbwarfare.client.overlay.GroundVehicleStatusHud.textWidth(width);
        // Module icons are rendered once by SBW from VehicleModuleHudProvider. Repair state
        // remains server-owned; the HUD only reads its accepted action snapshot.
        VehicleActionSnapshot repair = vehicle.getVehicleActionSnapshot(BvpFieldRepairAction.ACTION_ID);
        int repairMode = BvpFieldRepairAction.mode(repair);
        String repairText = "[" + BvpClientEvents.fieldRepairKeyLabel() + "] Field Repairs";
        boolean repairHeld = repair != null &&
                (repair.getInputHeld() || BvpClientEvents.fieldRepairKeyHeld());
        if (repairHeld && repairMode != BvpFieldRepairAction.MODE_IDLE) {
            int duration = repairMode == BvpFieldRepairAction.MODE_EMERGENCY_HOLD
                    ? BvpFieldRepairAction.EMERGENCY_HOLD_TICKS
                    : BvpFieldRepairAction.SEGMENT_DURATION_TICKS;
            int phaseTicks = Math.max(0, Math.min(repair.getPhaseTicks(), duration));
            float remainingSeconds = Math.max(0.0F, duration - phaseTicks) / 20.0F;
            int progress = Math.round(100.0F * phaseTicks / Math.max(1, duration));
            repairText = String.format(Locale.ROOT, "[%s] %.1fs %d%%",
                    BvpClientEvents.fieldRepairKeyLabel(), remainingSeconds, progress);
        }
        drawBounded(graphics, font, repairText, left, com.atsuishio.superbwarfare.client.overlay.GroundVehicleStatusHud.auxiliaryY(height, 2), maxTextWidth, 0xFFD8E4D8);
    }

    private static void drawBounded(GuiGraphics graphics, Font font, String text, int anchorX, int y,
                                    int maxWidth, int color) {
        int boundedWidth = Math.max(1, maxWidth);
        String shown = font.m_92895_(text) <= boundedWidth
                ? text
                : prefixByWidth(font, text, Math.max(0, boundedWidth - font.m_92895_("…"))) + "…";
        PoseStack pose = graphics.m_280168_();
        pose.m_85836_();
        pose.m_252880_((float) anchorX, (float) y, 0.0F);
        graphics.m_280056_(font, shown, 0, 0, color, true);
        pose.m_85849_();
    }

    private static String prefixByWidth(Font font, String text, int maxWidth) {
        int end = text.length();
        while (end > 0 && font.m_92895_(text.substring(0, end)) > maxWidth) {
            end--;
        }
        return text.substring(0, end);
    }

    private static void drawCentered(GuiGraphics graphics, Font font, String text, int y, int color) {
        graphics.m_280056_(font, text, -font.m_92895_(text) / 2, y, color, true);
    }

    private static void drawCenteredAt(GuiGraphics graphics, Font font, String text, int x, int y, int color) {
        graphics.m_280056_(font, text, x - font.m_92895_(text) / 2, y, color, true);
    }

    private static void renderHealthBar(GuiGraphics graphics, Entity target) {
        if (!(target instanceof VehicleEntity vehicle)) {
            return;
        }

        float maxHealth = Math.max(1.0F, vehicle.getMaxHealth());
        float health = Math.max(0.0F, Math.min(vehicle.getHealth(), maxHealth));
        int halfWidth = TARGET_CARD_WIDTH / 2;
        int fillWidth = Math.round(TARGET_CARD_WIDTH * (health / maxHealth));
        int top = -4;
        int bottom = top + TARGET_BAR_HEIGHT;

        graphics.m_280509_(-halfWidth - 1, top - 1, halfWidth + 1, bottom + 1, 0xE0000000);
        graphics.m_280509_(-halfWidth, top, halfWidth, bottom, 0xD0202020);
        graphics.m_280509_(-halfWidth, top, -halfWidth + fillWidth, bottom, 0xFFE8E8E8);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
