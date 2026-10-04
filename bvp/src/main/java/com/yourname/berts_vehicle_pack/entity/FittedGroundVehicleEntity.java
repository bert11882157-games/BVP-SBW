package com.yourname.berts_vehicle_pack.entity;

import com.atsuishio.superbwarfare.data.gun.GunData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.Set;

/** Shared armored ground runtime; the registered source owns geometry, systems, and weapon data. */
public final class FittedGroundVehicleEntity extends ArmoredVehicleEntity {
    /**
     * Launchers stowed inside the hull to reload (9P149 Shturm-S): the reload animation brings the arm back up
     * pointing straight ahead, so when the reload completes the turret aim is reset to the front and the normal aim
     * servo slews it onto the operator's aim from there (owner 2026-09-29) instead of snapping to it.
     */
    private static final Set<String> STOWED_RELOAD = Set.of("9p149_shturm");

    private boolean launcherReloading;

    public FittedGroundVehicleEntity(EntityType<?> type, Level world, String profileId) {
        super(type, world, profileId);
    }

    @Override
    public AABB fitEntityCollisionBounds(AABB nativeBounds) {
        return BvpMovementCollisionBounds.resolve(this, nativeBounds);
    }

    @Override
    public void m_6075_() {
        super.m_6075_();
        if (!STOWED_RELOAD.contains(getArmorProfileId()) || isWreck()) {
            launcherReloading = false;
            return;
        }
        GunData gun = getGunData(0, 0);
        boolean reloading = gun != null && gun.reloading();
        if (launcherReloading && !reloading) stowedLauncherForward();
        launcherReloading = reloading;
    }

    /** The launcher leaves the hull pointing straight ahead; the servo takes it from here next tick. */
    private void stowedLauncherForward() {
        setTurretYRotO(0.0F);
        setTurretYRot(0.0F);
        setTurretXRotO(0.0F);
        setTurretXRot(0.0F);
        setTurretYRotLock(0.0F);
    }
}
