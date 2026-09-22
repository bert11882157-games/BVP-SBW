package com.yourname.berts_vehicle_pack.network;

import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

public final class BvpNetwork {
    private static final String PROTOCOL_VERSION = "8";
    private static final int ARMOR_EVENT_STATUS_ID = 0;

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(BertsVehiclePack.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private BvpNetwork() {
    }

    public static void register() {
        CHANNEL.registerMessage(ARMOR_EVENT_STATUS_ID, ArmorEventStatusPacket.class,
                ArmorEventStatusPacket::encode,
                ArmorEventStatusPacket::decode,
                ArmorEventStatusPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void sendArmorEventStatus(ServerPlayer player, String line1, String line2) {
        sendArmorEventStatus(player, line1, line2, "");
    }

    public static void sendArmorEventStatus(ServerPlayer player, String line1, String line2, String line3) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new ArmorEventStatusPacket(line1, line2, line3, 3500));
    }


}
