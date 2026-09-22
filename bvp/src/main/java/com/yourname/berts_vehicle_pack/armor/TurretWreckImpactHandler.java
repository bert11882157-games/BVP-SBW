package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.api.vehicle.destruction.VehicleDestructionContexts;
import com.atsuishio.superbwarfare.entity.vehicle.TurretWreckEntity;
import com.yourname.berts_vehicle_pack.damage.BvpDamageTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class TurretWreckImpactHandler {
    public static final ResourceLocation AMMO_RACK_CRUSH_POLICY_ID =
            new ResourceLocation("berts_vehicle_pack", "ammo_rack_turret_crush");

    private static final double MIN_CRUSH_DOWNWARD_SPEED = 0.28D;
    private static final double HORIZONTAL_EDGE_MARGIN = 0.12D;
    private static final double UNDERSIDE_BELOW_MARGIN = 0.35D;
    private static final double UNDERSIDE_ABOVE_MARGIN = 0.80D;
    private TurretWreckImpactHandler() {
    }

    public static void register() {
        VehicleDestructionContexts.registerCrushPolicy(AMMO_RACK_CRUSH_POLICY_ID,
                TurretWreckImpactHandler::crushPlayersUnderFallingTurret);
    }

    private static void crushPlayersUnderFallingTurret(TurretWreckEntity wreck) {
        if (!(wreck.m_9236_() instanceof ServerLevel level)) {
            return;
        }
        double downwardSpeed = downwardSpeed(wreck);
        boolean hardGroundImpact = wreck.m_20096_() && downwardSpeed >= MIN_CRUSH_DOWNWARD_SPEED;
        boolean fallingOntoTarget = downwardSpeed >= MIN_CRUSH_DOWNWARD_SPEED && !wreck.getSupportByVehicle();
        if (!hardGroundImpact && !fallingOntoTarget) {
            return;
        }

        AABB wreckBox = wreck.m_20191_();
        for (ServerPlayer player : level.m_6907_()) {
            if (player.m_21224_() || player.m_20202_() != null || !isPlayerUnderTurret(wreckBox, player)) {
                continue;
            }
            player.m_6469_(BvpDamageTypes.randomTurretCrush(level, wreck), Float.MAX_VALUE);
        }
    }

    private static double downwardSpeed(TurretWreckEntity wreck) {
        Vec3 motion = wreck.m_20184_();
        return Math.max(Math.max(0.0D, -motion.f_82480_), Math.max(0.0D, -wreck.getLastTickVerticalSpeed()));
    }

    private static boolean isPlayerUnderTurret(AABB wreckBox, ServerPlayer player) {
        AABB playerBox = player.m_20191_();
        double centerX = (playerBox.f_82288_ + playerBox.f_82291_) * 0.5D;
        double centerZ = (playerBox.f_82290_ + playerBox.f_82293_) * 0.5D;
        boolean horizontallyUnder = centerX >= wreckBox.f_82288_ + HORIZONTAL_EDGE_MARGIN
                && centerX <= wreckBox.f_82291_ - HORIZONTAL_EDGE_MARGIN
                && centerZ >= wreckBox.f_82290_ + HORIZONTAL_EDGE_MARGIN
                && centerZ <= wreckBox.f_82293_ - HORIZONTAL_EDGE_MARGIN;
        if (!horizontallyUnder) {
            return false;
        }

        double turretBottom = wreckBox.f_82289_;
        return playerBox.f_82292_ >= turretBottom - UNDERSIDE_BELOW_MARGIN
                && playerBox.f_82289_ <= turretBottom + UNDERSIDE_ABOVE_MARGIN;
    }
}
