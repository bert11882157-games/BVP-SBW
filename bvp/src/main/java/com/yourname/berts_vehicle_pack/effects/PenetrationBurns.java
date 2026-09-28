package com.yourname.berts_vehicle_pack.effects;

import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Fire and rising smoke at a penetration point on a ground vehicle (owner direction 2026-09-28), sized by the
 * round's calibre: a 30 mm hole smoulders for a few seconds, a 125 mm or ATGM hole burns with open flame and then
 * smokes for about twenty. The point rides with the hull (vehicle-local) until it goes out or the vehicle is
 * gone. Presentation only: no damage.
 */
public final class PenetrationBurns {
    /** Rounds below this calibre leave no burn. */
    public static final double MIN_CALIBRE_MM = 20.0D;
    private static final int MAX_BURNS = 6;

    private final ArmoredVehicleEntity vehicle;
    private final List<Burn> burns = new ArrayList<>();

    public PenetrationBurns(ArmoredVehicleEntity vehicle) {
        this.vehicle = vehicle;
    }

    /** Burn duration in ticks for a calibre: 40 + 3 per mm (30 mm 6.5 s, 125 mm 21 s), at most 30 s. */
    public static int durationTicks(double calibreMm) {
        return (int) Math.min(600.0D, 40.0D + 3.0D * calibreMm);
    }

    /** Share of the burn with open flame: small rounds only smoke, big ones burn for the first half. */
    public static double flameShare(double calibreMm) {
        return Math.max(0.0D, Math.min(0.5D, (calibreMm - 25.0D) / 200.0D));
    }

    public void add(Vec3 worldPoint, double calibreMm) {
        if (worldPoint == null || !(calibreMm >= MIN_CALIBRE_MM) || vehicle.m_9236_().f_46443_) {
            return;
        }
        if (burns.size() >= MAX_BURNS) {
            burns.remove(0);
        }
        Vec3 local = vehicle.worldToVehicleLocal(worldPoint, 1.0F);
        int duration = durationTicks(calibreMm);
        burns.add(new Burn(local, calibreMm, duration, duration));
    }

    /** Server tick. */
    public void tick() {
        if (burns.isEmpty() || !(vehicle.m_9236_() instanceof ServerLevel level)) {
            return;
        }
        for (int i = burns.size() - 1; i >= 0; i--) {
            Burn burn = burns.get(i);
            burn.remaining--;
            if (burn.remaining <= 0 || vehicle.m_213877_()) {
                burns.remove(i);
                continue;
            }
            if ((vehicle.f_19797_ + i) % 2 != 0) {
                continue;
            }
            Vec3 at = vehicle.vehicleLocalToWorld(burn.local, 1.0F);
            double size = Math.min(1.0D, burn.calibreMm / 125.0D);
            double age = 1.0D - (double) burn.remaining / burn.duration;
            double spread = 0.05D + 0.12D * size;
            if (age < flameShare(burn.calibreMm)) {
                level.m_8767_(ParticleTypes.f_123744_, at.f_82479_, at.f_82480_, at.f_82481_,
                        1 + (int) Math.round(2.0D * size), spread, spread, spread, 0.01D);
            }
            // rising smoke: dense at first, thinning out as the hole cools
            int smoke = (int) Math.round((1.0D + 2.0D * size) * (1.0D - 0.6D * age));
            if (smoke > 0) {
                level.m_8767_(ParticleTypes.f_123777_, at.f_82479_, at.f_82480_ + 0.1D, at.f_82481_,
                        smoke, spread, 0.02D, spread, 0.005D);
            }
        }
    }

    private static final class Burn {
        final Vec3 local;
        final double calibreMm;
        final int duration;
        int remaining;

        Burn(Vec3 local, double calibreMm, int duration, int remaining) {
            this.local = local;
            this.calibreMm = calibreMm;
            this.duration = duration;
            this.remaining = remaining;
        }
    }
}
