package com.yourname.berts_vehicle_pack.network;

import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.atsuishio.superbwarfare.network.NetworkTelemetry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

public final class BvpNetwork {
    private static final String PROTOCOL_VERSION = "11";
    private static final int ARMOR_EVENT_STATUS_ID = 0;
    private static final int IMPACT_SHRAPNEL_ID = 1;
    private static final double IMPACT_VISIBILITY_RADIUS_BLOCKS = 128.0D;

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
        CHANNEL.registerMessage(IMPACT_SHRAPNEL_ID, ImpactShrapnelPacket.class,
                ImpactShrapnelPacket::encode,
                ImpactShrapnelPacket::decode,
                ImpactShrapnelPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void sendArmorEventStatus(ServerPlayer player, String line1, String line2) {
        sendArmorEventStatus(player, line1, line2, "");
    }

    public static void sendArmorEventStatus(ServerPlayer player, String line1, String line2, String line3) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new ArmorEventStatusPacket(line1, line2, line3, 3500));
    }

    /**
     * Publishes one visual recipe for an accepted impact on the logical server. Vectors are
     * world-space; incoming velocity and surface normal are normalized before encoding.
     * Invalid recipes are dropped without changing the accepted parent impact.
     */
    public static void sendImpactFragments(ServerLevel level, Vec3 position, Vec3 incoming,
                                           Vec3 normal, float caliberMm, int policy) {
        if (level == null) return;
        ImpactShrapnelPacket packet = ImpactShrapnelPacket.tryCreate(
                level.m_46472_().m_135782_(), position, incoming, normal, caliberMm, policy);
        if (packet == null) {
            NetworkTelemetry.INSTANCE.recordSystemWork(
                    "bvp.impact_fragments.rejected", 0, 0, 0, 0, 0L);
            return;
        }
        CHANNEL.send(PacketDistributor.NEAR.with(() -> new PacketDistributor.TargetPoint(
                position.f_82479_, position.f_82480_, position.f_82481_,
                IMPACT_VISIBILITY_RADIUS_BLOCKS, level.m_46472_())), packet);
        NetworkTelemetry.INSTANCE.recordSystemWork(
                "bvp.impact_fragments.sent", 0, 0, 0, 0, 0L);
    }
}
