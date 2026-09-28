package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/** Handheld shaped-charge admission; ordinary bullets and grenade launchers remain native. */
public final class BvpHandheldAtPolicy {
    public static final ResourceLocation PROFILE =
            new ResourceLocation(BertsVehiclePack.MODID, "handheld_at/heat_110");

    private BvpHandheldAtPolicy() { }

    public static boolean accepts(String gunType, boolean explosive) {
        return explosive && "rpg".equalsIgnoreCase(gunType);
    }

    static boolean owns(Entity projectile) {
        return projectile != null && PROFILE.equals(ProjectileProfiles.profileId(projectile));
    }
}
