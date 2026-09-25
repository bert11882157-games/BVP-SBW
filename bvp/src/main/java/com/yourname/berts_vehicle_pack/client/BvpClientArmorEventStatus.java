package com.yourname.berts_vehicle_pack.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class BvpClientArmorEventStatus {
    private static final int BACKGROUND_COLOR = 0x8A050505;
    private static final int PENETRATION_COLOR = 0xFF80F08A;
    private static final int NON_PENETRATION_COLOR = 0xFFFFB05C;
    private static final int MODULE_COLOR = 0xFFFFE07A;
    private static final int ERA_COLOR = 0xFFFF7048;

    private static String line1 = "";
    private static String line2 = "";
    private static String line3 = "";
    private static long expiresAtMillis;

    private BvpClientArmorEventStatus() {
    }

    public static void accept(String newLine1, String newLine2, int ttlMillis) {
        accept(newLine1, newLine2, "", ttlMillis);
    }

    public static void accept(String newLine1, String newLine2, String newLine3, int ttlMillis) {
        line1 = sanitize(newLine1);
        line2 = sanitize(newLine2);
        line3 = sanitize(newLine3);
        expiresAtMillis = System.currentTimeMillis() + Math.max(1000, ttlMillis);
    }

    public static void render(GuiGraphics graphics) {
        if (System.currentTimeMillis() > expiresAtMillis || (line1.isEmpty() && line2.isEmpty() && line3.isEmpty())) {
            return;
        }

        Minecraft minecraft = Minecraft.m_91087_();
        if (minecraft == null) {
            return;
        }

        Font font = minecraft.f_91062_;
        int screenWidth = minecraft.m_91268_().m_85445_();
        int screenHeight = minecraft.m_91268_().m_85446_();
        int x = Math.max(104, screenWidth / 5);
        int y = Math.max(34, screenHeight - 82);
        int maxWidth = Math.max(160, screenWidth - x - 10);
        float scale = fitScale(font, maxWidth);

        PoseStack poseStack = graphics.m_280168_();
        poseStack.m_85836_();
        try {
            poseStack.m_252880_(x, y, 0.0F);
            poseStack.m_85841_(scale, scale, 1.0F);
            int lineWidth = Math.max(Math.max(font.m_92895_(line1), font.m_92895_(line2)), font.m_92895_(line3));
            int backgroundHeight = line3.isEmpty() ? 22 : 33;
            graphics.m_280509_(-5, -4, lineWidth + 5, backgroundHeight, BACKGROUND_COLOR);
            graphics.m_280056_(font, line1, 0, 0, line1Color(), true);
            graphics.m_280056_(font, line2, 0, 11, MODULE_COLOR, true);
            if (!line3.isEmpty()) {
                graphics.m_280056_(font, line3, 0, 22, ERA_COLOR, true);
            }
        } finally {
            poseStack.m_85849_();
        }
    }

    private static float fitScale(Font font, int maxWidth) {
        int lineWidth = Math.max(Math.max(font.m_92895_(line1), font.m_92895_(line2)), font.m_92895_(line3));
        if (lineWidth <= maxWidth) {
            return 1.0F;
        }
        return Math.max(0.72F, maxWidth / (float) Math.max(1, lineWidth));
    }

    private static int line1Color() {
        if (line1.startsWith("Track hit!") || line1.startsWith("Module hit!")) {
            return MODULE_COLOR;
        }
        return line1.startsWith("Non-penetration") || line1.regionMatches(true, 0, "Shot missed", 0, 11)
                ? NON_PENETRATION_COLOR
                : PENETRATION_COLOR;
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
