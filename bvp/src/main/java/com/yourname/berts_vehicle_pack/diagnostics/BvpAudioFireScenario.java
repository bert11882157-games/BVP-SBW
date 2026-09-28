package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Diagnostic launches only ({@code bvp.diagnostics.scenarios}): {@code /bvp_audio_fire <vehicles> <weapon>} fires one
 * round of the named weapon from each selected vehicle with no crew, through the normal vehicle shot path, so its
 * fire cue goes out exactly as in play. Used by the audio loudness test to fire guns at set listener distances.
 * {@code /bvp_seat <player> <seat>} moves a rider to another seat (seat views in the spawn checks).
 * {@code /bvp_ammo <vehicles> <weapon> <rounds>} sets a weapon's loaded rounds (ammo-bone visibility checks).
 */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID)
public final class BvpAudioFireScenario {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private BvpAudioFireScenario() {
    }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) return;
        event.getDispatcher().register(Commands.literal("bvp_audio_fire")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("vehicles", EntityArgument.entities())
                        .then(Commands.argument("weapon", StringArgumentType.word()).executes(context -> {
                            String weapon = StringArgumentType.getString(context, "weapon");
                            int fired = 0;
                            for (Entity entity : EntityArgument.getEntities(context, "vehicles")) {
                                if (!(entity instanceof VehicleEntity vehicle)) continue;
                                vehicle.modifyGunData(weapon, data -> {
                                    data.resetStatus();
                                    data.ammo.set(Math.max(1, data.ammo.get()));
                                });
                                var result = vehicle.vehicleShootResult(null, weapon);
                                if (result.isAccepted()) fired++;
                                LOGGER.info("[BVP audio] fire {} {}: {}",
                                        entity.getType(), weapon, result);
                            }
                            int count = fired;
                            context.getSource().sendSuccess(() -> Component.literal("audio fire: " + count), false);
                            return count;
                        }))));
        // /bvp_ammo <vehicles> <weapon> <rounds>: sets a weapon's loaded rounds (ammo-bone visibility checks)
        event.getDispatcher().register(Commands.literal("bvp_ammo")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("vehicles", EntityArgument.entities())
                        .then(Commands.argument("weapon", StringArgumentType.word())
                                .then(Commands.argument("rounds", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 10000))
                                        .executes(context -> {
                                            String weapon = StringArgumentType.getString(context, "weapon");
                                            int rounds = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "rounds");
                                            int set = 0;
                                            for (Entity entity : EntityArgument.getEntities(context, "vehicles")) {
                                                if (!(entity instanceof VehicleEntity vehicle) || vehicle.getGunData(weapon) == null) continue;
                                                vehicle.modifyGunData(weapon, data -> {
                                                    data.resetStatus();
                                                    data.ammo.set(rounds);
                                                });
                                                set++;
                                                LOGGER.info("[BVP ammo] {} {} = {}", entity.getType(), weapon, rounds);
                                            }
                                            return set;
                                        })))));
        // /bvp_seat <player> <seat>: moves a rider to another seat of the vehicle it rides (seat views in checks)
        event.getDispatcher().register(Commands.literal("bvp_seat")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("seat", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 31))
                                .executes(context -> {
                                    Entity rider = EntityArgument.getPlayer(context, "player");
                                    int seat = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "seat");
                                    if (!(rider.getVehicle() instanceof VehicleEntity vehicle)) return 0;
                                    boolean moved = vehicle.changeSeat(rider, seat);
                                    LOGGER.info("[BVP seat] {} -> seat {}: {}", vehicle.getType(), seat, moved);
                                    return moved ? 1 : 0;
                                }))));
    }
}
