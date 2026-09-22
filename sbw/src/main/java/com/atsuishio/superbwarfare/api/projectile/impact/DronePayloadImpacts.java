package com.atsuishio.superbwarfare.api.projectile.impact;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListMap;

/** Optional typed ownership before a kamikaze payload applies native raw damage. */
public final class DronePayloadImpacts {
    public enum Decision { UNCLAIMED, MISS, CONSUMED }
    @FunctionalInterface
    public interface Handler {
        /** MISS rejects broadphase overlap; CONSUMED owns direct damage and detonation. */
        Decision resolve(Entity drone, Entity payload, Entity owner, Entity target, Vec3 point, Vec3 velocity);
    }
    private static final Map<String, Handler> HANDLERS = new ConcurrentSkipListMap<>();
    private DronePayloadImpacts() { }
    public static void register(ResourceLocation id, Handler handler) {
        HANDLERS.put(Objects.requireNonNull(id).toString(), Objects.requireNonNull(handler));
    }
    public static Decision resolve(Entity drone, Entity payload, Entity owner, Entity target,
                                  Vec3 point, Vec3 velocity) {
        if (drone.level().isClientSide() || payload == null) return Decision.UNCLAIMED;
        for (Handler handler : HANDLERS.values()) {
            Decision decision = handler.resolve(drone, payload, owner, target, point, velocity);
            if (decision != Decision.UNCLAIMED) return decision;
        }
        return Decision.UNCLAIMED;
    }
}
