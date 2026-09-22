package com.atsuishio.superbwarfare.client.camera;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.client.Minecraft;

/** A vehicle camera owns its pose; pedestrian camera inertia must not become a second pose owner. */
public final class VehicleCameraEffectOwnership {
    private VehicleCameraEffectOwnership() { }

    public static boolean ownsMountedCamera() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.level != null && mc.player.isAlive() && !mc.player.isSpectator()
                && mc.getCameraEntity() == mc.player
                && mc.player.getVehicle() instanceof VehicleEntity vehicle
                && !vehicle.isRemoved() && vehicle.level() == mc.level;
    }

    /** Per-effect-system state. Exit cannot expose its retained vehicle-era transform before a fresh update. */
    public static final class State {
        private boolean awaitingFreshUpdate;

        public boolean suppressTransform(boolean vehicleOwned) {
            if (vehicleOwned) awaitingFreshUpdate = true;
            return vehicleOwned || awaitingFreshUpdate;
        }

        public boolean suppressUpdate(boolean vehicleOwned) {
            if (vehicleOwned) awaitingFreshUpdate = true;
            return vehicleOwned;
        }

        public boolean resetBeforeOrdinaryUpdate() {
            if (!awaitingFreshUpdate) return false;
            awaitingFreshUpdate = false;
            return true;
        }
    }
}
