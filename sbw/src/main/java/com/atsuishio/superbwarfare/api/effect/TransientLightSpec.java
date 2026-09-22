package com.atsuishio.superbwarfare.api.effect;

/** Immutable, validated description of a short-lived dynamic light. */
public record TransientLightSpec(int luminance, int lifetimeTicks) {
    public TransientLightSpec {
        luminance = Math.max(0, Math.min(15, luminance));
        lifetimeTicks = Math.max(1, lifetimeTicks);
    }
}
