package com.atsuishio.superbwarfare.resource.vehicle;

import com.google.gson.annotations.SerializedName;

/** Optional source-fitted turret-pitch children; the client validates every binding before use. */
public final class FittedGroundRigResource {
    @SerializedName("Schema") public Integer schema;
    @SerializedName("Frame") public String frame;
    @SerializedName("PitchBones") public PitchBone[] pitchBones;

    public static final class PitchBone {
        @SerializedName("Bone") public String bone;
        @SerializedName("Parent") public String parent;
        @SerializedName("Pivot") public double[] pivot;
    }
}
