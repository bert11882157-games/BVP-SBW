package com.atsuishio.superbwarfare.client.input;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.function.Predicate;

/** GameRenderer UI selection only; weapon tracing retains its existing ProjectileUtil paths. */
public final class AircraftCollisionPicking {
    private static final ThreadLocal<UiPickScope> UI_PICK = ThreadLocal.withInitial(UiPickScope::new);

    private AircraftCollisionPicking() {
    }

    public static void beginUiPick(float partialTicks) {
        UI_PICK.get().begin(partialTicks);
    }

    public static void endUiPick() {
        UI_PICK.get().end();
    }

    public static boolean isUiPickActive() {
        return UI_PICK.get().isActive();
    }

    @Nullable
    public static EntityHitResult pickInUiScope(Entity viewer, Vec3 start, Vec3 end, AABB queryBounds,
                                               Predicate<Entity> filter, double maxDistanceSquared) {
        UiPickScope scope = UI_PICK.get();
        float partialTicks = scope.enter();
        boolean completed = false;
        try {
            EntityHitResult hit = pick(viewer, start, end, queryBounds, filter,
                    maxDistanceSquared, partialTicks);
            completed = true;
            return hit;
        } finally {
            scope.leave(completed);
        }
    }

    @Nullable
    public static EntityHitResult pick(Entity viewer, Vec3 start, Vec3 end, AABB queryBounds,
                                       Predicate<Entity> filter, double maxDistanceSquared,
                                       float partialTicks) {
        // A physical miss must never be re-admitted by vanilla's core AABB or the projectile OBBs.
        EntityHitResult nearest = ProjectileUtil.getEntityHitResult(viewer, start, end, queryBounds,
                entity -> filter.test(entity) && !hasPhysicalParts(entity, partialTicks),
                maxDistanceSquared);
        for (Entity entity : viewer.level().getEntities(viewer, queryBounds.inflate(8), filter)) {
            if (!(entity instanceof VehicleEntity vehicle) || !hasPhysicalParts(vehicle, partialTicks)) continue;
            if (vehicle.getPassengers().contains(viewer)
                    || (vehicle.getRootVehicle() == viewer.getRootVehicle() && !vehicle.canRiderInteract())) continue;
            nearest = nearer(start, nearest, vehicle.clipPhysicalCollision(start, end, partialTicks),
                    maxDistanceSquared);
        }
        return nearest;
    }

    private static boolean hasPhysicalParts(Entity entity, float partialTicks) {
        return entity instanceof VehicleEntity vehicle
                && vehicle.getAircraftCollisionSnapshot(partialTicks) != null;
    }

    @Nullable
    static EntityHitResult nearer(Vec3 start, @Nullable EntityHitResult current,
                                  @Nullable EntityHitResult candidate, double maxDistanceSquared) {
        if (candidate == null) return current;
        double distance = start.distanceToSqr(candidate.getLocation());
        if (!Double.isFinite(distance) || distance > maxDistanceSquared) return current;
        return current == null || distance < start.distanceToSqr(current.getLocation()) ? candidate : current;
    }

    static final class UiPickScope {
        private boolean active;
        private boolean resolving;
        private float partialTicks;

        void begin(float partialTicks) {
            active = Float.isFinite(partialTicks);
            resolving = false;
            this.partialTicks = partialTicks;
        }

        void end() {
            active = false;
            resolving = false;
        }

        boolean isActive() {
            return active && !resolving;
        }

        float enter() {
            resolving = true;
            return partialTicks;
        }

        void leave(boolean completed) {
            resolving = false;
            if (!completed) active = false;
        }
    }
}
