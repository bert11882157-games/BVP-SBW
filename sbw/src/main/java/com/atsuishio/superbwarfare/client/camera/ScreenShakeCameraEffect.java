package com.atsuishio.superbwarfare.client.camera;

import net.minecraftforge.client.event.ViewportEvent;

/** Existing screen-shake arithmetic at its camera-event consumer; no state or pose ownership. */
public final class ScreenShakeCameraEffect {
    private ScreenShakeCameraEffect() { }

    /** Returns the existing view-roll contribution; yaw/pitch stay on the same Forge event. */
    public static float apply(ViewportEvent.ComputeCameraAngles event, double phase, double amplitude,
                              float radiusGain, double direction, boolean mounted) {
        double impulse = phase * Math.sin(0.5 * Math.PI * phase) * amplitude * radiusGain;
        double axis = impulse * direction * (mounted ? 0.1 : 1.0);
        double roll = impulse * (mounted ? 0.1 : 1.0);
        if (direction > 0.0) {
            event.setYaw((float) (event.getYaw() + axis));
            event.setPitch((float) (event.getPitch() - axis));
            return (float) (event.getRoll() - roll);
        }
        event.setYaw((float) (event.getYaw() - axis));
        event.setPitch((float) (event.getPitch() + axis));
        return (float) (event.getRoll() + roll);
    }
}
