package com.yourname.berts_vehicle_pack.entity;

import com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleCopies;
import com.atsuishio.superbwarfare.api.vehicle.render.FarVehiclePresentation;
import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.yourname.berts_vehicle_pack.entity.helicopter.BvpHelicopterEntity;
import net.minecraft.world.phys.Vec3;

/** Shared visual predicates for live vehicles and their cosmetic snapshots. */
public final class BvpFarVehicleVisuals {
    public static final String ROTOR_ACTIVE = "bvp.rotor_active";
    public static final String ROTOR_SPOOL = "bvp.rotor_spool";
    public static final String SPENT_ERA = "bvp.spent_era";
    public static final String LEFT_TRACK_BROKEN = "bvp.left_track_broken";
    public static final String RIGHT_TRACK_BROKEN = "bvp.right_track_broken";

    private BvpFarVehicleVisuals() {
    }

    /** Accepted rotor power, independent of collective, camera mode, and fixed-wing channels. */
    public static double helicopterRotorSpool(GeoVehicleEntity entity, float partialTick) {
        FarVehiclePresentation far = FarVehicleCopies.frame(entity);
        if (far != null) {
            if (far.getSnapshot().getWreck()) return 0.0D;
            return parseRotorSpool(far.getSnapshot().getVisualData().get(ROTOR_SPOOL));
        }
        if (!(entity instanceof BvpHelicopterEntity) || !Float.isFinite(partialTick)) return Double.NaN;
        var flight = entity.getVehicleFlightInstrumentSnapshot(partialTick);
        return flight.getServerTick() > 0 ? boundedRotorSpool(flight.getRotorLift()) : Double.NaN;
    }

    static double parseRotorSpool(String text) {
        if (text == null || text.length() > 32) return Double.NaN;
        try {
            return boundedRotorSpool(Double.parseDouble(text));
        } catch (NumberFormatException invalid) {
            return Double.NaN;
        }
    }

    private static double boundedRotorSpool(double value) {
        return Double.isFinite(value) && value >= 0.0D && value <= 1.0D ? value : Double.NaN;
    }

    public static boolean trackBroken(ArmoredVehicleEntity entity, boolean left) {
        FarVehiclePresentation far = FarVehicleCopies.frame(entity);
        if (far != null) {
            return "true".equals(far.getSnapshot().getVisualData().get(
                    left ? LEFT_TRACK_BROKEN : RIGHT_TRACK_BROKEN));
        }
        return left ? entity.isLeftTrackBroken() : entity.isRightTrackBroken();
    }

    public static boolean rotorActive(GeoVehicleEntity entity) {
        FarVehiclePresentation far = FarVehicleCopies.frame(entity);
        if (far != null) {
            return !far.getSnapshot().getWreck()
                    && "true".equals(far.getSnapshot().getVisualData().get(ROTOR_ACTIVE));
        }
        if (entity instanceof ArmoredVehicleEntity armored && armored.isWreck()) {
            return false;
        }
        if (entity.m_9236_().f_46443_) {
            Boolean accepted = ClientRotorActivity.resolve(entity);
            if (accepted != null) return accepted;
        }
        if (entity.m_20197_().isEmpty()) {
            return false;
        }
        if (entity instanceof BvpHelicopterEntity helicopter
                && (helicopter.getBvpThrottleTarget() > 0.01D
                || helicopter.getBvpRotorLiftPower() > 0.01D
                || helicopter.getBvpRotorThrustMps2() > 0.01D)) {
            return true;
        }
        if (Math.abs(entity.getPower()) > 0.01F || Math.abs(entity.getTargetSpeed()) > 0.01D) {
            return true;
        }
        Vec3 motion = entity.m_20184_();
        return motion.m_82556_() > 1.0E-4D;
    }

    /** Keeps client-only cache access out of the server's visual-state producer. */
    private static final class ClientRotorActivity {
        private static Boolean resolve(GeoVehicleEntity entity) {
            var client = com.atsuishio.superbwarfare.client.FarVehicleClient.INSTANCE;
            if (client.currentLevel() != entity.m_9236_()) return null;
            var entry = client.getStore().get(entity.getId());
            if (entry == null) return null;
            var snapshot = entry.getCurrent();
            if (!snapshot.getUuid().equals(entity.getStringUUID())
                    || !snapshot.getType().equals(entity.getEncodeId())
                    || !snapshot.getOverrideData().equals(entity.getOverride())) return null;
            double age = client.time(0.0F) - entry.getReceivedTick();
            if (!Double.isFinite(age) || age < 0.0D || age > 2.0D * entry.getInterval()) return null;
            // Passenger tracking may arrive or retire separately from its vehicle. A current
            // server visual decision, including explicit shutdown, owns that brief overlap.
            return !snapshot.getWreck() && snapshot.getOccupied()
                    && "true".equals(snapshot.getVisualData().get(ROTOR_ACTIVE));
        }
    }
}
