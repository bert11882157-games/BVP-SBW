package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.client.overlay.weapon.LandVehicleHud;
import com.atsuishio.superbwarfare.client.overlay.GroundHudLayout;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.util.Mth;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep server-authored action-bar warnings clear of ground weapon assignments and ammo. */
@Mixin(value = ForgeGui.class, remap = false)
public abstract class GroundVehicleActionBarMixin extends Gui {
    protected GroundVehicleActionBarMixin(Minecraft minecraft, ItemRenderer renderer) {
        super(minecraft, renderer);
    }

    @Inject(method = "renderRecordOverlay", at = @At("HEAD"), cancellable = true, remap = false)
    private void groundVehicleAlert(int width, int height, float partialTick, GuiGraphics graphics, CallbackInfo ci) {
        var player = minecraft.player;
        if (player == null || !(player.getVehicle() instanceof VehicleEntity vehicle)
                || !LandVehicleHud.ID.equals(vehicle.computed().getHudType()) || !vehicle.banHand(player)) return;
        ci.cancel();
        if (overlayMessageString == null || overlayMessageTime <= 0) return;
        float remaining = overlayMessageTime - partialTick;
        int alpha = Math.min(255, (int)(remaining * 255F / 20F));
        if (alpha <= 8) return;
        int color = animateOverlayMessageColor ? Mth.hsvToRgb(remaining * 50F / 255F, .7F, .6F) : 0xFFFFFF;
        var lines = minecraft.font.split(overlayMessageString, Math.max(1,width-24));
        int y = 52;
        int left = GroundHudLayout.alertLeft(height, vehicle.getOrderedPassengers().size(),
                y-2, y+lines.size()*(minecraft.font.lineHeight+2), minecraft.font.lineHeight);
        if (left > 12) lines = minecraft.font.split(overlayMessageString, Math.max(1,width-left-12));
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        for (var line : lines) {
            int length = minecraft.font.width(line);
            int x = left + (width-left-12-length)/2;
            graphics.fill(x-3,y-2,x+length+3,y+minecraft.font.lineHeight+1, (alpha*96/255)<<24);
            graphics.drawString(minecraft.font,line,x,y,(alpha<<24)|color,true);
            y += minecraft.font.lineHeight+2;
        }
        RenderSystem.disableBlend();
    }
}
