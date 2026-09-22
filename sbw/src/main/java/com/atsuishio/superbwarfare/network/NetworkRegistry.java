package com.atsuishio.superbwarfare.network;

import com.atsuishio.superbwarfare.Mod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class NetworkRegistry {

    private static final String PROTOCOL_VERSION = "15";
    public static final SimpleChannel PACKET_HANDLER = net.minecraftforge.network.NetworkRegistry.newSimpleChannel(new ResourceLocation(Mod.MODID, Mod.MODID), () -> PROTOCOL_VERSION, PROTOCOL_VERSION::equals, PROTOCOL_VERSION::equals);

    public static void register() {
        NetworkRegistryKt.initializeNetwork();
    }

    public static <T> void playToClient(int messageId, Class<T> messageType, BiConsumer<T, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, T> decoder, BiConsumer<T, Supplier<NetworkEvent.Context>> messageConsumer) {
        PACKET_HANDLER.registerMessage(messageId, messageType, encoder, decoder, messageConsumer, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static <T> void playToServer(int messageId, Class<T> messageType, BiConsumer<T, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, T> decoder, BiConsumer<T, Supplier<NetworkEvent.Context>> messageConsumer) {
        PACKET_HANDLER.registerMessage(messageId, messageType, encoder, decoder, messageConsumer, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }
}
