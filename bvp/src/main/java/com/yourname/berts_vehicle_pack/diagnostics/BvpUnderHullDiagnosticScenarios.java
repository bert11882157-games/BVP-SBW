package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;

import com.atsuishio.superbwarfare.api.event.ProjectileHitEvent;
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.projectile.ProjectileEntity;
import com.atsuishio.superbwarfare.init.ModEntities;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/** Opt-in, bounded server checks for material hits and travel through vehicle clearance. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID)
public final class BvpUnderHullDiagnosticScenarios {
    private static Run active;

    private BvpUnderHullDiagnosticScenarios() { }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) return;
        event.getDispatcher().register(Commands.literal("bvp_underhull_test")
                .requires(source -> source.hasPermission(2))
                .executes(context -> run(context.getSource(), "leo2a6", 0))
                .then(Commands.argument("vehicle", StringArgumentType.word())
                        .executes(context -> run(context.getSource(),
                                StringArgumentType.getString(context, "vehicle"), 0))
                        .then(Commands.argument("yaw", FloatArgumentType.floatArg(-180, 180))
                                .executes(context -> run(context.getSource(),
                                        StringArgumentType.getString(context, "vehicle"),
                                        FloatArgumentType.getFloat(context, "yaw"))))));
    }

    private static int run(CommandSourceStack source, String vehicleId, float yaw)
            throws CommandSyntaxException {
        if (!vehicleId.equals("leo2a6") && !vehicleId.equals("m48a3_elite")) {
            throw new IllegalArgumentException("Expected leo2a6 or m48a3_elite");
        }
        if (active != null) throw new IllegalStateException("An under-hull check is already active");
        ServerPlayer observer = source.getPlayerOrException();
        EliteDiagnostics.INSTANCE.start(observer.server);
        Run run = new Run(observer, vehicleId, yaw);
        active = run;
        try {
            run.execute();
        } catch (RuntimeException exception) {
            run.check("scenario_completed_without_exception", false);
            run.record("ERROR", "exception", exception.toString());
        } finally {
            run.close();
            active = null;
            EliteDiagnostics.INSTANCE.stop(observer.server);
        }
        return run.failures == 0 ? 1 : 0;
    }

    @SubscribeEvent
    public static void hit(ProjectileHitEvent event) {
        if (active == null || event.getProjectile() != active.currentBullet) return;
        if (event instanceof ProjectileHitEvent.HitEntity entityHit) {
            active.entityImpacts++;
            if (entityHit.getTarget() == active.tank) active.tankImpacts++;
        } else if (event instanceof ProjectileHitEvent.HitBlock) {
            active.blockImpacts++;
        }
    }

    private static final class Run {
        private final ServerPlayer observer;
        private final ServerLevel level;
        private final String vehicleId;
        private final float yaw;
        private ArmoredVehicleEntity tank;
        private Cow downstream;
        private ProjectileEntity currentBullet;
        private BlockPos wall;
        private BlockState previousWall;
        private int entityImpacts;
        private int tankImpacts;
        private int blockImpacts;
        private int checks;
        private int failures;

        private Run(ServerPlayer observer, String vehicleId, float yaw) {
            this.observer = observer;
            this.level = observer.serverLevel();
            this.vehicleId = vehicleId;
            this.yaw = yaw;
        }

        private void execute() {
            record("STARTED", "vehicle_type", vehicleId, "yaw", yaw);
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(
                    new ResourceLocation(BertsVehiclePack.MODID, vehicleId));
            Entity created = type == null ? null : type.create(level);
            if (!(created instanceof ArmoredVehicleEntity armored)) {
                throw new IllegalStateException("Vehicle type unavailable: " + vehicleId);
            }
            tank = armored;
            tank.load(new CompoundTag());
            tank.moveTo(observer.getX() + 32, observer.getY() + 32, observer.getZ(), yaw, 0);
            tank.setNoGravity(true);
            level.getChunkAt(tank.blockPosition());
            if (!level.addFreshEntity(tank)) throw new IllegalStateException("Fixture spawn rejected");
            // Initialize the normal vehicle pose before testing within this one server command.
            tank.tick();
            tank.updateOBB();
            check("healthy_tank_fixture", tank.getHealth() > 0 && tank.getModuleHealth("lefttrack") > 0
                    && tank.getModuleHealth("righttrack") > 0);

            int ray = 0;
            for (double x : new double[]{-.5, 0, .5}) {
                for (double y : new double[]{.2, .25, .3}) {
                    for (int direction : new int[]{-1, 1}) {
                        String id = "gap_" + ray++;
                        float health = tank.getHealth();
                        float left = tank.getModuleHealth("lefttrack");
                        float right = tank.getModuleHealth("righttrack");
                        String plate = tank.getLastArmorHitPlate();
                        fire(new Vec3(x, y, -8 * direction), new Vec3(x, y, 8 * direction));
                        check(id + "_no_entity_impact", entityImpacts == 0);
                        check(id + "_no_hull_damage", tank.getHealth() == health);
                        check(id + "_no_track_damage", tank.getModuleHealth("lefttrack") == left
                                && tank.getModuleHealth("righttrack") == right);
                        check(id + "_no_plate_feedback", java.util.Objects.equals(plate, tank.getLastArmorHitPlate()));
                        check(id + "_projectile_continues", !currentBullet.isRemoved());
                        Vec3 segmentStart = world(new Vec3(x, y, -8 * direction));
                        Vec3 segmentEnd = world(new Vec3(x, y, 8 * direction));
                        AABB bounds = new AABB(segmentStart, segmentEnd).inflate(1);
                        check(id + "_level_collision_overload_misses", ProjectileUtil.getEntityHitResult(level,
                                currentBullet, segmentStart, segmentEnd, bounds, entity -> entity == tank, 0.0F) == null);
                        check(id + "_shooter_collision_overload_misses", ProjectileUtil.getEntityHitResult(
                                currentBullet, segmentStart, segmentEnd, bounds, entity -> entity == tank,
                                segmentStart.distanceToSqr(segmentEnd)) == null);
                        record("GAP_RESULT", "ray", id, "x", x, "y", y, "direction", direction,
                                "entity_impacts", entityImpacts, "hull_before", health, "hull_after", tank.getHealth());
                    }
                }
            }

            downstream = EntityType.COW.create(level);
            if (downstream == null) throw new IllegalStateException("Downstream target unavailable");
            Vec3 destination = world(new Vec3(0, .1, 10));
            downstream.moveTo(destination.x, destination.y, destination.z, 0, 0);
            downstream.setNoAi(true);
            downstream.setNoGravity(true);
            if (!level.addFreshEntity(downstream)) throw new IllegalStateException("Downstream spawn rejected");
            float downstreamHealth = downstream.getHealth();
            fire(new Vec3(0, .25, -8), new Vec3(0, .25, 16));
            check("target_beyond_gap_hit", downstream.getHealth() < downstreamHealth && entityImpacts > 0);
            check("target_beyond_gap_tank_missed", tankImpacts == 0);
            downstream.discard();
            downstream = null;

            wall = BlockPos.containing(world(new Vec3(0, .25, 10)));
            previousWall = level.getBlockState(wall);
            if (!previousWall.isAir()) throw new IllegalStateException("Wall fixture position is occupied");
            level.setBlock(wall, Blocks.STONE.defaultBlockState(), 3);
            fire(new Vec3(0, .25, -8), new Vec3(0, .25, 16));
            check("wall_beyond_gap_hit_same_tick", blockImpacts == 1);
            check("wall_beyond_gap_tank_missed", tankImpacts == 0);
            level.setBlock(wall, previousWall, 3);
            wall = null;
            previousWall = null;

            float hullBefore = tank.getHealth();
            fire(new Vec3(0, .1, 0), new Vec3(0, 3, 0));
            String bellyPlate = vehicleId.equals("leo2a6") ? "50mm_armor_69" : "make_100mm_288";
            check("real_belly_hit_retained", tankImpacts == 1 && tank.getLastArmorHitPlate().contains(bellyPlate));
            check("real_belly_blocks_coax_damage", tank.getHealth() == hullBefore);

            float tracksBefore = tank.getModuleHealth("lefttrack") + tank.getModuleHealth("righttrack");
            double trackX = -ArmorProfiles.get(vehicleId).trackBoxes.get(0).center.x;
            fire(new Vec3(trackX, .05, -8), new Vec3(trackX, .05, 8));
            check("real_track_hit_retained", tankImpacts == 1);
            check("real_track_takes_module_damage", tank.getModuleHealth("lefttrack")
                    + tank.getModuleHealth("righttrack") < tracksBefore);
        }

        private Vec3 world(Vec3 local) {
            return tank.getVehicleTransformSnapshot(1.0F).localToWorld(local);
        }

        private void fire(Vec3 localStart, Vec3 localEnd) {
            if (currentBullet != null) currentBullet.discard();
            entityImpacts = 0;
            tankImpacts = 0;
            blockImpacts = 0;
            Vec3 start = world(localStart);
            Vec3 end = world(localEnd);
            currentBullet = new ProjectileEntity(ModEntities.PROJECTILE.get(), level);
            currentBullet.setOwner(observer);
            currentBullet.setPos(start.x, start.y, start.z);
            currentBullet.setDeltaMovement(end.subtract(start));
            currentBullet.setGravity(0);
            currentBullet.setDamage(9.5F);
            ProjectileProfiles.assign(currentBullet, new ResourceLocation(BertsVehiclePack.MODID,
                    vehicleId + "/machinegun/ammo_00_western_762_ap"));
            check("typed_coax_profile_loaded", ProjectileProfiles.combatDescriptor(currentBullet) != null);
            // Tick the real projectile against registered world targets without retaining a test bullet.
            currentBullet.tick();
        }

        private void check(String name, boolean passed) {
            checks++;
            if (!passed) failures++;
            record(passed ? "ASSERT_PASS" : "ASSERT_FAIL", "check", name);
        }

        private void record(String event, Object... fields) {
            EliteDiagnostics.record(observer, "underhull", event, fields);
        }

        private void close() {
            if (currentBullet != null) currentBullet.discard();
            if (downstream != null) downstream.discard();
            if (tank != null) tank.discard();
            if (wall != null && previousWall != null) level.setBlock(wall, previousWall, 3);
            String status = failures == 0 ? "PASS" : "FAIL";
            record("COMPLETE", "vehicle_type", vehicleId, "yaw", yaw,
                    "status", status, "assertions", checks, "failures", failures);
            observer.sendSystemMessage(Component.literal(vehicleId + " under-hull diagnostics " + status + ": "
                    + checks + " assertions, " + failures + " failures."));
        }
    }
}
