package com.yourname.berts_vehicle_pack.network;

import com.yourname.berts_vehicle_pack.client.BvpClientArmorEventStatus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ArmorEventStatusPacket(String line1, String line2, String line3, int ttlMillis) {
    private static final int MAX_LINE_LENGTH = 220;
    private static final int MIN_TTL_MILLIS = 1000;
    private static final int MAX_TTL_MILLIS = 10_000;

    public static void encode(ArmorEventStatusPacket packet, FriendlyByteBuf buffer) {
        buffer.m_130072_(safe(packet.line1), MAX_LINE_LENGTH);
        buffer.m_130072_(safe(packet.line2), MAX_LINE_LENGTH);
        buffer.m_130072_(safe(packet.line3), MAX_LINE_LENGTH);
        buffer.m_130130_(Math.max(MIN_TTL_MILLIS, Math.min(MAX_TTL_MILLIS, packet.ttlMillis)));
    }

    public static ArmorEventStatusPacket decode(FriendlyByteBuf buffer) {
        String line1 = buffer.m_130136_(MAX_LINE_LENGTH);
        String line2 = buffer.m_130136_(MAX_LINE_LENGTH);
        String line3 = buffer.m_130136_(MAX_LINE_LENGTH);
        int ttlMillis = Math.max(MIN_TTL_MILLIS, Math.min(MAX_TTL_MILLIS, buffer.m_130242_()));
        return new ArmorEventStatusPacket(line1, line2, line3, ttlMillis);
    }

    public static void handle(ArmorEventStatusPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> BvpClientArmorEventStatus.accept(packet.line1, packet.line2, packet.line3, packet.ttlMillis)));
        context.setPacketHandled(true);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
