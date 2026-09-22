package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.event.ClientEventHandler;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/** Drives only the existing held-fire variable in a deliberately opted-in private client. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID, value = Dist.CLIENT)
public final class BvpFireTrafficClient {
    private static Active active;
    private BvpFireTrafficClient() { }

    private record Active(BvpFireTrafficControl.Session session, ClientPacketListener connection,
                          ClientLevel level, LocalPlayer player, VehicleEntity vehicle,
                          int seat, int weapon) { }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void message(ClientChatReceivedEvent event) {
        if (!BvpFireTrafficControl.enabled() || !event.isSystem()) return;
        BvpFireTrafficControl.Marker marker = BvpFireTrafficControl.parse(event.getMessage().getString());
        if (marker == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!privateClient(minecraft)) return;
        try {
            if (active == null) {
                if (marker.phase() != BvpFireTrafficControl.Phase.ARM
                        || !(minecraft.player.getVehicle() instanceof VehicleEntity vehicle)
                        || vehicle.getId() != marker.entityId() || !vehicle.getUUID().equals(marker.vehicle())
                        || !new ResourceLocation(BertsVehiclePack.MODID, "bmpt").equals(
                                ForgeRegistries.ENTITY_TYPES.getKey(vehicle.getType()))) return;
                int seat = vehicle.getSeatIndex(minecraft.player);
                if (seat < 0 || vehicle.getNthEntity(seat) != minecraft.player
                        || !"DualCannon".equals(vehicle.getGunName(seat))) return;
                active = new Active(new BvpFireTrafficControl.Session(marker, System.nanoTime()),
                        minecraft.getConnection(), minecraft.level, minecraft.player, vehicle,
                        seat, vehicle.getSelectedWeapon(seat));
                ClientEventHandler.holdFireVehicle = false;
                acknowledge("ARM");
            } else if (active.session.sameRun(marker)
                    && marker.phase() == BvpFireTrafficControl.Phase.STOP) {
                stop(false, "SERVER_STOP");
            } else if (valid(minecraft) && active.session.advance(marker, System.nanoTime())) {
                ClientEventHandler.holdFireVehicle = active.session.held(System.nanoTime());
                EliteDiagnostics.recordClient(minecraft.level.getGameTime(), "fire_traffic", "CLIENT_PHASE",
                        "run", marker.run().toString(), "phase", marker.phase().name());
                acknowledge(marker.phase().name());
            }
            event.setCanceled(true);
        } catch (RuntimeException failure) {
            stop(true, "CLIENT_ERROR");
        }
    }

    /** Runs before SBW's ordinary RenderTick fire handler; never constructs a fire packet. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void render(TickEvent.RenderTickEvent event) {
        if (active == null) return;
        try {
            if (!valid(Minecraft.getInstance())) { stop(true, "CONTEXT_LOST"); return; }
            ClientEventHandler.holdFireVehicle = active.session.held(System.nanoTime());
        } catch (RuntimeException failure) {
            stop(true, "CLIENT_ERROR");
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (active != null && event.phase == TickEvent.Phase.END && !valid(Minecraft.getInstance())) {
            stop(true, "CONTEXT_LOST");
        }
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (active != null && event.getLevel() == active.level) stop(false, "UNLOAD");
    }

    private static boolean privateClient(Minecraft minecraft) {
        return BvpFireTrafficControl.enabled() && minecraft.player != null && minecraft.level != null
                && minecraft.getConnection() != null
                && BvpFireTrafficControl.identity(minecraft.player.getGameProfile().getName(), minecraft.player.getUUID())
                && "0".equals(minecraft.getUser().getAccessToken())
                && BvpFireTrafficControl.loopback(minecraft.getConnection().getConnection().getRemoteAddress(), true);
    }

    private static boolean valid(Minecraft minecraft) {
        Active state = active;
        return state != null && privateClient(minecraft)
                && minecraft.getConnection() == state.connection && minecraft.level == state.level
                && minecraft.player == state.player && minecraft.screen == null && minecraft.isWindowActive()
                && state.player.isAlive() && !state.player.isSpectator() && !state.vehicle.isRemoved()
                && state.player.getVehicle() == state.vehicle && state.vehicle.getNthEntity(state.seat) == state.player
                && state.vehicle.getSeatIndex(state.player) == state.seat
                && state.vehicle.getSelectedWeapon(state.seat) == state.weapon
                && !state.session.expired(System.nanoTime()) && EliteDiagnostics.isClientEnabled();
    }

    private static void acknowledge(String phase) {
        Active state = active;
        if (state != null) state.connection.sendCommand("bvp_fire_traffic_ack " + state.session.identity.run() + " " + phase);
    }

    private static void stop(boolean notifyServer, String reason) {
        Active state = active;
        if (state == null) return;
        active = null;
        ClientEventHandler.holdFireVehicle = false;
        EliteDiagnostics.recordClient(state.level.getGameTime(), "fire_traffic", "CLIENT_STOP",
                "run", state.session.identity.run().toString(), "reason", reason);
        if (notifyServer && Minecraft.getInstance().getConnection() == state.connection
                && BvpFireTrafficControl.loopback(state.connection.getConnection().getRemoteAddress(), true)) {
            state.connection.sendCommand("bvp_fire_traffic_ack " + state.session.identity.run() + " ABORT");
        }
    }
}
