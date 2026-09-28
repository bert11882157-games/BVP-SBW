package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.example.sbwmeshloader.core.PolyMeshModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;

/**
 * 9P149 Shturm-S launcher reload (owner's brief, 2026-09-28), driven by the launcher's own reload timer so the last
 * frame is the last reload tick:
 *
 * <ol>
 * <li>shot: the fired tube is thrown off the arm, tumbles under gravity, lands beside the hull, bounces once and lies
 *     there for a few seconds before it sinks away; the arm is left empty;</li>
 * <li>the roof hatch beside the pole swings open (under it: a black well, invisible while the hatch is closed);</li>
 * <li>the arm turns back to straight ahead, then pole, mount and plate roll 90 degrees toward the hatch and sink
 *     a little into the hull, the angled plate folding flat onto the roof;</li>
 * <li>a new tube appears on the arm while it is down;</li>
 * <li>(the same in reverse, ending exactly with the reload) the arm rolls back up, turns back to where the operator
 *     aims, and the hatch closes.</li>
 * </ol>
 *
 * Bones (tools/vehgen/shturm_reload_rig.py): {@code turret} (pole; yaw axis = pole axis, pivot at the pole foot),
 * {@code barell} (tube), {@code launcher_tube_spent} (hull child, pivot on the pole axis at the mount, so it keeps
 * the aim it was fired at while the pole swings), {@code launcher_plate} (hull child hinged at its roof edge),
 * {@code reload_hatch} (hinged on its outer edge). Model space in the renderer is the geo frame with x mirrored;
 * a positive Z rotation there turns a bone's top toward geo +x, the hatch side of the pole.
 */
final class LauncherReloadAnimator {
    /** Nominal reload the timing is laid out for (WT 9P149: 8.33 s); shorter reloads compress every phase. */
    private static final float NOMINAL_SECONDS = 8.35F;
    private static final float ROLL_DEG = 90.0F;
    private static final float SINK_PX = 3.0F;
    private static final float PLATE_FOLD_DEG = 53.9F;
    private static final float PLATE_RAISE_PX = 1.65F;
    private static final float HATCH_OPEN_DEG = 115.0F;
    /** Plate hinge to pole axis in model space (geo (8.95, 0.9, 0.02) with x mirrored). */
    private static final float PLATE_TO_AXIS_X = -8.95F, PLATE_TO_AXIS_Z = 0.02F;

    /** Spent tube flight, model px and seconds: thrown outward (+x), up and slightly back, under real gravity. */
    private static final float THROW_OUT = 35.0F, THROW_UP = 45.0F, THROW_BACK = 8.0F;
    private static final float GRAVITY = 157.0F;          // 9.8 m/s^2, 16 px per metre
    private static final float REST_DROP = 43.2F;         // pivot height above the ground when the tube lies flat
    private static final float TUMBLE_DEG_PER_S = 260.0F;
    private static final float LIE_UNTIL = 6.0F;          // seconds after the shot the tube starts to sink away
    private static final float SINK_SECONDS = 0.6F;
    /** Aim (yaw, pitch) each launcher had when its tube was thrown, so the spent tube keeps it. */
    private static final java.util.Map<VehicleEntity, float[]> THROWN = new java.util.WeakHashMap<>();

    private LauncherReloadAnimator() {
    }

    static boolean applies(PolyMeshModel model) {
        return model.getBone("reload_hatch") != null && model.getBone("launcher_plate") != null
                && model.getBone("launcher_tube_spent") != null;
    }

    static void apply(VehicleEntity entity, PolyMeshModel model, float partialTicks) {
        BedrockBone turret = model.getBone("turret");
        BedrockBone barrel = model.getBone("barell");
        BedrockBone spent = model.getBone("launcher_tube_spent");
        BedrockBone plate = model.getBone("launcher_plate");
        BedrockBone hatch = model.getBone("reload_hatch");
        if (turret == null || barrel == null) return;
        GunData gun = entity.getGunData(0, 0);
        float yawRad = turret.rotationInEuler.y();
        float pitchRad = barrel.rotationInEuler.x();

        float total = 0.0F, elapsed = -1.0F;
        int ammo = 1;
        if (gun != null) {
            ammo = gun.ammo.get();
            if (gun.reloading()) {
                total = gun.reload.total() > 0 ? gun.reload.total() : gun.get(GunProp.EMPTY_RELOAD_TIME);
                float remaining = Math.max(0.0F, gun.reload.time() - partialTicks);
                elapsed = Math.max(0.0F, total - remaining);
            }
        }
        Pose pose = elapsed >= 0.0F && total > 0.0F
                ? pose(elapsed / 20.0F, total / 20.0F)
                : ammo > 0 ? Pose.REST : Pose.EMPTY;

        // pole + mount: aim blended toward straight ahead, then the roll toward the hatch and the sink
        RendererBones.setRotation(turret, 0.0F, yawRad * (1.0F - pose.align),
                pose.roll * ROLL_DEG * RendererBones.DEG_TO_RAD);
        RendererBones.setPositionOffset(turret, 0.0F, -pose.roll * SINK_PX, 0.0F);
        if (pose.tube) {
            RendererBones.show(barrel);
            RendererBones.setRotation(barrel, pitchRad * (1.0F - pose.align), 0.0F, 0.0F);
        } else {
            RendererBones.hide(barrel);
        }

        // the fired tube: thrown up and out, tumbling under gravity, lands beside the hull, lies there, sinks away
        if (spent != null) {
            float s = elapsed >= 0.0F && total > 0.0F ? elapsed / 20.0F : -1.0F;
            float life = Math.min(LIE_UNTIL + SINK_SECONDS, total / 20.0F);
            if (s >= 0.0F && s < life) {
                float[] aim = THROWN.get(entity);
                if (aim == null || s < aim[2]) {
                    // a new shot: remember the aim the tube left with (the pole only starts to turn at 0.6 s)
                    aim = new float[]{yawRad, pitchRad, s};
                    THROWN.put(entity, aim);
                }
                aim[2] = s;
                spentTube(spent, aim[0], aim[1], s);
            } else {
                RendererBones.hide(spent);
                if (s < 0.0F) THROWN.remove(entity);
            }
        }

        // the angled plate: follows the pole's yaw about the pole axis, folds flat onto the roof while it is down
        if (plate != null) {
            float psi = yawRad * (1.0F - pose.align);
            float c = (float) Math.cos(psi), s = (float) Math.sin(psi);
            float dx = PLATE_TO_AXIS_X, dz = PLATE_TO_AXIS_Z;
            float offX = dx - (dx * c + dz * s);
            float offZ = dz - (-dx * s + dz * c);
            RendererBones.setPositionOffset(plate, offX, pose.roll * PLATE_RAISE_PX, offZ);
            RendererBones.setRotation(plate, 0.0F, psi, pose.roll * PLATE_FOLD_DEG * RendererBones.DEG_TO_RAD);
        }
        if (hatch != null) {
            RendererBones.setPositionOffset(hatch, 0.0F, 0.0F, 0.0F);
            RendererBones.setRotation(hatch, 0.0F, 0.0F, pose.hatch * HATCH_OPEN_DEG * RendererBones.DEG_TO_RAD);
        }
    }

    /** The spent tube {@code s} seconds after the shot: ballistic flight, one small bounce, lying flat, sinking. */
    static void spentTube(BedrockBone spent, float yaw, float pitch, float s) {
        float land = (THROW_UP + (float) Math.sqrt(THROW_UP * THROW_UP + 2.0F * GRAVITY * REST_DROP)) / GRAVITY;
        float x, y, z, tumble;
        if (s < land) {
            x = THROW_OUT * s;
            y = THROW_UP * s - 0.5F * GRAVITY * s * s;
            z = THROW_BACK * s;
            tumble = TUMBLE_DEG_PER_S * s;
        } else {
            float after = s - land;
            float bounce = after < 0.3F ? 12.0F * after - 40.0F * after * after : 0.0F;   // a short hop
            x = THROW_OUT * land + 6.0F * Math.min(after, 0.3F);
            y = -REST_DROP + Math.max(0.0F, bounce);
            z = THROW_BACK * land + 2.0F * Math.min(after, 0.3F);
            // it comes to rest lying flat, turned the way it tumbled
            float landed = TUMBLE_DEG_PER_S * land;
            float flat = Math.round(landed / 180.0F) * 180.0F;
            tumble = landed + (flat - landed) * Math.min(1.0F, after / 0.3F);
            if (s > LIE_UNTIL) y -= 3.0F * Math.min(1.0F, (s - LIE_UNTIL) / SINK_SECONDS);
        }
        float settle = s < land ? 1.0F : Math.max(0.0F, 1.0F - (s - land) / 0.3F);
        RendererBones.setPositionOffset(spent, x, y, z);
        RendererBones.setRotation(spent, pitch * settle - tumble * RendererBones.DEG_TO_RAD, yaw,
                -0.4F * tumble * RendererBones.DEG_TO_RAD * settle);
    }

    /** The pose {@code s} seconds into a reload of {@code total} seconds. */
    static Pose pose(float s, float total) {
        float k = Math.min(1.0F, total / NOMINAL_SECONDS);
        float end = total;
        float throwEnd = 0.9F * k;
        float hatch = Math.min(ramp(s, 0.25F * k, 1.15F * k), 1.0F - ramp(s, end - 1.0F * k, end));
        float align = Math.min(ramp(s, 0.6F * k, 1.4F * k), 1.0F - ramp(s, end - 1.3F * k, end - 0.9F * k));
        float roll = Math.min(ramp(s, 1.4F * k, 3.0F * k), 1.0F - ramp(s, end - 3.0F * k, end - 1.3F * k));
        boolean tube = s >= 3.3F * k;
        float throwProgress = s < throwEnd ? s / throwEnd : 1.0F;
        return new Pose(hatch, align, roll, tube, throwProgress);
    }

    /** Smoothstep from 0 at {@code a} to 1 at {@code b}. */
    static float ramp(float s, float a, float b) {
        if (s <= a) return 0.0F;
        if (s >= b) return 1.0F;
        float t = (s - a) / (b - a);
        return t * t * (3.0F - 2.0F * t);
    }

    record Pose(float hatch, float align, float roll, boolean tube, float throwProgress) {
        static final Pose REST = new Pose(0.0F, 0.0F, 0.0F, true, 1.0F);
        static final Pose EMPTY = new Pose(0.0F, 0.0F, 0.0F, false, 1.0F);
    }
}
