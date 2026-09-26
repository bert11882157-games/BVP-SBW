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
        boolean mirror = ArmorProfiles.mirrorsProfileX(profile.id);
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
            String id = target.armorLocalPointToVehicleLocal(box.centroid()).f_82479_ > 0
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
                id = target.armorLocalPointToVehicleLocal(box.centroid()).f_82479_ > 0
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
        var point = box.centroid();
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
        // Solid volume weights the marker (relative weights: a box's 8*hx*hy*hz keeps the old ratios);
        // an open mesh has no volume but still counts a little.
        double volume = Math.max(box.volume.volume(), 1.0E-9D);
        var located = result.computeIfAbsent(kind + ":" + id, key -> new LocatedModule(marker));
        located.add(marker, volume);
        // Project every vertex (a box's eight rotated corners, or a mesh volume's vertices) into the hull's
        // top-down frame: the outline is their convex hull, and track sections union into one span.
        double[] vertex = new double[3];
        int count = box.volume.vertexCount();
        double[] xs = new double[count], zs = new double[count];
        for (int index = 0; index < count; index++) {
            box.volume.vertex(index, vertex);
            var p = new ArmorProfiles.Vec(vertex[0], vertex[1], vertex[2]);
            if (box.isBarrelFrame()) p = barrel.toHullPoint(p);
            else if (box.isTurretFrame()) p = turret.toHullPoint(p);
            xs[index] = (mirror ? p.x : -p.x) * 16;
            zs[index] = p.z * 16;
            if (kind == VehicleModuleHudKind.TRACK) {
                located.minZ = Math.min(located.minZ, p.z * 16);
                located.maxZ = Math.max(located.maxZ, p.z * 16);
            }
        }
        double[] outline = convexHull(xs, zs);
        if (outline != null) located.footprints.add(outline);
    }

    /** Monotone-chain convex hull as a flat [x0, z0, x1, z1, ...] polygon, or null when degenerate. */
    static double[] convexHull(double[] xs, double[] zs) {
        int n = xs.length;
        if (n < 3) return null;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> xs[a] != xs[b] ? Double.compare(xs[a], xs[b]) : Double.compare(zs[a], zs[b]));
        int[] hull = new int[2 * n];
        int k = 0;
        for (int pass = 0; pass < 2; pass++) {
            int start = k;
            for (int j = 0; j < n; j++) {
                int i = order[pass == 0 ? j : n - 1 - j];
                while (k >= start + 2 && cross(xs, zs, hull[k - 2], hull[k - 1], i) <= 0) k--;
                hull[k++] = i;
            }
            k--;
        }
        if (k < 3) return null;
        double[] out = new double[2 * k];
        for (int i = 0; i < k; i++) { out[2 * i] = xs[hull[i]]; out[2 * i + 1] = zs[hull[i]]; }
        return out;
    }

    private static double cross(double[] xs, double[] zs, int o, int a, int b) {
        return (xs[a] - xs[o]) * (zs[b] - zs[o]) - (zs[a] - zs[o]) * (xs[b] - xs[o]);
    }

    /** Multiple hitboxes belonging to one logical module produce one volume-weighted marker. */
    private static final class LocatedModule {
        final VehicleModuleHudMarker state;
        final List<double[]> footprints = new java.util.ArrayList<>();
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
                    Double.isFinite(maxZ) ? maxZ : z / weight, List.copyOf(footprints));
        }
    }
}
