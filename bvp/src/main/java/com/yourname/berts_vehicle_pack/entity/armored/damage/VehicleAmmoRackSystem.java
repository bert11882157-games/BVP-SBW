package com.yourname.berts_vehicle_pack.entity.armored.damage;

import com.yourname.berts_vehicle_pack.ammo.BvpTankShells;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

public final class VehicleAmmoRackSystem {
    private static final double AMMO_RACK_WARNING_RADIUS_SQR = 16.0D * 16.0D;

    private final ArmoredVehicleEntity vehicle;
    private int warningLevel;

    public VehicleAmmoRackSystem(ArmoredVehicleEntity vehicle) {
        this.vehicle = vehicle;
    }

    public void tickWarning() {
        int nextWarningLevel = BvpTankShells.warningLevelForContents(vehicle.getItems());
        if (nextWarningLevel == BvpTankShells.NO_AMMO_RACK_WARNING_LEVEL) {
            warningLevel = BvpTankShells.NO_AMMO_RACK_WARNING_LEVEL;
            return;
        }
        if (warningLevel >= nextWarningLevel) {
            return;
        }

        Component warning = Component.m_237113_(BvpTankShells.warningTextForLevel(nextWarningLevel))
                .m_130940_(ChatFormatting.RED);
        if (!sendWarningToPassengers(warning)) {
            sendWarningToNearbyPlayers(warning);
        }
        warningLevel = nextWarningLevel;
    }

    private boolean sendWarningToPassengers(Component warning) {
        boolean sent = false;
        for (Entity passenger : vehicle.m_20197_()) {
            if (passenger instanceof ServerPlayer player) {
                player.m_5661_(warning, false);
                sent = true;
            }
        }
        return sent;
    }

    private void sendWarningToNearbyPlayers(Component warning) {
        if (vehicle.m_9236_() instanceof ServerLevel serverLevel) {
            for (ServerPlayer player : serverLevel.m_8795_(candidate -> candidate.m_20275_(
                    vehicle.m_20185_(), vehicle.m_20186_(), vehicle.m_20189_()) <= AMMO_RACK_WARNING_RADIUS_SQR)) {
                player.m_5661_(warning, false);
            }
        }
    }
}
