package com.yourname.berts_vehicle_pack.network;

import com.atsuishio.superbwarfare.network.NetworkTelemetry;
import com.yourname.berts_vehicle_pack.client.BvpClientImpactFragments;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** A visual-only world-space impact recipe. Each receiving client owns its fragment randomness. */
public record ImpactShrapnelPacket(ResourceLocation dimension, Vec3 position, Vec3 incoming,
                                  Vec3 normal, float caliberMm, int policy) {
    public static final int MAX_DIMENSION_CHARS = 128;
    public static final int MAX_WIRE_BYTES = 183;
    private static final double MAX_POSITION_BLOCKS = 30_000_000.0D;
    private static final float MAX_CALIBER_MM = 2_000.0F;
    private static final double UNIT_TOLERANCE = 0.001D;

    public enum Policy {
        GENERIC_CALIBER(0), KPVT_IAI(1), SMALL_ROCKET(2), LARGE_ROCKET(3), ATGM(4), HE_AUTOCANNON(5), HEAVY_WARHEAD(6);

        public final int wireId;

        Policy(int wireId) {
            this.wireId = wireId;
        }

        public static boolean accepts(int wireId) {
            return wireId >= GENERIC_CALIBER.wireId && wireId <= HEAVY_WARHEAD.wireId;
        }
    }

    public ImpactShrapnelPacket {
        if (!validContext(dimension, position, caliberMm, policy)
                || !unitDirection(incoming) || !unitDirection(normal)) {
            throw new IllegalArgumentException("Invalid impact-fragment recipe");
        }
    }

    /** Returns null for an invalid server recipe; never substitutes a made-up normal or direction. */
    public static ImpactShrapnelPacket tryCreate(ResourceLocation dimension, Vec3 position,
                                                 Vec3 incoming, Vec3 normal, float caliberMm,
                                                 int policy) {
        if (!validContext(dimension, position, caliberMm, policy)) return null;
        Vec3 unitIncoming = normalized(incoming);
        Vec3 unitNormal = normalized(normal);
        if (unitIncoming == null || unitNormal == null) return null;
        return new ImpactShrapnelPacket(dimension, position, unitIncoming, unitNormal,
                caliberMm, policy);
    }

    public static void encode(ImpactShrapnelPacket packet, FriendlyByteBuf buffer) {
        int start = buffer.writerIndex();
        buffer.m_130072_(packet.dimension.toString(), MAX_DIMENSION_CHARS);
        buffer.writeDouble(packet.position.f_82479_);
        buffer.writeDouble(packet.position.f_82480_);
        buffer.writeDouble(packet.position.f_82481_);
        writeDirection(buffer, packet.incoming);
        writeDirection(buffer, packet.normal);
        buffer.writeFloat(packet.caliberMm);
        buffer.writeByte(packet.policy);
        NetworkTelemetry.INSTANCE.recordSystemWork("bvp.impact_fragments.encoded",
                buffer.writerIndex() - start, 0, 0, 0, 0L);
    }

    public static ImpactShrapnelPacket decode(FriendlyByteBuf buffer) {
        int bytes = buffer.readableBytes();
        if (bytes > MAX_WIRE_BYTES) {
            throw new IllegalArgumentException("Impact-fragment recipe exceeds its byte bound");
        }
        ResourceLocation dimension = new ResourceLocation(buffer.m_130136_(MAX_DIMENSION_CHARS));
        Vec3 position = new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        Vec3 incoming = readDirection(buffer);
        Vec3 normal = readDirection(buffer);
        float caliberMm = buffer.readFloat();
        int policy = buffer.readUnsignedByte();
        if (buffer.isReadable()) {
            throw new IllegalArgumentException("Trailing impact-fragment recipe data");
        }
        ImpactShrapnelPacket packet = new ImpactShrapnelPacket(
                dimension, position, incoming, normal, caliberMm, policy);
        NetworkTelemetry.INSTANCE.recordSystemWork(
                "bvp.impact_fragments.decoded", bytes, 0, 0, 0, 0L);
        return packet;
    }

    public static void handle(ImpactShrapnelPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (context.getDirection() == NetworkDirection.PLAY_TO_CLIENT) {
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> BvpClientImpactFragments.accept(packet.dimension, packet.position,
                            packet.incoming, packet.normal, packet.caliberMm, packet.policy)));
        }
        context.setPacketHandled(true);
    }

    private static boolean validContext(ResourceLocation dimension, Vec3 position,
                                         float caliberMm, int policy) {
        return dimension != null && dimension.toString().length() <= MAX_DIMENSION_CHARS
                && finite(position) && Math.abs(position.f_82479_) <= MAX_POSITION_BLOCKS
                && Math.abs(position.f_82480_) <= MAX_POSITION_BLOCKS
                && Math.abs(position.f_82481_) <= MAX_POSITION_BLOCKS
                && Float.isFinite(caliberMm) && caliberMm > 0F && caliberMm <= MAX_CALIBER_MM
                && Policy.accepts(policy);
    }

    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.f_82479_)
                && Double.isFinite(vector.f_82480_) && Double.isFinite(vector.f_82481_);
    }

    private static boolean unitDirection(Vec3 vector) {
        return finite(vector) && Math.abs(vector.m_82556_() - 1.0D) <= UNIT_TOLERANCE;
    }

    private static Vec3 normalized(Vec3 vector) {
        if (!finite(vector)) return null;
        double length = Math.hypot(Math.hypot(vector.f_82479_, vector.f_82480_), vector.f_82481_);
        return !Double.isFinite(length) || length <= 1.0E-8D ? null
                : new Vec3(vector.f_82479_ / length, vector.f_82480_ / length, vector.f_82481_ / length);
    }

    private static void writeDirection(FriendlyByteBuf buffer, Vec3 direction) {
        buffer.writeFloat((float) direction.f_82479_);
        buffer.writeFloat((float) direction.f_82480_);
        buffer.writeFloat((float) direction.f_82481_);
    }

    private static Vec3 readDirection(FriendlyByteBuf buffer) {
        return new Vec3(buffer.readFloat(), buffer.readFloat(), buffer.readFloat());
    }
}
