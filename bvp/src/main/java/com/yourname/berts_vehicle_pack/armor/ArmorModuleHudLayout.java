package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.api.vehicle.presentation.VehicleModuleHudHealth;
import com.atsuishio.superbwarfare.api.vehicle.presentation.VehicleModuleHudKind;
import com.atsuishio.superbwarfare.api.vehicle.presentation.VehicleModuleHudMarker;
import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Located module health from the same authored boxes and identities used by projectile damage. */
public final class ArmorModuleHudLayout {
    private ArmorModuleHudLayout() {}

    public static List<VehicleModuleHudMarker> sample(ArmoredVehicleEntity vehicle, float partial) {
        if (!vehicle.usesBvpArmorResolution()) return List.of();
        var profile = ArmorProfiles.get(vehicle.getArmorProfileId());
        var result = new LinkedHashMap<String, LocatedModule>();
        var target = new ArmoredVehicleArmorTarget(vehicle);
        boolean mirror = "t72a".equals(profile.id) || "t72b".equals(profile.id);
        var turret = ArmorCoordinateFrame.barrelFrame(vehicle.getVehicleTransform(partial),
                vehicle.getTurretTransform(partial), vehicle.getTurretPos(), Vec3.f_82478_, mirror);
        var barrel = ArmorCoordinateFrame.barrelFrame(vehicle.getVehicleTransform(partial),
                vehicle.getBarrelTransform(partial), vehicle.getTurretPos(), vehicle.getBarrelPosition(), mirror);
        boolean tracked = vehicle.computed().getEngineType() == EngineType.TRACK;
        for (var box : profile.engineBoxes) {
            add(result, vehicle, box, "engine", VehicleModuleHudKind.ENGINE,
                    VehicleModuleHealth.ENGINE_HP, turret, barrel, mirror);
        }
        for (var box : profile.trackBoxes) {
            if (!tracked || !vehicle.usesBvpTrackModuleRepair()) continue;
            String id = target.armorLocalPointToVehicleLocal(box.center).f_82479_ > 0
                    ? "lefttrack" : "righttrack";
            add(result, vehicle, box, id, VehicleModuleHudKind.TRACK,
                    VehicleModuleHealth.TRACK_HP, turret, barrel, mirror);
        }
        for (var box : profile.moduleBoxes) {
            String id = ArmorModuleResolver.moduleIdForBox(box);
            if (id.isBlank()) continue;
            VehicleModuleHudKind kind;
            double maximum;
            if (ArmorModuleResolver.isTrack(id)) {
                if (!tracked || !vehicle.usesBvpTrackModuleRepair()) continue;
                id = target.armorLocalPointToVehicleLocal(box.center).f_82479_ > 0
                        ? "lefttrack" : "righttrack";
                kind = VehicleModuleHudKind.TRACK;
                maximum = VehicleModuleHealth.TRACK_HP;
            } else if ("engine".equals(id)) {
                kind = VehicleModuleHudKind.ENGINE;
                maximum = VehicleModuleHealth.ENGINE_HP;
            } else if (ArmorModuleResolver.isAmmoRack(id)) {
                kind = VehicleModuleHudKind.AMMO;
                maximum = VehicleModuleHealth.AMMO_RACK_HP;
            } else if (id.equals(VehicleModuleHealth.WEAPONS_SYSTEMS_ID)
                    || id.startsWith(VehicleModuleHealth.WEAPONS_SYSTEMS_ID + ":")) {
                kind = VehicleModuleHudKind.WEAPON;
                maximum = VehicleModuleHealth.WEAPONS_SYSTEMS_HP;
            } else {
                kind = VehicleModuleHudKind.MODULE;
                maximum = VehicleModuleHealth.GENERIC_MODULE_HP;
            }
            add(result, vehicle, box, id, kind, maximum, turret, barrel, mirror);
        }
        for (var box : profile.ammoRacks) {
            add(result, vehicle, box, ArmorModuleResolver.ammoRackModuleId(box),
                    VehicleModuleHudKind.AMMO, VehicleModuleHealth.AMMO_RACK_HP,
                    turret, barrel, mirror);
        }
        return result.values().stream().map(LocatedModule::marker).toList();
    }

    private static void add(Map<String, LocatedModule> result, ArmoredVehicleEntity vehicle,
                            ArmorProfiles.ArmorBox box, String id, VehicleModuleHudKind kind,
                            double maximum, ArmorCoordinateFrame.BarrelFrame turret,
                            ArmorCoordinateFrame.BarrelFrame barrel, boolean mirror) {
        var point = box.center;
        if (box.isBarrelFrame()) {
            if (barrel == null) return;
            point = barrel.toHullPoint(point);
        } else if (box.isTurretFrame()) {
            if (turret == null) return;
            point = turret.toHullPoint(point);
        }
        // Bedrock mesh loading reflects model X before applying the vehicle's visual frame.
        var marker = new VehicleModuleHudMarker(id, kind, (mirror ? point.x : -point.x) * 16,
                point.z * 16, new VehicleModuleHudHealth(vehicle.getModuleHealth(id),
                (float) maximum, vehicle.isModuleDestroyed(id)));
        double volume = box.halfSize.x * box.halfSize.y * box.halfSize.z;
        var located = result.computeIfAbsent(kind + ":" + id, key -> new LocatedModule(marker));
        located.add(marker, volume);
        if (kind == VehicleModuleHudKind.TRACK) {
            // Project all eight rotated corners, then union sections belonging to the same track.
            for (int corner = 0; corner < 8; corner++) {
                var p = new ArmorProfiles.Vec((corner & 1) == 0 ? -box.halfSize.x : box.halfSize.x,
                        (corner & 2) == 0 ? -box.halfSize.y : box.halfSize.y,
                        (corner & 4) == 0 ? -box.halfSize.z : box.halfSize.z)
                        .rotateX(box.rotationDeg.x).rotateY(box.rotationDeg.y).rotateZ(box.rotationDeg.z)
                        .add(box.center);
                if (box.isBarrelFrame()) p = barrel.toHullPoint(p);
                else if (box.isTurretFrame()) p = turret.toHullPoint(p);
                located.minZ = Math.min(located.minZ, p.z * 16);
                located.maxZ = Math.max(located.maxZ, p.z * 16);
            }
        }
    }

    /** Multiple hitboxes belonging to one logical module produce one volume-weighted marker. */
    private static final class LocatedModule {
        final VehicleModuleHudMarker state;
        double x, z, weight;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;

        LocatedModule(VehicleModuleHudMarker state) { this.state = state; }

        void add(VehicleModuleHudMarker location, double volume) {
            x += location.getModelX() * volume;
            z += location.getModelZ() * volume;
            weight += volume;
        }

        VehicleModuleHudMarker marker() {
            return new VehicleModuleHudMarker(state.getId(), state.getKind(), x / weight, z / weight,
                    state.getHealth(), Double.isFinite(minZ) ? minZ : z / weight,
                    Double.isFinite(maxZ) ? maxZ : z / weight);
        }
    }
}
