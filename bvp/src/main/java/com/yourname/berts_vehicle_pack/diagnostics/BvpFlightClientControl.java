package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;

import java.util.UUID;

/** Run-scoped ordinary system-message coordination, with no custom wire registration. */
public final class BvpFlightClientControl {
    public static final String PREFIX = "[BVP_FLIGHT_INPUT] ";
    public static final long MAX_NANOS = 270_000_000_000L;
    public static final long ARM_NANOS = 10_000_000_000L;
    public static final String RECENTER_MAPPING = "key.superbwarfare.flight_recenter";
    public static final int ACK_DELAY_TICKS = 1;
    public static final String REPORT_SCHEMA = "bvp-real-client-world-aim-v2";
    private BvpFlightClientControl() { }

    public static boolean enabled() {
        return DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios") && Boolean.getBoolean("bvp.diagnostics.flight");
    }

    public record Marker(UUID run, UUID vehicle, int entityId, String label, String digest, String phase,
                         long minimumServerTick) { }

    public static String message(UUID run, UUID vehicle, int entityId, String label, String digest, String phase) {
        return message(run, vehicle, entityId, label, digest, phase, 0L);
    }

    public static String message(UUID run, UUID vehicle, int entityId, String label, String digest,
                                 String phase, long minimumServerTick) {
        String text = PREFIX + run + " " + vehicle + " " + entityId + " " + label + " " + digest + " " + phase
                + " " + minimumServerTick;
        if (parse(text) == null) throw new IllegalArgumentException("Invalid flight marker");
        return text;
    }

    public static Marker parse(String text) {
        if (text == null || text.length() > 288 || !text.startsWith(PREFIX)) return null;
        String[] values = text.substring(PREFIX.length()).split(" ", -1);
        if ((values.length != 6 && values.length != 7) || !values[3].matches("[A-Za-z0-9_-]{1,40}")
                || !values[4].matches("[0-9a-f]{64}") || !values[5].matches("ARM|STOP|S(?:[0-9]|[12][0-9]|3[01])")) return null;
        try {
            int id = Integer.parseInt(values[2]);
            long minimumTick = values.length == 7 ? Long.parseLong(values[6]) : 0L;
            return id <= 0 || minimumTick < 0 ? null : new Marker(UUID.fromString(values[0]), UUID.fromString(values[1]),
                    id, values[3], values[4], values[5], minimumTick);
        } catch (IllegalArgumentException invalid) { return null; }
    }

    public static final class Session {
        public final Marker identity;
        public final BvpFlightClientPlan plan;
        private final long started;
        private long stageStarted;
        private int stage = -1;
        private int stageTick;
        private int totalTicks;
        private boolean stopped;

        public Session(Marker marker, BvpFlightClientPlan plan, long now) {
            if (!marker.phase().equals("ARM")) throw new IllegalArgumentException("ARM required");
            plan.validate(); identity = marker; this.plan = plan; started = now; stageStarted = now;
        }
        public boolean matches(Marker marker) {
            return identity.run().equals(marker.run()) && identity.vehicle().equals(marker.vehicle())
                    && identity.entityId() == marker.entityId() && identity.label().equals(marker.label())
                    && identity.digest().equals(marker.digest());
        }
        public boolean advance(Marker marker, long now) {
            if (!matches(marker) || expired(now)) return false;
            if (marker.phase().equals("STOP")) { stopped = true; return true; }
            if (!marker.phase().startsWith("S")) return false;
            int next = Integer.parseInt(marker.phase().substring(1));
            if (next != stage + 1 || next >= plan.stages.size()) return false;
            stage = next; stageTick = 0; stageStarted = now;
            return true;
        }
        public boolean expired(long now) {
            long stageLimit = stage < 0 ? ARM_NANOS : plan.stages.get(stage).ticks * 50_000_000L + ARM_NANOS;
            return stopped || now < started || now < stageStarted || now - started >= MAX_NANOS
                    || now - stageStarted >= stageLimit || totalTicks >= 5_000;
        }
        public BvpFlightClientPlan.Stage stage() { return stage < 0 ? null : plan.stages.get(stage); }
        public int stageIndex() { return stage; }
        public int stageTick() { return stageTick; }
        public int totalTicks() { return totalTicks; }
        public void tick() { if (stage >= 0) stageTick++; totalTicks++; }
    }
}
