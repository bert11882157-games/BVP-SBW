package com.yourname.berts_vehicle_pack.client;

import com.atsuishio.superbwarfare.api.performance.ClientRenderPerformanceDiagnostics;
import com.atsuishio.superbwarfare.client.VehicleActionInputClient;
import com.atsuishio.superbwarfare.client.sound.VehicleLoopSoundProviderRegistry;
import com.atsuishio.superbwarfare.data.vehicle.DefaultVehicleData;
import com.atsuishio.superbwarfare.data.vehicle.VehicleData;
import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.yourname.berts_vehicle_pack.client.renderer.ArmorDebugRenderer;
import com.yourname.berts_vehicle_pack.client.renderer.BvpMuzzleDebugRenderer;
import com.yourname.berts_vehicle_pack.client.renderer.BvpMuzzleFlashRenderer;
import com.yourname.berts_vehicle_pack.client.renderer.BvpProjectileTrailRenderer;
import com.yourname.berts_vehicle_pack.client.renderer.BvpTracerRenderer;
import com.yourname.berts_vehicle_pack.client.renderer.BaseVehicleRenderer;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import com.yourname.berts_vehicle_pack.entity.armored.damage.BvpFieldRepairAction;
import com.yourname.berts_vehicle_pack.item.BvpVehicleItem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;

import java.util.Map;
import java.util.List;

public final class BvpClientEvents {
    public static final BvpClientEvents INSTANCE = new BvpClientEvents();
    private static final KeyMapping FIELD_REPAIR_KEY = new KeyMapping(
            "key.berts_vehicle_pack.field_repair",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F,
            "key.categories.berts_vehicle_pack");
    private static final Map<String, String> SPECIAL_AMMO_TOOLTIPS = Map.of(
            "berts_vehicle_pack:t72b", "weapon.berts_vehicle_pack.3bm42_apfsds",
            "berts_vehicle_pack:t64b_obr1976", "weapon.berts_vehicle_pack.3bm42_apfsds",
            "berts_vehicle_pack:t80b_obr1976", "weapon.berts_vehicle_pack.3bm42_apfsds",
            "berts_vehicle_pack:t80bv_obr1985", "weapon.berts_vehicle_pack.3bm42_apfsds",
            "berts_vehicle_pack:t90a", "weapon.berts_vehicle_pack.3bm60_apfsds"
    );

    private BvpClientEvents() {
        BvpHudRenderer.registerTargetCardRenderer();
        BvpProjectileTrailRenderer.registerProviders();
        BvpTankEngineSounds.registerProvider();
    }

    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(FIELD_REPAIR_KEY);
    }

    /** The live translated key label is consumed by the text-only ground HUD. */
    public static String fieldRepairKeyLabel() {
        return FIELD_REPAIR_KEY.m_90863_().getString();
    }

    /**
     * Presentation-only view of the local repair key.  The server action snapshot remains the
     * authority for mode/phase; this closes the small client-input/snapshot ordering gap so the
     * countdown is visible on the same render that the player is holding the bound key.
     */
    public static boolean fieldRepairKeyHeld() {
        Minecraft minecraft = Minecraft.m_91087_();
        return minecraft != null && minecraft.f_91080_ == null && FIELD_REPAIR_KEY.m_90857_();
    }

    public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener)
                resourceManager -> {
                    BvpTracerRenderer.onResourceReload();
                    BaseVehicleRenderer.onResourceReload();
                });
    }

    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().f_46443_) {
            BvpTracerRenderer.onEntityAdded(event.getEntity());
        }
    }

    @SubscribeEvent
    public void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getLevel().f_46443_) {
            BvpTracerRenderer.onEntityRemoved(event.getEntity());
            if (event.getEntity() instanceof GeoVehicleEntity vehicle) {
                BaseVehicleRenderer.onEntityRemoved(vehicle);
            }
        }
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel level) {
            BvpTracerRenderer.onLevelUnload(level);
            BaseVehicleRenderer.onLevelUnload();
        }
    }

    @SubscribeEvent
    public void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(muzzleDebugCommand());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.m_82127_("bvp_armor_xray")
                .executes(context -> {
                    boolean enabled = ArmorDebugRenderer.toggleCommandXray();
                    sendLocalStatus("BVP armor xray: " + (enabled ? "ON" : "OFF"));
                    return 1;
                })
                .then(Commands.m_82127_("on").executes(context -> {
                    ArmorDebugRenderer.setCommandXrayEnabled(true);
                    sendLocalStatus("BVP armor xray: ON");
                    return 1;
                }))
                .then(Commands.m_82127_("off").executes(context -> {
                    ArmorDebugRenderer.setCommandXrayEnabled(false);
                    sendLocalStatus("BVP armor xray: OFF");
                    return 1;
                })));

        event.getDispatcher().register(Commands.m_82127_("bvp").executes(context -> bvpStatus()));
    }

    @SubscribeEvent
    public void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (!(stack.m_41720_() instanceof BvpVehicleItem)) {
            return;
        }

        ResourceLocation entityTypeLocation = BvpVehicleItem.getEntityTypeId(stack).orElse(null);
        if (entityTypeLocation == null) {
            return;
        }

        String entityTypeId = entityTypeLocation.toString();
        String ammoKey = SPECIAL_AMMO_TOOLTIPS.get(entityTypeId);
        if (ammoKey != null) {
            event.getToolTip().add(Component.m_237110_(
                    "tooltip.berts_vehicle_pack.uses_special_ammo",
                    Component.m_237115_(ammoKey)
            ).m_130940_(ChatFormatting.GRAY));
        }

        EntityType<?> entityType = ForgeRegistries.ENTITY_TYPES.getValue(entityTypeLocation);
        if (entityType == null) {
            return;
        }
        DefaultVehicleData vehicleData = VehicleData.getDefault(entityType);
        addTechnologyTooltip(event.getToolTip(), vehicleData);
    }

    private static void addTechnologyTooltip(java.util.List<Component> tooltip, DefaultVehicleData data) {
        if (data.getHasCCIP()) {
            tooltip.add(Component.m_237115_("tooltip.berts_vehicle_pack.has_ccip")
                    .m_130940_(ChatFormatting.GRAY));
        }
        if (data.getHasFCS()) {
            tooltip.add(Component.m_237115_("tooltip.berts_vehicle_pack.has_fcs")
                    .m_130940_(ChatFormatting.GRAY));
        }
        if (data.getRemoteWeaponStation()) {
            tooltip.add(Component.m_237115_("tooltip.berts_vehicle_pack.has_rws")
                    .m_130940_(ChatFormatting.GRAY));
        }
        if (data.getHasERA()) {
            tooltip.add(Component.m_237115_("tooltip.berts_vehicle_pack.has_era")
                    .m_130940_(ChatFormatting.GRAY));
        }
        if (data.getHasNVD()) {
            tooltip.add(Component.m_237113_("NVD").m_130940_(ChatFormatting.GRAY));
        }
        if (data.getHasTVD()) {
            tooltip.add(Component.m_237113_("TVD").m_130940_(ChatFormatting.GRAY));
        }
        if (data.getHasAutoloader()) {
            tooltip.add(Component.m_237113_("Autoloader").m_130940_(ChatFormatting.GRAY));
        }
        List<String> fires = data.getFires();
        if (fires != null && !fires.isEmpty()) {
            tooltip.add(Component.m_237110_(
                    "tooltip.berts_vehicle_pack.fires",
                    Component.m_237113_(String.join(", ", fires))
            ).m_130940_(ChatFormatting.GRAY));
        }
    }

    @SubscribeEvent
    public void onRenderGuiPost(RenderGuiEvent.Post event) {
        BvpHudRenderer.renderPost(event);
        BvpMuzzleDebugRenderer.renderHud(event);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.m_91087_();
        BvpMuzzleFlashRenderer.tick(minecraft);
        if (minecraft == null) {
            return;
        }
        BaseVehicleRenderer.prewarmOne(minecraft);
        // Custom BVP loop cleanup is owned by the provider registry; native SBW loops retain
        // their separate lifecycle in ClientEventHandler.  This avoids two competing BVP owners.
        VehicleLoopSoundProviderRegistry.tick(minecraft);
        syncFieldRepairInput(minecraft);
        BvpProjectileTrailRenderer.tick(minecraft);
        BvpTracerRenderer.tick(minecraft);
        if (minecraft.f_91074_ == null) {
            return;
        }
        BvpDynamicProjectileLights.ensureRegistered();
    }


    private static void syncFieldRepairInput(Minecraft minecraft) {
        Player player = minecraft.f_91074_;
        Entity ridden = player == null ? null : player.m_20202_();
        if (!(ridden instanceof ArmoredVehicleEntity vehicle)) {
            VehicleActionInputClient.clear();
            return;
        }

        boolean pressed = minecraft.f_91080_ == null && FIELD_REPAIR_KEY.m_90857_();
        VehicleActionInputClient.sync(vehicle, BvpFieldRepairAction.ACTION_ID, pressed);
    }

    @SubscribeEvent
    public void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES
                && ClientRenderPerformanceDiagnostics.isEnabled()) {
            Minecraft minecraft = Minecraft.m_91087_();
            long nowNanos = System.nanoTime();
            boolean valid = minecraft != null && minecraft.f_91073_ != null && minecraft.f_91074_ != null
                    && !minecraft.m_91104_() && minecraft.m_91302_();
            ClientRenderPerformanceDiagnostics.recordFrameBoundary(nowNanos, valid);
        }
        BvpFiredVisualClient.render(event);
        BvpMuzzleFlashRenderer.render(event);
        BvpProjectileTrailRenderer.render(event);
        BvpTracerRenderer.render(event);
    }

    private static void sendLocalStatus(String message) {
        sendLocalComponent(Component.m_237113_(message));
    }

    private static void sendLocalComponent(Component component) {
        Minecraft minecraft = Minecraft.m_91087_();
        if (minecraft != null && minecraft.f_91074_ != null) {
            minecraft.f_91074_.m_5661_(component, false);
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> muzzleDebugCommand() {
        return Commands.m_82127_("bvp_muzzle_debug")
                .executes(context -> setMuzzleDebugEnabled(BvpMuzzleDebugRenderer.toggle()))
                .then(Commands.m_82127_("on").executes(context -> setMuzzleDebugEnabled(true)))
                .then(Commands.m_82127_("off").executes(context -> setMuzzleDebugEnabled(false)))
                .then(Commands.m_82127_("status").executes(context -> reportMuzzleDebugStatus()));
    }

    private static int setMuzzleDebugEnabled(boolean enabled) {
        BvpMuzzleDebugRenderer.setEnabled(enabled);
        return reportMuzzleDebugStatus();
    }

    private static int reportMuzzleDebugStatus() {
        boolean enabled = BvpMuzzleDebugRenderer.isEnabled();
        sendLocalStatus("BVP muzzle debug: " + (enabled ? "ON" : "OFF")
                + (enabled ? " (yellow FIRE@1 muzzle, green direction, red 8-block endpoint)" : ""));
        return 1;
    }

    private static int bvpStatus() {
        sendLocalStatus("BVP: client hooks active. Use /sbw elite diagnostics on|off|status for diagnostics.");
        return 1;
    }

}
