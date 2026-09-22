package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.client.input.VehicleControlProfile;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.tools.EntityFindUtil;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.UUID;

/** Server-owned fixture tags admit a synchronized identity; clients never trust unsynchronized tags. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID)
public final class BvpVehicleControlProbeCommands {
    private BvpVehicleControlProbeCommands() { }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        if (!BvpFlightClientControl.enabled()) return;
        event.getDispatcher().register(Commands.literal("bvp_control_probe")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("mode", StringArgumentType.word()).executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    String mode = StringArgumentType.getString(context, "mode");
                    if (!privatePlayer(player)) {
                        context.getSource().sendFailure(Component.literal("Private diagnostic operator required"));
                        return 0;
                    }
                    if (mode.equals("stop")) {
                        player.sendSystemMessage(Component.literal("BVP_CONTROL_PROBE stop"));
                        return 1;
                    }
                    VehicleEntity target = target(player, mode);
                    if (target == null || !EliteDiagnostics.isServerEnabled()) {
                        context.getSource().sendFailure(Component.literal("Active capture and exact tagged fixture required"));
                        return 0;
                    }
                    UUID run = UUID.randomUUID();
                    EliteDiagnostics.record(target, "control_probe", "SERVER_ADMISSION",
                            "run", run.toString(), "mode", mode, "operator", player.getUUID().toString());
                    player.sendSystemMessage(Component.literal("BVP_CONTROL_PROBE " + mode + " "
                            + run + " " + target.getUUID() + " " + target.getId()));
                    return 1;
                })));
    }

    private static boolean privatePlayer(ServerPlayer player) {
        var server = player.server;
        return BvpFlightClientControl.enabled() && server.isDedicatedServer() && !server.usesAuthentication()
                && "127.0.0.1".equals(server.getLocalIp()) && server.getPort() == BvpFireTrafficControl.PORT
                && server.getPlayerCount() == 1 && player.level().dimension() == Level.OVERWORLD
                && player.isAlive() && !player.isSpectator() && player.getAbilities().instabuild
                && BvpFireTrafficControl.identity(player.getGameProfile().getName(), player.getUUID())
                && BvpFireTrafficControl.loopback(player.connection.connection.getRemoteAddress(), false);
    }

    private static VehicleEntity target(ServerPlayer player, String mode) {
        if (mode.equals("drone") && player.getVehicle() == null
                && VehicleControlProfile.controlsLinkedDrone(player)) {
            VehicleEntity drone = EntityFindUtil.findDrone(player.level(),
                    player.getMainHandItem().getTag().getString("LinkedDrone"));
            return drone != null && drone.getTags().contains("bvp_refine_drone")
                    && "superbwarfare:drone".equals(String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(drone.getType())))
                    ? drone : null;
        }
        if (mode.equals("pod") && player.getVehicle() instanceof VehicleEntity vehicle
                && vehicle.getTags().contains("bvp_refine_manual") && vehicle.isFixedWingFlightVehicle()
                && !vehicle.isRemoved() && !vehicle.isWreck() && vehicle.getNthEntity(0) == player) return vehicle;
        return null;
    }
}
