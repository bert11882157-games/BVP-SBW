package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.atsuishio.superbwarfare.client.renderer.vehicle.VehicleRenderPartSnapshot;
import com.example.sbwmeshloader.core.PolyMeshModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.yourname.berts_vehicle_pack.client.BvpFiredVisualClient;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import com.yourname.berts_vehicle_pack.entity.Bmp2Entity;
import com.yourname.berts_vehicle_pack.entity.BmptEntity;
import com.yourname.berts_vehicle_pack.entity.Btr80AEntity;
import com.yourname.berts_vehicle_pack.entity.Mig19Entity;
import com.yourname.berts_vehicle_pack.entity.ToyotaJihadDshkEntity;
import com.yourname.berts_vehicle_pack.entity.ToyotaJihadSpg9Entity;
import net.minecraft.util.Mth;

import java.util.Map;
import java.util.WeakHashMap;

final class VehicleBonePoseController {
    private static final Map<GeoVehicleEntity, VisualRecoil> VISUAL_RECOIL = new WeakHashMap<>();
    private static final Map<ArmoredVehicleEntity, HmgVisualRecoil> HMG_VISUAL_RECOIL = new WeakHashMap<>();
    private static final float DEFAULT_HMG_RECOIL_UNITS = 0.45F;
    private static final float T72B_NSVT_RECOIL_UNITS = 1.15F;

    private VehicleBonePoseController() {
    }

    static void apply(GeoVehicleEntity entity, float entityYaw, PolyMeshModel loadedModel, float partialTicks,
                      VehicleRenderPartSnapshot renderParts) {
        if (AircraftRigAnimator.apply(entity, loadedModel, partialTicks, renderParts)) {
            return;
        }
        if (entity instanceof Mig19Entity) {
            var controls = entity.getVehicleFlightControlSurfaceSnapshot(partialTicks);
            Mig19ControlSurfaceAnimator.apply(loadedModel,
                    controls == null ? 0 : controls.getElevator(),
                    controls == null ? 0 : controls.getAileron(),
                    controls == null ? 0 : controls.getRudder());
            return;
        }
        BedrockBone turret = loadedModel.getBone("turret");
        BedrockBone barrel = loadedModel.getBone("barell");
        BedrockBone barrelRecoil = loadedModel.getBone("barrelRecoil");
        BedrockBone passengerWeaponStation = loadedModel.getBone("passengerWeaponStation");
        BedrockBone passengerWeaponYawPivot = loadedModel.getBone("passengerWeaponStationYawPivot");
        BedrockBone passengerWeaponYaw = loadedModel.getBone("passengerWeaponStationYaw");
        BedrockBone passengerWeaponPitch = loadedModel.getBone("passengerWeaponStationPitch");
        // BMP-2M's authored AGS-30 graph is a turret child.  The mount inherits
        // turret yaw; only its explicit pitch pivot is driven here.
        BedrockBone ags30Mount = loadedModel.getBone("ags30_mount");
        BedrockBone ags30Pitch = loadedModel.getBone("ags30_pitch");
        boolean ags30GraphPresent = ags30Mount != null && ags30Pitch != null;
        // The M1 roof M240 graph is parented under turret. Keep
        // the mount untouched so it inherits turret yaw, and drive only its
        // authored pitch pivot from the same actual pitch used by the main barrel.
        BedrockBone roofCoaxMount = loadedModel.getBone("roofCoaxMount");
        BedrockBone roofCoaxPitch = loadedModel.getBone("roofCoaxPitch");
        boolean roofCoaxGraphPresent = roofCoaxMount != null && roofCoaxPitch != null;
        ArmoredVehicleEntity armoredEntity = entity instanceof ArmoredVehicleEntity armored ? armored : null;
        float turretYaw = renderParts.getTurretYawFromRenderedHullDegrees();
        float barrelPitch = renderParts.getBarrelPitchDegrees();
        boolean turretEjected = armoredEntity != null && armoredEntity.isTurretEjected();

        if (turret != null) {
            RendererBones.setPositionOffset(turret, 0.0F, 0.0F, 0.0F);
            RendererBones.setRotation(turret, 0.0F, turretYaw * RendererBones.DEG_TO_RAD, 0.0F);
        }
        RendererBones.resetPosition(passengerWeaponStation);
        RendererBones.resetPosition(passengerWeaponYawPivot);
        RendererBones.resetPosition(passengerWeaponYaw);
        RendererBones.resetPosition(passengerWeaponPitch);
        if (ags30GraphPresent) {
            RendererBones.resetPosition(ags30Mount);
            RendererBones.resetPosition(ags30Pitch);
            resetRotation(ags30Mount);
            resetRotation(ags30Pitch);
        }
        if (roofCoaxGraphPresent) {
            RendererBones.resetPosition(roofCoaxMount);
            RendererBones.resetPosition(roofCoaxPitch);
            resetRotation(roofCoaxMount);
            resetRotation(roofCoaxPitch);
        }
        if (passengerWeaponYaw != null) {
            resetRotation(passengerWeaponStation);
            resetRotation(passengerWeaponYaw);
        }
        EraSpentMaskController.apply(armoredEntity, loadedModel);
        if (turretEjected) {
            RendererBones.hide(turret);
            RendererBones.hide(barrel);
            RendererBones.hide(barrelRecoil);
            RendererBones.hide(passengerWeaponStation);
            RendererBones.hide(passengerWeaponYawPivot);
            RendererBones.hide(passengerWeaponYaw);
            RendererBones.hide(passengerWeaponPitch);
            if (ags30GraphPresent) {
                RendererBones.hide(ags30Mount);
                RendererBones.hide(ags30Pitch);
            }
            if (roofCoaxGraphPresent) {
                RendererBones.hide(roofCoaxMount);
                RendererBones.hide(roofCoaxPitch);
            }
            return;
        }
        RendererBones.show(turret);
        RendererBones.show(barrel);
        RendererBones.show(barrelRecoil);
        RendererBones.show(passengerWeaponStation);
        RendererBones.show(passengerWeaponYawPivot);
        RendererBones.show(passengerWeaponYaw);
        RendererBones.show(passengerWeaponPitch);
        if (ags30GraphPresent) {
            RendererBones.show(ags30Mount);
            RendererBones.show(ags30Pitch);
            Float ags30PitchDegrees = renderParts.getAgs30PitchDegrees();
            if (ags30PitchDegrees != null && Float.isFinite(ags30PitchDegrees)) {
                RendererBones.setRotation(ags30Pitch,
                        ags30PitchDegrees * RendererBones.DEG_TO_RAD,
                        0.0F,
                        0.0F);
            }
        }
        if (roofCoaxGraphPresent) {
            RendererBones.show(roofCoaxMount);
            RendererBones.show(roofCoaxPitch);
            // roofCoaxMount inherits the parent turret's yaw.  Applying yaw a
            // second time here would make the roof weapon orbit its pivot.
            RendererBones.setRotation(roofCoaxPitch,
                    renderParts.getBarrelPitchDegrees() * RendererBones.DEG_TO_RAD,
                    0.0F,
                    0.0F);
        }
        if (barrel != null) {
            float barrelYaw = 0.0F;
            float barrelRoll = 0.0F;
            if (entity instanceof BmptEntity bmpt) {
                float activity = bmptBarrelActivity(bmpt, renderParts.getCannonRecoilTime());
                if (activity > 0.0F) {
                    boolean dual = bmpt.getSelectedWeapon(0) == 1;
                    float phase = bmptBarrelPhase(bmpt, partialTicks);
                    float pitchAmplitude = dual ? 0.65F : 0.12F;
                    float yawAmplitude = dual ? 0.35F : 0.06F;
                    float rollAmplitude = dual ? 0.18F : 0.03F;
                    barrelPitch += Mth.m_14031_(phase) * pitchAmplitude * activity;
                    barrelYaw = Mth.m_14089_(phase * 0.73F) * yawAmplitude * activity;
                    barrelRoll = Mth.m_14031_(phase * 1.37F) * rollAmplitude * activity;
                }
            }
            RendererBones.setRotation(barrel,
                    barrelPitch * RendererBones.DEG_TO_RAD,
                    barrelYaw * RendererBones.DEG_TO_RAD,
                    barrelRoll * RendererBones.DEG_TO_RAD);
            if (barrelRecoil != null) {
                RendererBones.setPositionOffset(barrel, 0.0F, 0.0F, 0.0F);
                RendererBones.setBackwardRecoil(barrelRecoil,
                        cannonRecoilOffset(entity, renderParts, partialTicks));
            } else {
                RendererBones.setBackwardRecoil(barrel,
                        cannonRecoilOffset(entity, renderParts, partialTicks));
            }
        }
        if (passengerWeaponYaw != null && renderParts.getStationPresentationValid()) {
            // The station bone is a turret child, so its local yaw must match SBW's
            // gun-versus-turret angle. Using the inverse rendered-hull angle makes
            // the mesh traverse away from its native camera, muzzle and fire direction.
            float passengerWeaponYawRot = renderParts.getStationYawRelativeToTurretDegrees();
            if (passengerWeaponYawPivot != null) {
                RendererBones.setRotation(passengerWeaponYawPivot, 0.0F, passengerWeaponYawRot * RendererBones.DEG_TO_RAD, 0.0F);
            } else {
                RendererBones.setRotation(passengerWeaponYaw, 0.0F, passengerWeaponYawRot * RendererBones.DEG_TO_RAD, 0.0F);
            }
        }
        if (passengerWeaponPitch != null && renderParts.getStationPresentationValid()) {
            float passengerWeaponPitchRot = renderParts.getStationPitchDegrees();
            RendererBones.setRotation(passengerWeaponPitch, passengerWeaponPitchRot * RendererBones.DEG_TO_RAD, 0.0F, 0.0F);
            float hmgRecoil = hmgRecoilOffset(armoredEntity, partialTicks);
            if (hmgRecoil > 0.0F) {
                RendererBones.setBackwardRecoil(passengerWeaponPitch, hmgRecoil);
            }
        }
    }

    private static void resetRotation(BedrockBone bone) {
        if (bone != null) {
            RendererBones.setRotation(bone, 0.0F, 0.0F, 0.0F);
        }
    }

    private static float cannonRecoilOffset(GeoVehicleEntity entity, VehicleRenderPartSnapshot renderParts,
                                            float partialTicks) {
        int recoilTime = renderParts.getCannonRecoilTime();
        if (recoilTime <= 0) {
            VISUAL_RECOIL.remove(entity);
            return 0.0F;
        }

        if (entity instanceof BmptEntity bmpt) {
            float activity = bmptBarrelActivity(bmpt, recoilTime);
            if (activity <= 0.0F) {
                return 0.0F;
            }
            boolean dual = bmpt.getSelectedWeapon(0) == 1;
            float pulse = 0.72F + 0.28F * Math.abs(Mth.m_14031_(bmptBarrelPhase(bmpt, partialTicks)));
            float force = Math.max(0.0F, renderParts.getCannonRecoilForce());
            return force * (dual ? 0.85F : 0.55F) * activity * pulse;
        }

        if (entity instanceof Btr80AEntity
                || entity instanceof Bmp2Entity
                || entity instanceof ToyotaJihadDshkEntity
                || entity instanceof ToyotaJihadSpg9Entity) {
            VisualRecoil recoil = VISUAL_RECOIL.computeIfAbsent(entity, ignored -> new VisualRecoil());
            long gameTime = entity.m_9236_().m_46467_();
            if (recoilTime > recoil.lastRecoilTime + 1 || recoil.lastRecoilTime <= 0) {
                recoil.startTick = gameTime;
            }
            recoil.lastRecoilTime = recoilTime;

            float age = Math.max(0.0F, gameTime - recoil.startTick + partialTicks);
            float duration = entity instanceof Btr80AEntity ? 3.15F : 3.0F;
            if (age > duration) {
                return 0.0F;
            }
            float force = Math.max(0.0F, renderParts.getCannonRecoilForce());
            float multiplier = entity instanceof Btr80AEntity ? 3.25F
                    : entity instanceof ToyotaJihadSpg9Entity ? 2.6F
                    : entity instanceof ToyotaJihadDshkEntity ? 0.8F
                    : 2.4F;
            float kick;
            if (entity instanceof Btr80AEntity) {
                float backTime = 0.9F;
                kick = age <= backTime ? age / backTime : Math.max(0.0F, 1.0F - (age - backTime) / (duration - backTime));
            } else {
                kick = 1.0F - age / duration;
            }
            return force * multiplier * kick;
        }

        float force = Math.max(0.0F, renderParts.getCannonRecoilForce());
        float kick = Math.min(1.0F, recoilTime / 4.0F);
        return force * kick * 0.32F;
    }

    private static float bmptBarrelActivity(BmptEntity entity, int cannonRecoilTime) {
        int weapon = entity.getSelectedWeapon(0);
        if (weapon < 0 || weapon > 1) {
            return 0.0F;
        }
        return Mth.m_14036_((cannonRecoilTime - 26.0F) / 5.0F, 0.0F, 1.0F);
    }

    private static float bmptBarrelPhase(BmptEntity entity, float partialTicks) {
        boolean dual = entity.getSelectedWeapon(0) == 1;
        float renderTime = entity.m_9236_().m_46467_() + partialTicks;
        return renderTime * (dual ? 5.76F : 2.88F) + entity.m_19879_() * 0.37F;
    }

    private static float hmgRecoilOffset(ArmoredVehicleEntity entity, float partialTicks) {
        if (entity == null || entity.isTurretEjected()) {
            return 0.0F;
        }
        long pulse = BvpFiredVisualClient.passengerHmgSequence(entity);
        if (pulse <= 0) {
            HMG_VISUAL_RECOIL.remove(entity);
            return 0.0F;
        }

        HmgVisualRecoil recoil = HMG_VISUAL_RECOIL.computeIfAbsent(entity, ignored -> new HmgVisualRecoil());
        long gameTime = entity.m_9236_().m_46467_();
        if (pulse != recoil.lastPulse) {
            recoil.lastPulse = pulse;
            recoil.startTick = gameTime;
        }

        float age = Math.max(0.0F, gameTime - recoil.startTick + partialTicks);
        float duration = 4.0F;
        if (age >= duration) {
            return 0.0F;
        }

        float kick = age <= 0.75F
                ? age / 0.75F
                : Math.max(0.0F, 1.0F - (age - 0.75F) / (duration - 0.75F));
        float recoilUnits = "t72b".equals(entity.getArmorProfileId())
                ? T72B_NSVT_RECOIL_UNITS : DEFAULT_HMG_RECOIL_UNITS;
        return recoilUnits * kick;
    }

    private static final class VisualRecoil {
        int lastRecoilTime;
        long startTick = Long.MIN_VALUE;
    }

    private static final class HmgVisualRecoil {
        long lastPulse;
        long startTick = Long.MIN_VALUE;
    }
}
