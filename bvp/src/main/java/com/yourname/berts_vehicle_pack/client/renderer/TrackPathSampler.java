package com.yourname.berts_vehicle_pack.client.renderer;

import net.minecraft.util.Mth;

final class TrackPathSampler {
    private static final float T90_TRACK_Y_MIN = -21.05F;
    private static final float T90_TRACK_Y_MAX = 0.1F;
    private static final float T90_TRACK_Z_MIN = -6.64F;
    private static final float T90_TRACK_Z_MAX = 116.49F;
    private static final float[][] T90_ROT_X = new float[][] {
            {0.0F, -1.25F}, {42.3333F, -1.25F}, {47.6667F, -135.0F},
            {48.6667F, -135.0F}, {49.3333F, -145.4F}, {50.0F, -155.0F},
            {54.0F, -155.0F}, {56.6667F, -180.0F}, {87.0F, -180.0F},
            {88.6667F, -216.0F}, {93.8333F, -216.0F}, {94.1667F, -218.5F},
            {94.5F, -221.0F}, {95.5F, -221.0F}, {100.0F, -360.0F}
    };
    private static final float[][] T90_MOVE_Y = new float[][] {
            {0.0F, 0.1F}, {43.0F, -2.35F}, {44.0F, -3.46F}, {44.8333F, -5.665F},
            {45.75F, -7.72F}, {46.75F, -9.965F}, {48.0F, -12.62F},
            {49.25F, -14.57F}, {54.0F, -19.92F}, {55.1667F, -20.81F},
            {56.6667F, -20.92F}, {86.6667F, -21.02F}, {87.1667F, -21.05F},
            {87.75F, -20.77F}, {88.3333F, -20.09F}, {88.8333F, -19.32F},
            {94.0833F, -11.055F}, {95.0833F, -9.71F}, {96.0F, -8.1F},
            {96.9167F, -5.56F}, {97.75F, -3.26F}, {99.0F, -1.24F}, {100.0F, 0.1F}
    };
    private static final float[][] T90_MOVE_Z = new float[][] {
            {0.0F, -3.0F}, {43.0F, 112.75F}, {44.0F, 114.53F}, {44.8333F, 116.005F},
            {45.75F, 116.49F}, {46.75F, 115.97F}, {48.0F, 113.97F},
            {49.25F, 111.47F}, {54.0F, 100.1F}, {55.1667F, 97.31F},
            {56.6667F, 93.31F}, {86.6667F, 13.36F}, {87.1667F, 12.03F},
            {87.75F, 10.47F}, {88.3333F, 8.96F}, {88.8333F, 7.67F},
            {94.0833F, -3.395F}, {95.0833F, -5.05F}, {96.0F, -6.17F},
            {96.9167F, -6.64F}, {97.75F, -6.25F}, {99.0F, -4.99F}, {100.0F, -3.0F}
    };

    private final float trackYBottom;
    private final float trackYTop;
    private final float trackZRear;
    private final float trackZFront;

    TrackPathSampler(float trackYCenter, float trackRadius, float trackZRear, float trackZFront) {
        this.trackYBottom = trackYCenter - trackRadius;
        this.trackYTop = trackYCenter + trackRadius;
        this.trackZRear = trackZRear;
        this.trackZFront = trackZFront;
    }

    void sample(float phase, TrackPathSample out) {
        sampleT90Track(phase, out);
    }

    float phaseDistance(int linkCount) {
        return 100.0F / Math.max(1, linkCount);
    }

    static float normalizePhase(float value, int wrapRange) {
        if (wrapRange <= 0) {
            return RendererBones.wrap(value, 100.0F);
        }
        // SBW exposes track progress in the vehicle's own wrap range; BVP geometry samples use percent phase.
        return RendererBones.wrap(value, (float) wrapRange) * 100.0F / wrapRange;
    }

    private void sampleT90Track(float phase, TrackPathSample out) {
        float yScale = (this.trackYTop - this.trackYBottom) / (T90_TRACK_Y_MAX - T90_TRACK_Y_MIN);
        float zScale = (this.trackZFront - this.trackZRear) / (T90_TRACK_Z_MAX - T90_TRACK_Z_MIN);
        float t90Y = interpolateTable(T90_MOVE_Y, phase);
        float t90Z = interpolateTable(T90_MOVE_Z, phase);
        out.set(
                this.trackYBottom + (t90Y - T90_TRACK_Y_MIN) * yScale,
                this.trackZRear + (t90Z - T90_TRACK_Z_MIN) * zScale,
                -interpolateTable(T90_ROT_X, phase));
    }

    private static float interpolateTable(float[][] table, float phase) {
        float wrapped = RendererBones.wrap(phase, 100.0F);
        int low = 1;
        int high = table.length - 1;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (wrapped <= table[middle][0]) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        float fromPhase = table[low - 1][0];
        float toPhase = table[low][0];
        float t = (wrapped - fromPhase) / (toPhase - fromPhase);
        return Mth.m_14179_(t, table[low - 1][1], table[low][1]);
    }
}

final class TrackPathSample {
    float y;
    float z;
    float rotationXDegrees;

    void set(float y, float z, float rotationXDegrees) {
        this.y = y;
        this.z = z;
        this.rotationXDegrees = rotationXDegrees;
    }
}
