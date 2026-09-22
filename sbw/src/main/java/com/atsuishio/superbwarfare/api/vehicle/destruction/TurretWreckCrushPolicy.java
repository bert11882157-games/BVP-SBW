package com.atsuishio.superbwarfare.api.vehicle.destruction;

import com.atsuishio.superbwarfare.entity.vehicle.TurretWreckEntity;

/** Server-side optional policy evaluated by one specifically tagged turret wreck. */
@FunctionalInterface
public interface TurretWreckCrushPolicy {
    void apply(TurretWreckEntity wreck);
}
