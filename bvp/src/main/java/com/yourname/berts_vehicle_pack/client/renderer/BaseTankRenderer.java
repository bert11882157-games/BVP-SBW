package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.Map;
import java.util.WeakHashMap;

public class BaseTankRenderer<T extends GeoVehicleEntity> extends BaseTrackedVehicleRenderer<T> {
    private static final Map<GeoVehicleEntity, HullRecoilState> HULL_RECOIL = new WeakHashMap<>();
    private static final int MIN_TANK_CANNON_RECOIL_TIME = 36;
    private static final float HULL_RECOIL_RETURN_TICKS = 4.0F;
    private static final double HULL_RECOIL_DISTANCE = 0.08D;
    private static final float HULL_RECOIL_PITCH_DEGREES = 1.0F;
    private static final float DEGREES_TO_RADIANS = (float) (Math.PI / 180.0D);

    protected BaseTankRenderer(EntityRendererProvider.Context context, ResourceLocation modelLocation,
                               ResourceLocation textureLocation,
                               int roadWheelCount, float trackYCenter, float trackRadius, float trackZRear,
                               float trackZFront, String debugName) {
        super(context, modelLocation, textureLocation, roadWheelCount, trackYCenter,
                trackRadius, trackZRear, trackZFront, debugName);
    }

    protected BaseTankRenderer(EntityRendererProvider.Context context, ResourceLocation modelLocation,
                               ResourceLocation textureLocation,
                               int roadWheelCount, float trackYCenter, float trackRadius, float trackZRear,
                               float trackZFront, int trackLinkCount, String debugName) {
        super(context, modelLocation, textureLocation, roadWheelCount, trackYCenter,
                trackRadius, trackZRear, trackZFront, trackLinkCount, debugName);
    }

    @Override
    protected void applyAdditionalVehicleTransform(T entity, float partialTicks, PoseStack poseStack) {
        if (entity instanceof ArmoredVehicleEntity armored && armored.isWreck()) {
            HULL_RECOIL.remove(entity);
            return;
        }
        float kick = hullRecoilKick(entity, partialTicks);
        HullRecoilState state = HULL_RECOIL.get(entity);
        if (kick <= 0.0F || state == null) {
            return;
        }

        poseStack.m_85837_(
                -state.barrelDirectionX * HULL_RECOIL_DISTANCE * kick,
                -state.barrelDirectionY * HULL_RECOIL_DISTANCE * kick,
                -state.barrelDirectionZ * HULL_RECOIL_DISTANCE * kick);
        double horizontalLength = Math.sqrt(
                state.barrelDirectionX * state.barrelDirectionX
                        + state.barrelDirectionZ * state.barrelDirectionZ);
        if (horizontalLength > 1.0E-6D) {
            float axisX = (float) (-state.barrelDirectionZ / horizontalLength);
            float axisZ = (float) (state.barrelDirectionX / horizontalLength);
            Quaternionf rock = state.rock.rotationAxis(
                    HULL_RECOIL_PITCH_DEGREES * kick * DEGREES_TO_RADIANS,
                    axisX, 0.0F, axisZ);
            poseStack.m_272245_(rock, 0.0F, (float) entity.getRotateOffsetHeight(), 0.0F);
        }
    }

    private static float hullRecoilKick(GeoVehicleEntity entity, float partialTicks) {
        int recoilTime = entity.getCannonRecoilTime();
        HullRecoilState state = HULL_RECOIL.get(entity);
        if (state == null) {
            if (recoilTime < MIN_TANK_CANNON_RECOIL_TIME) {
                return 0.0F;
            }
            state = new HullRecoilState();
            HULL_RECOIL.put(entity, state);
        }

        double renderTime = (double) entity.m_9236_().m_46467_() + partialTicks;
        if (recoilTime >= MIN_TANK_CANNON_RECOIL_TIME
                && (state.lastRecoilTime <= 0 || recoilTime > state.lastRecoilTime + 1)) {
            state.startTime = renderTime;
            captureBarrelDirection(entity, partialTicks, state);
        }
        state.lastRecoilTime = recoilTime;

        double age = renderTime - state.startTime;
        if (age < 0.0F || age >= HULL_RECOIL_RETURN_TICKS) {
            if (recoilTime < MIN_TANK_CANNON_RECOIL_TIME) {
                HULL_RECOIL.remove(entity);
            }
            return 0.0F;
        }
        float remaining = (float) (1.0D - age / HULL_RECOIL_RETURN_TICKS);
        return remaining * remaining * (3.0F - 2.0F * remaining);
    }

    private static void captureBarrelDirection(GeoVehicleEntity entity, float partialTicks, HullRecoilState state) {
        Vec3 barrelDirection = entity.getBarrelVector(partialTicks);
        double length = Math.sqrt(
                barrelDirection.f_82479_ * barrelDirection.f_82479_
                        + barrelDirection.f_82480_ * barrelDirection.f_82480_
                        + barrelDirection.f_82481_ * barrelDirection.f_82481_);
        if (length <= 1.0E-6D) {
            state.barrelDirectionX = 0.0D;
            state.barrelDirectionY = 0.0D;
            state.barrelDirectionZ = 0.0D;
            return;
        }

        // This transform runs before hull rotation because SBW's barrel vector is already world-space.
        state.barrelDirectionX = barrelDirection.f_82479_ / length;
        state.barrelDirectionY = barrelDirection.f_82480_ / length;
        state.barrelDirectionZ = barrelDirection.f_82481_ / length;
    }

    private static final class HullRecoilState {
        final Quaternionf rock = new Quaternionf();
        int lastRecoilTime;
        double startTime = Double.NEGATIVE_INFINITY;
        double barrelDirectionX;
        double barrelDirectionY;
        double barrelDirectionZ;
    }
}
