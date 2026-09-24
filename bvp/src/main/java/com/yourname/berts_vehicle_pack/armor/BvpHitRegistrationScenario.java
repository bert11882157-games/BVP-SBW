package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.atsuishio.superbwarfare.data.gun.ShootParameters;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.tools.OBB;
import com.atsuishio.superbwarfare.world.phys.ProjectileHitSelection;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.entity.EntityKineticBullet;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.diagnostics.BvpFireTrafficControl;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.Vec;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One real projectile per disposable fixture; the normal collision and damage paths own outcomes. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID)
public final class BvpHitRegistrationScenario {
    private static Run active;
    private BvpHitRegistrationScenario() { }

    private static boolean admitted(ServerPlayer player) {
        var server = player.server;
        return DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")
                && server.isDedicatedServer() && !server.usesAuthentication()
                && "127.0.0.1".equals(server.getLocalIp()) && server.getPort() == BvpFireTrafficControl.PORT
                && server.getPlayerCount() == 1 && player.level().dimension() == Level.OVERWORLD
                && player.isAlive() && !player.isSpectator() && player.getAbilities().instabuild
                && player.getVehicle() == null
                && BvpFireTrafficControl.identity(player.getGameProfile().getName(), player.getUUID())
                && BvpFireTrafficControl.loopback(player.connection.connection.getRemoteAddress(), false);
    }

    static Vec3 turretOutwardVehicleLocal(ArmorTarget coordinates, Vec hullPoint, boolean front) {
        Vec armorOutward = new Vec(0, 0, front ? -1 : 1);
        Vec hullOutward = armorOutward.rotateY(coordinates.turretFrameYaw());
        return coordinates.armorLocalPointToVehicleLocal(hullPoint.add(hullOutward))
                .subtract(coordinates.armorLocalPointToVehicleLocal(hullPoint)).normalize();
    }

    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) return;
        event.getDispatcher().register(Commands.literal("bvp_hitreg").requires(s -> s.hasPermission(2))
                .then(Commands.literal("stop").executes(c -> {
                    if (active == null || c.getSource().getEntity() != active.player) return 0;
                    active.close("ABORTED"); return 1;
                }))
                .then(Commands.argument("case", StringArgumentType.greedyString()).executes(c -> {
                    ServerPlayer player = c.getSource().getPlayerOrException();
                    if (active != null || !admitted(player) || EliteDiagnostics.isServerEnabled()) return 0;
                    String[] args = StringArgumentType.getString(c, "case").trim().split("\\s+");
                    if (args.length != 6 || !Set.of("leo2a6", "t72b", "m48a3_elite", "native_t90").contains(args[0])
                            || !Set.of("shell", "bullet", "tacz", "rpg", "tow", "rocket").contains(args[1])
                            || !Set.of("left", "right", "front", "rear", "oblique_left", "oblique_right", "hull", "miss", "gap", "turret_front", "turret_rear").contains(args[2])
                            || !Set.of("12", "80").contains(args[3])
                            || !Set.of("stationary", "moving").contains(args[4])
                            || !Set.of("clear", "wall", "splash").contains(args[5])) {
                        player.sendSystemMessage(Component.literal("Usage: bvp_hitreg <leo2a6|t72b|m48a3_elite|native_t90> <shell|bullet|tacz|rpg|tow|rocket> <left|right|front|rear|oblique_left|oblique_right|hull|miss|gap|turret_front|turret_rear> <12|80> <stationary|moving> <clear|wall|splash>"));
                        return 0;
                    }
                    if (args[2].startsWith("turret_") && !(args[0].equals("t72b")
                            && Set.of("rpg", "tow", "rocket").contains(args[1]) && args[3].equals("12")
                            && args[4].equals("stationary") && args[5].equals("clear"))) {
                        player.sendSystemMessage(Component.literal("Turret ERA acceptance requires t72b rpg|tow|rocket turret_front|turret_rear 12 stationary clear."));
                        return 0;
                    }
                    if (args[5].equals("splash") && !(args[0].equals("native_t90")
                            && args[1].equals("rpg") && Set.of("left", "right").contains(args[2])
                            && args[3].equals("12") && args[4].equals("stationary"))) {
                        player.sendSystemMessage(Component.literal("Splash acceptance requires native_t90 rpg left|right 12 stationary splash."));
                        return 0;
                    }
                    if (args[2].equals("gap") && !(Set.of("t72b", "native_t90").contains(args[0])
                            && args[1].equals("tacz") && args[3].equals("12")
                            && args[4].equals("stationary") && args[5].equals("clear"))) {
                        player.sendSystemMessage(Component.literal("Gap acceptance requires t72b|native_t90 tacz gap 12 stationary clear."));
                        return 0;
                    }
                    active = new Run(player, args);
                    try { active.prepare(); }
                    catch (RuntimeException error) { active.record("ERROR", "error", error.toString()); active.close("ERROR"); return 0; }
                    return 1;
                })));
    }

    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (active == null || event.phase != TickEvent.Phase.END || event.getServer() != active.player.server) return;
        try {
            if (!admitted(active.player)) { active.close("ABORTED"); return; }
            active.tick();
        } catch (RuntimeException error) {
            active.record("ERROR", "error", error.toString());
            active.close("ERROR");
        }
    }

    @SubscribeEvent public static void stop(ServerStoppingEvent event) {
        if (active != null && event.getServer() == active.player.server) active.close("ABORTED");
    }

    private static final class Run {
        final ServerPlayer player;
        final ServerLevel level;
        final String[] args;
        final UUID run = UUID.randomUUID();
        final Vec3 savedPosition;
        final Vec3 savedMotion;
        final float savedYaw, savedPitch;
        final boolean savedFlying;
        final ItemStack savedHand;
        final List<Entity> owned = new ArrayList<>();
        final Set<ChunkPos> chunks = new HashSet<>();
        final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        final Set<BlockPos> wallBlocks = new HashSet<>();
        VehicleEntity target, weapon, neighbor;
        Vec3 muzzle, aim;
        double leftBefore, rightBefore, hullBefore;
        int turretEraBefore;
        double neighborLeftBefore, neighborRightBefore, neighborHullBefore;
        Vec3 targetPositionBefore;
        int ticks, shots, failures;

        Run(ServerPlayer player, String[] args) {
            this.player = player; this.level = player.serverLevel(); this.args = args;
            savedPosition = player.position(); savedYaw = player.getYRot(); savedPitch = player.getXRot();
            savedMotion = player.getDeltaMovement();
            savedFlying = player.getAbilities().flying; savedHand = player.getMainHandItem().copy();
        }

        void prepare() {
            EliteDiagnostics.INSTANCE.startForEntities(player.server, Set.of(player.getUUID()));
            Vec3 origin = new Vec3(Math.floor(savedPosition.x) + 32, 220, Math.floor(savedPosition.z));
            for (int x = -9; x <= 9; x++) for (int z = -9; z <= 9; z++) {
                put(BlockPos.containing(origin.add(x, -1, z)), Blocks.BEDROCK.defaultBlockState());
            }
            target = spawn(args[0], origin);
            targetPositionBefore = target.position();
            if (args[4].equals("moving")) {
                var driver = EntityType.COW.create(level);
                if (driver == null) throw new IllegalStateException("Moving fixture driver unavailable");
                owned.add(driver); driver.setNoAi(true); driver.setInvulnerable(true);
                driver.setPos(target.position());
                if (!level.addFreshEntity(driver) || !driver.startRiding(target, true)
                        || target.getFirstPassenger() != driver)
                    throw new IllegalStateException("Moving fixture driver boarding failed");
                target.setEnergy(target.getMaxEnergy());
            }
            weapon = spawn(args[1].equals("tow") ? "tow_tripod"
                    : args[1].equals("rocket") ? "bm_21_grad" : "leo2a6",
                    origin.add(0, 0, 25));
            // A smaller native vehicle gives a separated, unoccluded witness inside the RPG's
            // three-block origin-distance radius. Both vehicles keep their authored geometry.
            if (args[5].equals("splash")) neighbor = spawn("native_lav150",
                    origin.add(args[2].equals("right") ? -4.2 : 4.2, 0, -3.92));
            record("CASE_STARTED", "case", String.join(" ", args), "target", target.getUUID(),
                    "method", "SBW GunData.shootWithResult or TacZ IGunOperator.shoot; no direct damage/collision invocation");
        }

        void tick() {
            if (ticks == 15) positionShot();
            if (args[4].equals("moving")) {
                // Drive through the ordinary occupied vehicle engine; a velocity seed is
                // discarded by ground physics and does not establish a moving target.
                target.setForwardInputDown(ticks >= 15 && ticks < 65
                        && target.getDeltaMovement().horizontalDistance() < .10);
                target.setBackInputDown(false); target.setLeftInputDown(false); target.setRightInputDown(false);
                target.setUpInputDown(ticks >= 65); target.setSprintInputDown(false);
            }
            if (ticks >= 15 && ticks < 55 && tacz()) IGunOperator.fromLivingEntity(player).aim(true);
            if (ticks == 55) fire();
            if (ticks == 105) {
                record("HEALTH_RESULT", "target", target.getUUID(), "shots", shots,
                        "left_before", leftBefore, "left_after", trackHealth(target, true),
                        "right_before", rightBefore, "right_after", trackHealth(target, false),
                        "hull_before", hullBefore, "hull_after", target.getHealth(),
                        "target_position", target.position());
                check("one_projectile_created", shots == 1);
                if (args[2].startsWith("turret_")) {
                    int after = spentTurretEra();
                    record("ERA_RESULT", "target", target.getUUID(), "front_turret_spent_before",
                            turretEraBefore, "front_turret_spent_after", after);
                    check(args[2].equals("turret_rear") ? "rear_did_not_spend_front_turret_era"
                            : "front_spent_local_turret_era",
                            args[2].equals("turret_rear") ? after == turretEraBefore
                                    : after - turretEraBefore >= 2);
                }
                if (args[5].equals("wall") || Set.of("miss", "gap").contains(args[2])) {
                    check("occluded_or_missed_target_unchanged", target.getHealth() == hullBefore
                            && trackHealth(target, true) == leftBefore
                            && trackHealth(target, false) == rightBefore);
                } else if (args[0].equals("native_t90") && args[1].equals("bullet")) {
                    // This exact 9.5-damage round is absorbed by native T-90A projectile immunity
                    // and its flat 20-point reduction. Contact selection still requires trace review.
                    check("native_rifle_damage_absorbed", target.getHealth() == hullBefore
                            && trackHealth(target, true) == leftBefore
                            && trackHealth(target, false) == rightBefore);
                } else if (args[0].equals("native_t90") && args[1].equals("rpg")) {
                    // Native flat mitigation absorbs the RPG's 20 direct damage. Its explosion
                    // may damage hulls, but must never reuse the direct wheel contact.
                    check("native_rpg_tracks_unchanged", trackHealth(target, true) == leftBefore
                            && trackHealth(target, false) == rightBefore);
                } else if (args[3].equals("12") && args[4].equals("stationary")
                        && Set.of("left", "right").contains(args[2])) {
                    boolean left = args[2].equals("left");
                    check("intended_track_damaged", trackHealth(target, left)
                            < (left ? leftBefore : rightBefore));
                    check("opposite_track_unchanged", trackHealth(target, !left)
                            == (left ? rightBefore : leftBefore));
                }
                if (args[4].equals("moving")) check("target_actually_moved", target.position().distanceToSqr(targetPositionBefore) > .01);
                if (neighbor != null) {
                    record("SPLASH_RESULT", "neighbor", neighbor.getUUID(), "hull_before", neighborHullBefore,
                            "hull_after", neighbor.getHealth(), "left_before", neighborLeftBefore,
                            "left_after", trackHealth(neighbor, true), "right_before", neighborRightBefore,
                            "right_after", trackHealth(neighbor, false));
                    check("neighbor_received_splash", neighbor.getHealth() < neighborHullBefore);
                    check("neighbor_tracks_not_copied", trackHealth(neighbor, true) == neighborLeftBefore
                            && trackHealth(neighbor, false) == neighborRightBefore);
                }
                close(failures == 0 ? "CAPTURED_REVIEW_CONTACT_TRACE" : "FAIL");
                return;
            }
            ticks++;
        }

        void positionShot() {
            ArmorTarget coordinates = ArmorTargetAdapters.resolve(target);
            boolean right = args[2].contains("right");
            String boxName;
            Vec3 turretOutward = null;
            if (coordinates == null) {
                var box = target.getOBBs().stream().filter(b -> b.part == (args[2].equals("hull") ? OBB.Part.BODY
                        : right ? OBB.Part.WHEEL_RIGHT : OBB.Part.WHEEL_LEFT)).findFirst().orElseThrow();
                aim = OBB.vector3dToVec3(box.center);
                boxName = box.part.name();
            } else {
            var profile = ArmorProfiles.get(args[0]);
            if (args[2].startsWith("turret_")) {
                Vec turretPoint = args[2].equals("turret_front")
                        ? profile.eraBoxes.stream().filter(b -> b.isTurretFrame()
                                && b.name.equals("newera_76")).findFirst().orElseThrow().center
                        : new Vec(0.35425D, 2.07241D, 1.6D);
                Vec pivot = coordinates.turretPivot();
                Vec hullPoint = pivot.add(turretPoint.subtract(pivot).rotateY(coordinates.turretFrameYaw()));
                aim = coordinates.armorLocalPointToWorld(hullPoint);
                // The armor source may mirror Z relative to vehicle-local space.
                // Derive the approach side in the same frame as the authored box.
                turretOutward = turretOutwardVehicleLocal(coordinates, hullPoint,
                        args[2].equals("turret_front"));
                boxName = args[2].equals("turret_front") ? "newera_76" : "rear_turret_clear_of_front_era";
            } else {
            // Side is vehicle-local +X left / -X right, independent of profile mirroring or labels.
            var box = profile.trackBoxes.stream().filter(b ->
                    (coordinates.armorLocalPointToVehicleLocal(b.center).x > 0) != right)
                    .min(Comparator.comparingDouble(b -> Math.abs(coordinates.armorLocalPointToVehicleLocal(b.center).z)))
                    .orElseThrow(() -> new IllegalStateException("No authored track for requested side"));
            aim = coordinates.armorLocalPointToWorld(box.center);
            boxName = box.name;
            if (args[2].equals("hull")) {
                var plate = profile.plates.stream().filter(b -> !b.isTurretFrame() && !b.isBarrelFrame())
                        .min(Comparator.comparingDouble(b -> Math.abs(coordinates.armorLocalPointToVehicleLocal(b.center).z)))
                        .orElseThrow();
                aim = coordinates.armorLocalPointToWorld(plate.center);
                boxName = plate.name;
            }
            }
            }
            Vec3 localAim = target.worldToVehicleLocal(aim, 1);
            if (neighbor != null) {
                // Approach the front end of the requested track outside the hull's lateral
                // extent, with the native witness alongside and clear of the projectile ray.
                localAim = new Vec3(right ? -2.29 : 2.29, .79, -3.52);
                aim = target.vehicleLocalToWorld(localAim, 1);
            }
            Vec3 outward = new Vec3(right ? -1 : 1, 0, 0);
            if (neighbor != null) outward = new Vec3(0, 0, -1);
            if (args[2].equals("front")) outward = new Vec3(0, 0, -1);
            if (args[2].equals("rear")) outward = new Vec3(0, 0, 1);
            if (turretOutward != null) outward = turretOutward;
            if (args[2].startsWith("oblique")) outward = new Vec3(right ? -1 : 1, 0, -1).normalize();
            if (args[2].equals("miss")) {
                // A clear geometric control. Non-detailed OBB envelope-gap witnesses need trace review.
                aim = aim.add(0, target.getBoundingBox().getYsize() + 2, 0);
                localAim = target.worldToVehicleLocal(aim, 1);
            }
            muzzle = target.vehicleLocalToWorld(localAim.add(outward.scale(Integer.parseInt(args[3]))), 1);
            if (args[2].equals("gap")) findEnvelopeGap();
            force(muzzle.x, muzzle.z);
            prepareObserverSupport();
            Vec3 direction = aim.subtract(muzzle);
            positionObserver();
            if (tacz()) {
                var id = new ResourceLocation("tacz", args[1].equals("rpg") ? "rpg7" : "ak47");
                player.setItemInHand(InteractionHand.MAIN_HAND, GunItemBuilder.create().setId(id)
                        .setAmmoCount(1).setAmmoInBarrel(true).setFireMode(FireMode.SEMI).build());
                var operator = IGunOperator.fromLivingEntity(player);
                operator.initialData(); operator.draw(player::getMainHandItem);
            }
            if (args[5].equals("wall")) {
                BlockPos center = BlockPos.containing(muzzle.add(direction.scale(.5)));
                for (int x = -2; x <= 2; x++) for (int y = -2; y <= 2; y++) for (int z = -2; z <= 2; z++) {
                    BlockPos block = center.offset(x, y, z);
                    put(block, Blocks.BEDROCK.defaultBlockState()); wallBlocks.add(block.immutable());
                }
            }
            record("AIM", "target", target.getUUID(), "authored_box", boxName,
                    "muzzle", muzzle, "aim", aim, "target_position", target.position(),
                    "range_blocks", Integer.parseInt(args[3]), "nominal_side", right ? "right" : "left");
        }

        void fire() {
            leftBefore = trackHealth(target, true); rightBefore = trackHealth(target, false);
            hullBefore = target.getHealth();
            if (args[2].startsWith("turret_")) turretEraBefore = spentTurretEra();
            if (args[4].equals("moving")) {
                double moved = target.position().distanceToSqr(targetPositionBefore);
                double speed = target.getDeltaMovement().horizontalDistance();
                record("MOTION_AT_FIRE", "target", target.getUUID(), "position", target.position(),
                        "velocity", target.getDeltaMovement(), "displacement_squared", moved,
                        "driver", target.getFirstPassenger() == null ? null : target.getFirstPassenger().getUUID());
                if (moved <= .01 || speed <= .01)
                    throw new IllegalStateException("Target did not establish real motion before firing");
            }
            if (neighbor != null) {
                neighborLeftBefore = trackHealth(neighbor, true); neighborRightBefore = trackHealth(neighbor, false);
                neighborHullBefore = neighbor.getHealth();
                boolean separated = !collisionBounds(target).intersects(collisionBounds(neighbor));
                record("SPLASH_GEOMETRY", "target", target.getUUID(), "neighbor", neighbor.getUUID(),
                        "neighbor_type", ForgeRegistries.ENTITY_TYPES.getKey(neighbor.getType()),
                        "target_bounds", collisionBounds(target), "neighbor_bounds", collisionBounds(neighbor),
                        "neighbor_position", neighbor.position(), "separated", separated);
                if (!separated) throw new IllegalStateException("Splash vehicles overlap; no valid witness");
            }
            if (tacz()) {
                // The real gun samples the observer's previous/current position and supplied aim.
                // Re-establish the supported fixture pose before launch; never reposition the shot.
                positionObserver();
                check("observer_at_muzzle", player.getEyePosition().distanceToSqr(muzzle) < .0025);
                Vec3 direction = aim.subtract(muzzle);
                float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
                float pitch = (float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z)));
                Set<UUID> before = new HashSet<>();
                for (Entity entity : level.getAllEntities()) if (entity instanceof EntityKineticBullet) before.add(entity.getUUID());
                check("gun_operator_accepted", IGunOperator.fromLivingEntity(player).shoot(() -> pitch, () -> yaw) == ShootResult.SUCCESS);
                for (Entity entity : level.getAllEntities()) if (entity instanceof EntityKineticBullet
                        && !before.contains(entity.getUUID())) shot(entity);
            } else {
                String name = Set.of("shell", "tow").contains(args[1]) ? "Cannon"
                        : args[1].equals("rocket") ? "Rocket" : "MachineGun";
                weapon.modifyGunData(name, data -> {
                    data.resetStatus(); data.reload.setPendingProgressPercent(0); data.ammo.set(10);
                    data.selectedAmmoType.set(0); data.projectileBeltPhase.set(0);
                    data.virtualAmmo.set(0); data.heat.set(0); data.overHeat.set(false);
                });
                var data = weapon.getGunData(name);
                var parameters = new ShootParameters(weapon, player, level, muzzle, aim.subtract(muzzle).normalize(),
                        data, 0.0, true, null, null, true, null, null, null, null, null, null);
                var result = data.shootWithResult(parameters);
                check("sbw_gun_factory_accepted", result.isAccepted());
                for (UUID id : result.getSpawnedProjectileIds()) {
                    Entity entity = level.getEntity(id);
                    if (entity instanceof Projectile) shot(entity);
                }
            }
        }

        int spentTurretEra() {
            if (!(target instanceof ArmoredVehicleEntity armored)) return -1;
            return (int) ArmorProfiles.get("t72b").eraBoxes.stream().filter(box -> box.isTurretFrame()
                    && armored.isBvpEraBrickSpent(box.name)).count();
        }

        void shot(Entity entity) {
            shots++; owned.add(entity); EliteDiagnostics.includeServerEntity(entity.getUUID());
            record("SHOT", "projectile", entity.getUUID(), "type", ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()),
                    "gun", entity instanceof EntityKineticBullet bullet ? bullet.getGunId() : null,
                    "ammo", entity instanceof EntityKineticBullet bullet ? bullet.getAmmoId() : null,
                    "profile", ProjectileProfiles.profileId(entity), "position", entity.position(),
                    "velocity", entity.getDeltaMovement(), "shot_sequence", ProjectileProfiles.shotSequence(entity),
                    "target", target.getUUID(), "latency_ms", player.latency);
            Vec3 direction = entity.getDeltaMovement().normalize();
            Vec3 plannedDirection = aim.subtract(muzzle).normalize();
            double alignment = direction.dot(plannedDirection);
            check("actual_launch_at_muzzle", entity.position().distanceToSqr(muzzle) < .0025);
            check("actual_launch_follows_aim", alignment > .995);
            Vec3 end = entity.position().add(direction.scale(Integer.parseInt(args[3]) * 2.0));
            if (neighbor != null) {
                var hit = ProjectileHitSelection.nearestObb(target.getOBBs(), entity.position(), end, 0);
                boolean neighborHit = ProjectileHitSelection.nearestObb(neighbor.getOBBs(), entity.position(), end, .03) != null;
                double distance = hit == null ? Double.POSITIVE_INFINITY : hit.point().distanceTo(neighbor.position());
                OBB.Part expected = args[2].equals("left") ? OBB.Part.WHEEL_LEFT : OBB.Part.WHEEL_RIGHT;
                record("LAUNCHED_SPLASH_RAY", "projectile", entity.getUUID(), "target", target.getUUID(),
                        "neighbor", neighbor.getUUID(), "predicted_part", hit == null ? null : hit.part(),
                        "predicted_point", hit == null ? null : hit.point(), "neighbor_ray_hit", neighborHit,
                        "neighbor_origin_distance", distance);
                check("splash_ray_hits_track_without_neighbor", hit != null && hit.part() == expected && !neighborHit);
                check("splash_neighbor_inside_damage_radius", distance < 2.5);
            }
            if (args[2].equals("gap")) {
                boolean aabbHit = target.getBoundingBox().clip(entity.position(), end).isPresent();
                boolean obbHit = ProjectileHitSelection.nearestObb(target.getOBBs(), entity.position(), end, .03) != null;
                record("LAUNCHED_GAP_RAY", "projectile", entity.getUUID(), "target", target.getUUID(),
                        "start", entity.position(), "end", end, "aabb_hit", aabbHit, "inflated_obb_hit", obbHit);
                check("launched_ray_is_aabb_only_gap", aabbHit && !obbHit);
            }
            if (args[5].equals("wall")) {
                var hit = level.clip(new ClipContext(entity.position(), end,
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
                boolean wallHit = hit.getType() == HitResult.Type.BLOCK && wallBlocks.contains(hit.getBlockPos());
                record("LAUNCHED_WALL_RAY", "projectile", entity.getUUID(), "start", entity.position(),
                        "end", end, "first_block", hit.getBlockPos(), "fixture_wall", wallHit);
                check("launched_ray_meets_fixture_wall", wallHit);
            }
        }

        Vec3 supportedEye(Vec3 eye) {
            return new Vec3(eye.x, Math.floor(eye.y - player.getEyeHeight() + 1.0E-6) + player.getEyeHeight(), eye.z);
        }

        void prepareObserverSupport() {
            // Use ordinary collision support; teleport/ability synchronization can reset creative flying.
            Vec3 eye = supportedEye(tacz() ? muzzle : muzzle.add(0, 4, 0));
            if (tacz()) muzzle = eye;
            BlockPos floor = BlockPos.containing(eye.x, eye.y - player.getEyeHeight() - 1.0E-6, eye.z);
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                put(floor.offset(x, 0, z), Blocks.BEDROCK.defaultBlockState());
                for (int y = 1; y <= 3; y++) put(floor.offset(x, y, z), Blocks.AIR.defaultBlockState());
            }
        }

        void positionObserver() {
            Vec3 direction = aim.subtract(muzzle);
            float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z)));
            Vec3 eye = supportedEye(tacz() ? muzzle : muzzle.add(0, 4, 0));
            player.getAbilities().flying = false; player.onUpdateAbilities();
            player.teleportTo(level, eye.x, eye.y - player.getEyeHeight(), eye.z, yaw, pitch);
            player.setDeltaMovement(Vec3.ZERO);
            player.setOldPosAndRot();
        }

        void findEnvelopeGap() {
            if (target instanceof ArmoredVehicleEntity armored && armored.usesDetailedProjectileCollision())
                throw new IllegalStateException("Use t72b or native_t90 for a non-detailed OBB gap");
            var bounds = target.getBoundingBox();
            var boxes = target.getOBBs();
            double[] heights = {.99, .98, .97, .95, .9, .75, .5, .25, .1, .05, .02, .01};
            for (int angle = 0; angle < 4; angle++) for (double height : heights) for (int lateral = -9; lateral <= 9; lateral++) {
                double radians = Math.toRadians(angle * 45);
                Vec3 axis = new Vec3(Math.cos(radians), 0, Math.sin(radians));
                Vec3 side = new Vec3(-axis.z, 0, axis.x);
                double width = (Math.abs(side.x) * bounds.getXsize() + Math.abs(side.z) * bounds.getZsize()) / 2;
                Vec3 point = new Vec3(bounds.getCenter().x, bounds.minY + bounds.getYsize() * height,
                        bounds.getCenter().z).add(side.scale(width * lateral / 10));
                Vec3 from = supportedEye(point.add(axis.scale(Integer.parseInt(args[3]))));
                Vec3 end = from.add(point.subtract(from).scale(2));
                if (bounds.deflate(.01).clip(from, end).isPresent()
                        && ProjectileHitSelection.nearestObb(boxes, from, end, .03) == null) {
                    muzzle = from; aim = point;
                    record("GAP_WITNESS", "target", target.getUUID(), "start", from, "end", end,
                            "aabb_hit", true, "inflated_obb_hit", false, "target_yaw", target.getYRot());
                    return;
                }
            }
            throw new IllegalStateException("No robust AABB-only gap found in this authored profile; not a passed test");
        }

        static double trackHealth(VehicleEntity vehicle, boolean left) {
            return vehicle instanceof ArmoredVehicleEntity armored
                    ? armored.getModuleHealth(left ? "lefttrack" : "righttrack")
                    : left ? vehicle.getLeftWheelHealth() : vehicle.getRightWheelHealth();
        }

        static AABB collisionBounds(VehicleEntity vehicle) {
            AABB bounds = vehicle.getBoundingBox();
            for (OBB box : vehicle.getOBBs()) for (var vertex : box.getVertices()) {
                Vec3 point = OBB.vector3dToVec3(vertex);
                bounds = bounds.minmax(new AABB(point, point));
            }
            return bounds;
        }

        VehicleEntity spawn(String id, Vec3 position) {
            force(position.x, position.z);
            var type = ForgeRegistries.ENTITY_TYPES.getValue(id.equals("native_t90")
                    ? new ResourceLocation("superbwarfare", "t_90a") : id.equals("native_lav150")
                    ? new ResourceLocation("superbwarfare", "lav_150") : new ResourceLocation(BertsVehiclePack.MODID, id));
            if (type == null || !(type.create(level) instanceof VehicleEntity vehicle))
                throw new IllegalStateException("Unavailable vehicle " + id);
            owned.add(vehicle); vehicle.load(new CompoundTag());
            float yaw = args[2].equals("gap") && id.equals(args[0]) ? 37F : 0F;
            vehicle.moveTo(position.x, position.y, position.z, yaw, 0); vehicle.setNoGravity(true);
            if (!level.addFreshEntity(vehicle)) throw new IllegalStateException("Vehicle insertion failed");
            EliteDiagnostics.includeServerEntity(vehicle.getUUID());
            return vehicle;
        }

        void force(double x, double z) {
            ChunkPos chunk = new ChunkPos((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
            if (!level.getForcedChunks().contains(chunk.toLong())) { level.setChunkForced(chunk.x, chunk.z, true); chunks.add(chunk); }
            level.getChunk(chunk.x, chunk.z);
        }

        void put(BlockPos position, BlockState state) {
            force(position.getX(), position.getZ());
            // Do not overwrite block entities, whose state cannot be restored by a BlockState.
            if (level.getBlockEntity(position) != null) throw new IllegalStateException("Fixture intersects block entity");
            blocks.putIfAbsent(position.immutable(), level.getBlockState(position));
            level.setBlockAndUpdate(position, state);
        }

        boolean tacz() { return args[1].equals("tacz") || args[1].equals("rpg"); }
        void check(String name, boolean passed) { if (!passed) failures++; record("CHECK", "name", name, "pass", passed); }
        void record(String event, Object... fields) {
            Object[] all = new Object[fields.length + 2]; all[0] = "run"; all[1] = run;
            System.arraycopy(fields, 0, all, 2, fields.length);
            EliteDiagnostics.record(player, "hitreg_fixture", event, all);
        }

        void close(String status) {
            String finalStatus = status;
            try {
                for (Entity entity : owned) entity.discard();
                blocks.forEach(level::setBlockAndUpdate);
                for (ChunkPos chunk : chunks) level.setChunkForced(chunk.x, chunk.z, false);
                player.setItemInHand(InteractionHand.MAIN_HAND, savedHand);
                if (tacz()) { var operator = IGunOperator.fromLivingEntity(player); operator.initialData(); operator.draw(player::getMainHandItem); }
                player.getAbilities().flying = savedFlying; player.onUpdateAbilities();
                player.teleportTo(level, savedPosition.x, savedPosition.y, savedPosition.z, savedYaw, savedPitch);
                player.setDeltaMovement(savedMotion);
            } catch (RuntimeException error) {
                finalStatus = "CLEANUP_FAILED";
                record("ERROR", "phase", "cleanup", "error", error.toString());
            } finally {
                record("FINISHED", "status", finalStatus, "failures", failures, "shots", shots);
                try { player.sendSystemMessage(Component.literal("[BVP_HITREG] " + run + " " + finalStatus)); }
                finally { EliteDiagnostics.INSTANCE.stop(player.server); active = null; }
            }
        }
    }
}
