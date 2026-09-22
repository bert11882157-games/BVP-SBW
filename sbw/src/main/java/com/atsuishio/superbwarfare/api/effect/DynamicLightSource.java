package com.atsuishio.superbwarfare.api.effect;

/**
 * Client-safe contract for an entity whose luminance may be consumed by an
 * optional dynamic-light compatibility bridge.
 */
public interface DynamicLightSource {
    int getDynamicLightLuminance();
}
