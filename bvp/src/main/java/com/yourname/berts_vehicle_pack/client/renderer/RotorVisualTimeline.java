package com.yourname.berts_vehicle_pack.client.renderer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Client rotor phase follows a vehicle UUID across normal-entity and far-copy handoffs. */
public final class RotorVisualTimeline {
    private static final int CAPACITY = 256;
    private final Map<UUID, State> states = new LinkedHashMap<>(16, 0.75F, true);
    private Object world;

    public record Sample(float speed, float mainDegrees, float tailDegrees) { }

    public Sample sample(Object currentWorld, UUID vehicle, double renderTick, boolean active) {
        return sample(currentWorld, vehicle, renderTick, active, false);
    }

    public Sample sample(Object currentWorld, UUID vehicle, double renderTick, boolean active, boolean paused) {
        if (currentWorld != world) {
            states.clear();
            world = currentWorld;
        }
        State state = states.get(vehicle);
        if (state == null) {
            state = new State(renderTick);
            states.put(vehicle, state);
            if (states.size() > CAPACITY) states.remove(states.keySet().iterator().next());
        }
        double elapsed = renderTick - state.lastRenderTick;
        if (Double.isFinite(renderTick)) state.lastRenderTick = renderTick;
        if (paused) return new Sample(state.speed, state.mainDegrees, state.tailDegrees);
        float delta = Double.isFinite(elapsed) ? (float) Math.max(0.0, Math.min(elapsed, 2.0)) : 0.0F;
        float target = active ? 1.0F : 0.0F;
        if (active && state.speed < 0.02F) state.speed = 0.02F;
        float step = (target > state.speed ? 0.00225F : 0.04F) * delta;
        state.speed += Math.max(-step, Math.min(target - state.speed, step));
        if (!active && state.speed <= 1.0E-4F) {
            // Retain the integrated parked phase in the bounded UUID cache across retracking.
            state.speed = 0.0F;
            return new Sample(0.0F, state.mainDegrees, state.tailDegrees);
        }
        state.mainDegrees = wrap(state.mainDegrees + 98.0F * state.speed * delta);
        state.tailDegrees = wrap(state.tailDegrees + 392.0F * state.speed * delta);
        return new Sample(state.speed, state.mainDegrees, state.tailDegrees);
    }

    private static float wrap(float degrees) {
        return (degrees % 360.0F + 360.0F) % 360.0F;
    }

    private static final class State {
        private float speed;
        private float mainDegrees;
        private float tailDegrees;
        private double lastRenderTick;

        private State(double renderTick) {
            lastRenderTick = Double.isFinite(renderTick) ? renderTick : 0.0;
        }
    }
}
