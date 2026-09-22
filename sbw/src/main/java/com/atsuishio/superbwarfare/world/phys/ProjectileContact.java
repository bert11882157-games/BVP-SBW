package com.atsuishio.superbwarfare.world.phys;

import com.atsuishio.superbwarfare.tools.OBB;
import java.util.UUID;

/** A selected contact belongs to one target and server tick; splash cannot reuse its part. */
public record ProjectileContact(UUID target, long tick, OBB.Part part) {
    public OBB.Part partFor(UUID damagedTarget, long damageTick, boolean explosion) {
        return !explosion && tick == damageTick && target.equals(damagedTarget) ? part : OBB.Part.EMPTY;
    }
}
