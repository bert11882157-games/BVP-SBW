package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Private diagnostic control markers, carried by ordinary system messages, not a mod packet. */
public final class BvpFireTrafficControl {
    public static final String PLAYER_NAME = "BvpDiagnostics";
    public static final UUID PLAYER_UUID = UUID.nameUUIDFromBytes(
            ("OfflinePlayer:" + PLAYER_NAME).getBytes(StandardCharsets.UTF_8));
    public static final int PORT = 25579;
    public static final String PREFIX = "[BVP_FIRE_TRAFFIC] ";
    public static final long MAX_SESSION_NANOS = 45_000_000_000L;
    public static final long MAX_HOLD_NANOS = 12_000_000_000L;

    public enum Phase {
        ARM(0), IDLE(100), HOLD(200), RELEASE(100), STOP(0);
        public final int ticks;
        Phase(int ticks) { this.ticks = ticks; }
    }

    public record Marker(UUID run, UUID vehicle, int entityId, Phase phase) { }

    private BvpFireTrafficControl() { }

    public static boolean enabled() {
        return DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")
                && Boolean.getBoolean("bvp.diagnostics.fireTraffic");
    }

    public static boolean identity(String name, UUID uuid) {
        return PLAYER_NAME.equals(name) && PLAYER_UUID.equals(uuid);
    }

    public static boolean loopback(SocketAddress address, boolean serverEndpoint) {
        return address instanceof InetSocketAddress inet && inet.getAddress() != null
                && inet.getAddress().isLoopbackAddress()
                && (!serverEndpoint || inet.getPort() == PORT);
    }

    public static String message(UUID run, UUID vehicle, int entityId, Phase phase) {
        return PREFIX + run + " " + vehicle + " " + entityId + " " + phase;
    }

    public static Marker parse(String message) {
        if (message == null || message.length() > 160 || !message.startsWith(PREFIX)) return null;
        String[] fields = message.substring(PREFIX.length()).split(" ", -1);
        if (fields.length != 4) return null;
        try {
            int entityId = Integer.parseInt(fields[2]);
            if (entityId <= 0) return null;
            return new Marker(UUID.fromString(fields[0]), UUID.fromString(fields[1]),
                    entityId, Phase.valueOf(fields[3]));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** Ordered transitions do not let duplicated/replayed HOLD markers extend the watchdog. */
    public static final class Session {
        public final Marker identity;
        private final long startedNanos;
        private long holdStartedNanos;
        private Phase phase = Phase.ARM;

        public Session(Marker marker, long nowNanos) {
            if (marker.phase() != Phase.ARM) throw new IllegalArgumentException("ARM required");
            identity = marker;
            startedNanos = nowNanos;
        }

        public boolean advance(Marker marker, long nowNanos) {
            if (!sameRun(marker) || expired(nowNanos)) return false;
            if (marker.phase() != Phase.STOP && marker.phase().ordinal() != phase.ordinal() + 1) return false;
            if (phase == Phase.STOP) return false;
            phase = marker.phase();
            if (phase == Phase.HOLD) holdStartedNanos = nowNanos;
            return true;
        }

        public boolean sameRun(Marker marker) {
            return identity.run().equals(marker.run()) && identity.vehicle().equals(marker.vehicle())
                    && identity.entityId() == marker.entityId();
        }

        public boolean expired(long nowNanos) {
            return nowNanos < startedNanos || nowNanos - startedNanos >= MAX_SESSION_NANOS
                    || phase == Phase.HOLD && nowNanos - holdStartedNanos >= MAX_HOLD_NANOS;
        }

        public boolean held(long nowNanos) { return phase == Phase.HOLD && !expired(nowNanos); }
        public Phase phase() { return phase; }
    }
}
