package com.atsuishio.superbwarfare.api.performance;

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;

/**
 * Opt-in, allocation-free counters for client render-path diagnosis.
 *
 * <p>The disabled hot path is one volatile boolean read. Timers do not call
 * {@link System#nanoTime()} unless collection is explicitly enabled. This class deliberately owns
 * no logging, scheduler, entity, level, or renderer references; callers request an immutable
 * snapshot explicitly.</p>
 */
public final class ClientRenderPerformanceDiagnostics {
    public static final long TIMER_DISABLED = Long.MIN_VALUE;

    private static volatile boolean enabled;
    /** Held by the diagnostic perf probe so Elite's per-tick shutdown does not stop its counters. */
    private static volatile boolean probeActive;
    /** A/B switch for the batched direct VBO draw (BVP); only the perf probe sets it. */
    private static volatile boolean batchingDisabled;
    private static long batchPasses;
    private static long lastFrameNanos = Long.MIN_VALUE;

    private static long frameIntervals;
    private static long frameIntervalNanos;
    private static long maxFrameIntervalNanos;
    private static long vehicleRenders;
    private static long vehicleRenderNanos;
    private static long vehicleModelLoads;
    private static long vehicleModelLoadFailures;
    private static long vehicleModelLoadNanos;

    private static long polyMeshVboHits;
    private static long polyMeshVboMisses;
    private static long polyMeshUploads;
    private static long polyMeshUploadBytes;
    private static long polyMeshDrawCalls;

    private static long linksTransformRebuilds;
    private static long linksEvaluated;
    private static long linksTransformNanos;

    private static long tracerDiscoveryPasses;
    private static long tracerCandidatesVisited;
    private static long tracerCandidatesAccepted;
    private static long tracerDiscoveryNanos;
    private static long tracerRenderPasses;
    private static long tracerRetainedSamplesVisited;
    private static long tracerLiveBeamsVisited;
    private static long tracerBeamsDrawn;
    private static long tracerRenderNanos;

    private static long ccipSamples;
    private static long ccipCollisionSteps;
    private static long ccipSampleNanos;

    private ClientRenderPerformanceDiagnostics() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean isProbeActive() {
        return probeActive;
    }

    /** Diagnostic launches only: the perf probe owns the counters for its capture window. */
    public static void setProbeActive(boolean value) {
        probeActive = value && DebugFeaturePolicy.allowsDebugTools();
        setEnabled(probeActive);
    }

    public static boolean isBatchingDisabled() {
        return batchingDisabled;
    }

    public static void setBatchingDisabled(boolean value) {
        batchingDisabled = value && DebugFeaturePolicy.allowsDebugTools();
    }

    public static void recordBatchPass() {
        if (enabled) {
            batchPasses++;
        }
    }

    public static long batchPasses() {
        return batchPasses;
    }

    public static void setEnabled(boolean value) {
        value = value && DebugFeaturePolicy.allowsDebugTools();
        if (value) {
            reset();
        }
        enabled = value;
    }

    public static long startTimer() {
        return enabled ? System.nanoTime() : TIMER_DISABLED;
    }

    public static void recordFrameBoundary(long nowNanos, boolean valid) {
        if (!enabled) {
            return;
        }
        if (!valid || nowNanos < 0L) {
            lastFrameNanos = Long.MIN_VALUE;
            return;
        }
        if (lastFrameNanos != Long.MIN_VALUE) {
            long elapsed = nowNanos - lastFrameNanos;
            if (elapsed > 0L) {
                frameIntervals++;
                frameIntervalNanos += elapsed;
                maxFrameIntervalNanos = Math.max(maxFrameIntervalNanos, elapsed);
            }
        }
        lastFrameNanos = nowNanos;
    }

    public static void recordVehicleRender(long startedNanos) {
        if (!enabled || startedNanos == TIMER_DISABLED) {
            return;
        }
        vehicleRenders++;
        vehicleRenderNanos += elapsedSince(startedNanos);
    }

    public static void recordVehicleModelLoad(long startedNanos, boolean success) {
        if (!enabled || startedNanos == TIMER_DISABLED) {
            return;
        }
        vehicleModelLoads++;
        if (!success) {
            vehicleModelLoadFailures++;
        }
        vehicleModelLoadNanos += elapsedSince(startedNanos);
    }

    public static void recordPolyMeshVboLookup(boolean hit) {
        if (!enabled) {
            return;
        }
        if (hit) {
            polyMeshVboHits++;
        } else {
            polyMeshVboMisses++;
        }
    }

    public static void recordPolyMeshUpload(long bytes) {
        if (!enabled) {
            return;
        }
        polyMeshUploads++;
        polyMeshUploadBytes += Math.max(0L, bytes);
    }

    public static void recordPolyMeshDraw() {
        if (enabled) {
            polyMeshDrawCalls++;
        }
    }

    public static void recordLinksTransformRebuild(long startedNanos, int links) {
        if (!enabled || startedNanos == TIMER_DISABLED) {
            return;
        }
        linksTransformRebuilds++;
        linksEvaluated += Math.max(0, links);
        linksTransformNanos += elapsedSince(startedNanos);
    }

    public static void recordTracerDiscovery(long startedNanos, int visited, int accepted) {
        if (!enabled || startedNanos == TIMER_DISABLED) {
            return;
        }
        tracerDiscoveryPasses++;
        tracerCandidatesVisited += Math.max(0, visited);
        tracerCandidatesAccepted += Math.max(0, accepted);
        tracerDiscoveryNanos += elapsedSince(startedNanos);
    }

    public static void recordTracerRender(long startedNanos, int retainedVisited,
                                          int liveVisited, int drawn) {
        if (!enabled || startedNanos == TIMER_DISABLED) {
            return;
        }
        tracerRenderPasses++;
        tracerRetainedSamplesVisited += Math.max(0, retainedVisited);
        tracerLiveBeamsVisited += Math.max(0, liveVisited);
        tracerBeamsDrawn += Math.max(0, drawn);
        tracerRenderNanos += elapsedSince(startedNanos);
    }

    public static void recordCcipSample(long startedNanos, int collisionSteps) {
        if (!enabled || startedNanos == TIMER_DISABLED) {
            return;
        }
        ccipSamples++;
        ccipCollisionSteps += Math.max(0, collisionSteps);
        ccipSampleNanos += elapsedSince(startedNanos);
    }

    private static long elapsedSince(long startedNanos) {
        return Math.max(0L, System.nanoTime() - startedNanos);
    }

    public static void reset() {
        lastFrameNanos = Long.MIN_VALUE;
        frameIntervals = 0L;
        frameIntervalNanos = 0L;
        maxFrameIntervalNanos = 0L;
        vehicleRenders = 0L;
        vehicleRenderNanos = 0L;
        vehicleModelLoads = 0L;
        vehicleModelLoadFailures = 0L;
        vehicleModelLoadNanos = 0L;
        polyMeshVboHits = 0L;
        polyMeshVboMisses = 0L;
        polyMeshUploads = 0L;
        polyMeshUploadBytes = 0L;
        polyMeshDrawCalls = 0L;
        batchPasses = 0L;
        linksTransformRebuilds = 0L;
        linksEvaluated = 0L;
        linksTransformNanos = 0L;
        tracerDiscoveryPasses = 0L;
        tracerCandidatesVisited = 0L;
        tracerCandidatesAccepted = 0L;
        tracerDiscoveryNanos = 0L;
        tracerRenderPasses = 0L;
        tracerRetainedSamplesVisited = 0L;
        tracerLiveBeamsVisited = 0L;
        tracerBeamsDrawn = 0L;
        tracerRenderNanos = 0L;
        ccipSamples = 0L;
        ccipCollisionSteps = 0L;
        ccipSampleNanos = 0L;
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                enabled,
                frameIntervals,
                frameIntervalNanos,
                maxFrameIntervalNanos,
                vehicleRenders,
                vehicleRenderNanos,
                vehicleModelLoads,
                vehicleModelLoadFailures,
                vehicleModelLoadNanos,
                polyMeshVboHits,
                polyMeshVboMisses,
                polyMeshUploads,
                polyMeshUploadBytes,
                polyMeshDrawCalls,
                linksTransformRebuilds,
                linksEvaluated,
                linksTransformNanos,
                tracerDiscoveryPasses,
                tracerCandidatesVisited,
                tracerCandidatesAccepted,
                tracerDiscoveryNanos,
                tracerRenderPasses,
                tracerRetainedSamplesVisited,
                tracerLiveBeamsVisited,
                tracerBeamsDrawn,
                tracerRenderNanos,
                ccipSamples,
                ccipCollisionSteps,
                ccipSampleNanos);
    }

    public record Snapshot(
            boolean enabled,
            long frameIntervals,
            long frameIntervalNanos,
            long maxFrameIntervalNanos,
            long vehicleRenders,
            long vehicleRenderNanos,
            long vehicleModelLoads,
            long vehicleModelLoadFailures,
            long vehicleModelLoadNanos,
            long polyMeshVboHits,
            long polyMeshVboMisses,
            long polyMeshUploads,
            long polyMeshUploadBytes,
            long polyMeshDrawCalls,
            long linksTransformRebuilds,
            long linksEvaluated,
            long linksTransformNanos,
            long tracerDiscoveryPasses,
            long tracerCandidatesVisited,
            long tracerCandidatesAccepted,
            long tracerDiscoveryNanos,
            long tracerRenderPasses,
            long tracerRetainedSamplesVisited,
            long tracerLiveBeamsVisited,
            long tracerBeamsDrawn,
            long tracerRenderNanos,
            long ccipSamples,
            long ccipCollisionSteps,
            long ccipSampleNanos) {
    }
}
