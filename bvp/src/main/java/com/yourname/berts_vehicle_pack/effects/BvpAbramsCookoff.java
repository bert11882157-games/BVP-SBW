package com.yourname.berts_vehicle_pack.effects;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.Set;

/** Explicit family policy: blowout-compartment tanks retain their turret even on rack death. */
public final class BvpAbramsCookoff {
    private static final Set<String> VEHICLES = Set.of("m1_abrams_elite", "m1a2_abrams_sep_v2", "m1a1_abrams");
    public static boolean applies(VehicleEntity vehicle) {
        var id = ForgeRegistries.ENTITY_TYPES.getKey(vehicle.getType());
        return id != null && id.getNamespace().equals("berts_vehicle_pack") && VEHICLES.contains(id.getPath());
    }
    private BvpAbramsCookoff() {}
}
