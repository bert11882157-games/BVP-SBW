package com.yourname.berts_vehicle_pack.effects;

import net.minecraft.world.phys.Vec3;

/** Interpolates real tick endpoints without changing a projectile's world state. */
public record TracerInterpolation(Vec3 start, Vec3 first, Vec3 second, Vec3 end) {
    public static final int MAX_SAMPLE_HZ = 165;
    public static final double MUZZLE_CLEARANCE_BLOCKS = 0.02D;
    public static final double MAX_INITIAL_LENGTH_BLOCKS = 0.25D;

    public static TracerInterpolation between(Vec3 start, Vec3 end) {
        return new TracerInterpolation(start, start.lerp(end, 1.0 / 3.0),
                start.lerp(end, 2.0 / 3.0), end);
    }

    public Vec3 position(double partialTick) {
        double alpha = Double.isFinite(partialTick) ? Math.max(0, Math.min(1, partialTick)) : 0;
        return start.lerp(end, alpha);
    }

    /** One sample per rendered frame, at most one per 165-Hz time slot; never catches up. */
    public static final class Cadence {
        private static final long SECOND = 1_000_000_000L;
        private boolean initialized;
        private long originNanos;
        private long slot;
        private long samples;
        private double sampledTick;

        public boolean advance(long nowNanos, double renderTick) {
            if (!Double.isFinite(renderTick)) return false;
            long elapsed = nowNanos - originNanos;
            if (!initialized || elapsed < 0L || renderTick < sampledTick) {
                initialized = true;
                originNanos = nowNanos;
                slot = 0L;
                samples = 1L;
                sampledTick = renderTick;
                return true;
            }
            // One nanosecond covers integer-clock rounding at an exact 165-Hz boundary.
            long current = elapsed / SECOND * MAX_SAMPLE_HZ
                    + (elapsed % SECOND + 1L) * MAX_SAMPLE_HZ / SECOND;
            if (current <= slot || renderTick == sampledTick) return false;
            slot = current;
            samples++;
            sampledTick = renderTick;
            return true;
        }

        public long slot() { return slot; }
        public long samples() { return samples; }

        public void reset() {
            initialized = false;
            originNanos = slot = samples = 0L;
            sampledTick = 0.0D;
        }
    }

    /** Two real tick intervals plus one accepted muzzle interval; no unbounded path history. */
    public static final class Presentation {
        private static final double EPSILON = 1.0E-8D;
        private TracerInterpolation current;
        private TracerInterpolation previous;
        private TracerInterpolation currentFlight;
        private TracerInterpolation previousFlight;
        private TracerInterpolation launch;
        private long currentTick = Long.MIN_VALUE;
        private long previousTick = Long.MIN_VALUE;
        private long launchTick = Long.MIN_VALUE;
        private Vec3 muzzle;
        private Vec3 launchDirection;
        private Vec3 launchTail;
        private boolean pendingLaunch;
        private double delayTicks;
        private Vec3 sampledStart;
        private Vec3 sampledEnd;
        private Vec3 sampledDirection;
        private boolean initialSample;
        private boolean launchInterval;
        private long sourceTick;
        private double sourceAlpha;

        public void update(TracerInterpolation frame, long tick) {
            if (tick != currentTick) {
                previous = tick - currentTick == 1L ? current : null;
                previousFlight = previous == null ? null : currentFlight;
                previousTick = previous == null ? Long.MIN_VALUE : currentTick;
            }
            // A tracking correction can replace both entity endpoints between client ticks.
            // Join the last displayed interval to the new real endpoint instead of snapping
            // to its overwritten start. This never extrapolates beyond the received endpoint.
            current = previous != null && finite(previous.end) && finite(frame.end)
                    ? between(previous.end, frame.end) : frame;
            currentFlight = frame;
            currentTick = tick;
        }

        public void anchor(Vec3 position, Vec3 direction) {
            muzzle = position;
            launchDirection = unit(direction);
            pendingLaunch = true;
            launch = null;
            launchTail = null;
            launchTick = Long.MIN_VALUE;
            delayTicks = 0.0D;
            sampledStart = sampledEnd = sampledDirection = null;
        }

        public boolean sample(double renderTick, Vec3 fallbackDirection, double beamLength) {
            sampledStart = sampledEnd = sampledDirection = null;
            initialSample = launchInterval = false;
            if (!Double.isFinite(renderTick) || !Double.isFinite(beamLength)
                    || beamLength <= 0.0D || current == null
                    || !finite(current.start) || !finite(current.end)) return false;
            if (pendingLaunch) {
                double phase = renderTick - currentTick;
                if (!finite(muzzle) || launchDirection == null || phase < 0.0D || phase > 1.0D)
                    return false;
                Vec3 tail = muzzle.m_82549_(launchDirection.m_82490_(MUZZLE_CLEARANCE_BLOCKS));
                Vec3 toEndpoint = current.end.m_82546_(tail);
                double forward = toEndpoint.f_82479_ * launchDirection.f_82479_
                        + toEndpoint.f_82480_ * launchDirection.f_82480_
                        + toEndpoint.f_82481_ * launchDirection.f_82481_;
                if (!Double.isFinite(forward) || forward <= EPSILON) return false;
                double length = Math.min(Math.min(beamLength, MAX_INITIAL_LENGTH_BLOCKS), forward);
                Vec3 head = tail.m_82549_(launchDirection.m_82490_(length));
                launch = between(head, current.end);
                launchTail = tail;
                launchTick = currentTick;
                delayTicks = phase;
                pendingLaunch = false;
                initialSample = true;
            }

            double alpha = renderTick - delayTicks - currentTick;
            TracerInterpolation frame = current;
            TracerInterpolation flightFrame = currentFlight;
            sourceTick = currentTick;
            if (alpha < 0.0D) {
                if (alpha < -1.0D || previous == null || previousTick != currentTick - 1L)
                    return false;
                frame = previous;
                flightFrame = previousFlight;
                sourceTick = previousTick;
                alpha += 1.0D;
            }
            if (alpha < 0.0D || alpha > 1.0D) return false;
            launchInterval = sourceTick == launchTick;
            if (launchInterval) frame = launch;
            if (frame == null || !finite(frame.start) || !finite(frame.end)) return false;

            Vec3 end = frame.position(alpha);
            Vec3 chord = launchInterval ? end.m_82546_(launchTail)
                    : flightFrame.end.m_82546_(flightFrame.start);
            Vec3 direction = unit(chord);
            if (direction == null) direction = unit(fallbackDirection);
            if (!finite(end) || direction == null) return false;
            double length = launchInterval ? Math.min(beamLength, Math.sqrt(chord.m_82556_()))
                    : beamLength;
            if (!(length > EPSILON)) return false;
            Vec3 start = end.m_82549_(direction.m_82490_(-length));
            if (!finite(start)) return false;
            sampledStart = start;
            sampledEnd = end;
            sampledDirection = direction;
            sourceAlpha = alpha;
            return true;
        }

        public Vec3 start() { return sampledStart; }
        public Vec3 end() { return sampledEnd; }
        public Vec3 direction() { return sampledDirection; }
        public Vec3 muzzle() { return muzzle; }
        public boolean initialSample() { return initialSample; }
        public boolean launchInterval() { return launchInterval; }
        public long sourceTick() { return sourceTick; }
        public double sourceAlpha() { return sourceAlpha; }
        public double delayTicks() { return delayTicks; }

        private static Vec3 unit(Vec3 value) {
            return finite(value) && value.m_82556_() > EPSILON ? value.m_82541_() : null;
        }

        private static boolean finite(Vec3 value) {
            return value != null && Double.isFinite(value.f_82479_)
                    && Double.isFinite(value.f_82480_) && Double.isFinite(value.f_82481_);
        }
    }

    /** Fragments retain their full dimensions until the last quarter of their lifetime. */
    public static float fragmentScale(double ageTicks, int lifetimeTicks) {
        if (lifetimeTicks <= 0 || !Double.isFinite(ageTicks)) return 0;
        return (float) Math.max(0.0, Math.min(1.0, 4.0 * (1.0 - ageTicks / lifetimeTicks)));
    }
}
