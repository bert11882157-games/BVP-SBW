package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;

import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.api.projectile.GuidedPropulsionPhase;
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile;
import com.atsuishio.superbwarfare.entity.projectile.WireGuideMissileEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.network.VehicleHelicopterAtgmCameraRayTransport;
import com.atsuishio.superbwarfare.network.message.send.VehicleHelicopterAtgmCameraRayMessage;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Opt-in tracking-boundary presentation and live launcher-guidance acceptance scenarios. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID)
public final class BvpEliteFlightScenarios {
    private static Run active;
    private BvpEliteFlightScenarios() { }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) return;
        event.getDispatcher().register(Commands.literal("bvp_elite_flight_test")
                .requires(source -> source.hasPermission(2)).executes(context -> {
                    var player = context.getSource().getPlayerOrException();
                    return start(player, false);
                }));
        event.getDispatcher().register(Commands.literal("bvp_elite_guidance_test")
                .requires(source -> source.hasPermission(2))
                .executes(context -> start(context.getSource().getPlayerOrException(), true)));
        event.getDispatcher().register(Commands.literal("bvp_qn506_guidance_test")
                .requires(source -> source.hasPermission(2))
                .executes(context -> start(context.getSource().getPlayerOrException(), true, true)));
    }

    private static int start(ServerPlayer player, boolean guidanceOnly) {
        return start(player, guidanceOnly, false);
    }

    private static int start(ServerPlayer player, boolean guidanceOnly, boolean qn506Only) {
        MinecraftServer server = player.server;
        if (active != null || EliteDiagnostics.isServerEnabled() || !server.isDedicatedServer()
                || server.usesAuthentication() || !"127.0.0.1".equals(server.getLocalIp())
                || server.getPort() != BvpFireTrafficControl.PORT || server.getPlayerCount() != 1
                || player.level().dimension() != Level.OVERWORLD || !player.isAlive()
                || player.isSpectator() || !player.getAbilities().instabuild
                || !BvpFireTrafficControl.identity(player.getGameProfile().getName(), player.getUUID())
                || !BvpFireTrafficControl.loopback(player.connection.connection.getRemoteAddress(), false)) return 0;
        EliteDiagnostics.INSTANCE.startForEntities(server, Set.of(player.getUUID()));
        active = new Run(player, guidanceOnly, qn506Only);
        try { active.prepare(); } catch (RuntimeException failure) { fail(failure); return 0; }
        return 1;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || active == null || event.getServer() != active.server) return;
        try { active.tick(); } catch (RuntimeException failure) { fail(failure); }
    }

    private static void fail(RuntimeException failure) {
        if (active == null) return;
        active.record("SCENARIO_ERROR", "error", failure.toString());
        active.close("ERROR");
        active = null;
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        if (active != null && active.server == event.getServer()) {
            active.close("STOPPED");
            active = null;
        }
    }

    private static final class Run {
        final MinecraftServer server;
        final ServerLevel level;
        final ServerPlayer observer;
        final Vec3 savedPosition;
        final float savedYaw;
        final float savedPitch;
        final boolean wasFlying;
        final Vec3 origin;
        final boolean guidanceOnly;
        final boolean qn506Only;
        final List<Entity> owned = new ArrayList<>();
        final List<ChunkPos> chunks = new ArrayList<>();
        final Set<ChunkPos> farCorridor = new HashSet<>();
        final Map<VehicleEntity, Vec3> moving = new LinkedHashMap<>();
        final Map<VehicleEntity, Cow> rotorCrew = new LinkedHashMap<>();
        VehicleEntity launcher;
        VehicleEntity target;
        WireGuideMissileEntity missile;
        Entity rocket;
        Vec3 rocketStart;
        float targetHealth;
        Vec3 launcherPosition;
        Vec3 targetPosition;
        double initialMissileX;
        Vec3 initialMissileMotion;
        Vec3 initialMissilePosition;
        double ignitionSpeed;
        int ejectionSamples;
        boolean ignitionObserved;
        boolean guidedTurn;
        String weapon;
        String caseId = "far_movement";
        int elapsed;
        int checks;
        int failures;
        int readinessTicks;
        boolean farReady;

        Run(ServerPlayer player, boolean guidanceOnly, boolean qn506Only) {
            server = player.server;
            level = player.serverLevel();
            observer = player;
            savedPosition = player.position();
            savedYaw = player.getYRot();
            savedPitch = player.getXRot();
            wasFlying = player.getAbilities().flying;
            this.guidanceOnly = guidanceOnly;
            this.qn506Only = qn506Only;
            origin = new Vec3(Math.floor(player.getX()), Math.floor(player.getY()) + 50, Math.floor(player.getZ()));
        }

        void prepare() {
            observer.stopRiding();
            observer.getAbilities().flying = true;
            observer.onUpdateAbilities();
            observer.teleportTo(level, origin.x, origin.y, origin.z, 0, 0);
            if (guidanceOnly) {
                elapsed = 1119;
                record("SCENARIO_STARTED", "suite", qn506Only ? "qn506_guidance" : "guided_ejection", "origin", origin);
                return;
            }
            level.setDayTime(2000);
            level.setWeatherParameters(6000, 0, false, false);
            String[] ids = {"mi28n", "ka50", "mig19", "leo2a6"};
            for (int index = 0; index < ids.length; index++) {
                // The ground fixture needs physical support on both server and client. Pinning
                // a tank in midair bypasses server gravity while the ordinary client still falls.
                Vec3 offset = new Vec3((index - 1.5) * 22, index == 3 ? savedPosition.y - origin.y : index * 5, 80);
                VehicleEntity vehicle = spawn(ids[index], origin.add(offset));
                moving.put(vehicle, offset);
                if (index < 2) {
                    Cow crew = EntityType.COW.create(level);
                    if (crew == null) throw new IllegalStateException("Missing crew fixture");
                    crew.setNoAi(true);
                    crew.setNoGravity(true);
                    crew.setInvulnerable(true);
                    crew.setPos(vehicle.position());
                    EliteDiagnostics.includeServerEntity(crew.getUUID());
                    level.addFreshEntity(crew);
                    owned.add(crew);
                    check("rotor_fixture_occupied", crew.startRiding(vehicle, true));
                    rotorCrew.put(vehicle, crew);
                }
            }
            for (Vec3 offset : moving.values()) {
                for (int distance = 80; distance <= 640; distance += 16) {
                    double x = origin.x + offset.x, z = origin.z + distance;
                    force(x, z);
                    farCorridor.add(new ChunkPos(BlockPos.containing(x, origin.y, z)));
                }
            }
            if (farCorridor.size() > 144) throw new IllegalStateException("Far corridor exceeded its chunk bound");
            record("SCENARIO_PREPARING", "suite", "far_flight", "origin", origin,
                    "corridor_chunks", farCorridor.size());
        }

        void tick() {
            if (!guidanceOnly && !farReady) {
                readinessTicks++;
                long ready = farCorridor.stream().filter(pos -> level.isPositionEntityTicking(
                        new BlockPos(pos.getMinBlockX(), (int) origin.y, pos.getMinBlockZ()))).count();
                if (ready != farCorridor.size()) {
                    if (readinessTicks % 5 == 0) record("CORRIDOR_WAIT", "ready", ready,
                            "required", farCorridor.size(), "wait_ticks", readinessTicks);
                    if (readinessTicks >= 200) throw new IllegalStateException("Far corridor never became entity-ticking");
                    return;
                }
                farReady = true;
                record("SCENARIO_STARTED", "suite", "far_flight", "origin", origin,
                        "corridor_chunks", farCorridor.size(), "ready_after_ticks", readinessTicks);
            }
            elapsed++;
            if (!guidanceOnly && elapsed == 1030) {
                rotorCrew.values().forEach(Entity::stopRiding);
                record("ROTOR_CREW_DISMOUNTED", "elapsed", elapsed,
                        "retained_ticks", 50, "motion_fixture", true);
            }
            if (!guidanceOnly && (elapsed == 1050 || elapsed == 1070)) {
                double distance = elapsed == 1050 ? 640 : 0;
                observer.teleportTo(level, origin.x, origin.y, origin.z + distance,
                        elapsed == 1050 ? 180 : 0, 0);
                record("OFF_ROTOR_RETRACK", "elapsed", elapsed, "observer_distance", distance);
            }
            if (elapsed <= 1080) {
                for (var row : rotorCrew.entrySet()) {
                    if (row.getValue().isRemoved() || (elapsed < 1030
                            && row.getValue().getVehicle() != row.getKey())) {
                        throw new IllegalStateException("Rotor crew fixture was removed or dismounted; "
                                + "dedicated tests require spawn-animals=true");
                    }
                }
                double distance = elapsed < 120 ? 80 : elapsed <= 520 ? 80 + (elapsed - 120) * 1.4
                        : elapsed < 600 ? 640 : elapsed <= 1000 ? 640 - (elapsed - 600) * 1.4 : 80;
                double motion = elapsed >= 120 && elapsed < 520 ? 1.4 : elapsed >= 600 && elapsed < 1000 ? -1.4 : 0;
                for (var row : moving.entrySet()) {
                    VehicleEntity vehicle = row.getKey();
                    Vec3 point = origin.add(row.getValue().x, row.getValue().y, distance);
                    force(point.x, point.z);
                    vehicle.setPos(point);
                    vehicle.setDeltaMovement(0, 0, motion);
                    vehicle.setPower(elapsed < 1030 ? 1 : 0);
                    vehicle.setTargetSpeed(elapsed < 1030 ? 1 : 0);
                    if (elapsed % 5 == 0) EliteDiagnostics.record(vehicle, "elite_flight", "MOVEMENT_REFERENCE",
                            "elapsed", elapsed, "position", point, "velocity", motion,
                            "entity_ticking", level.isPositionEntityTicking(vehicle.blockPosition()),
                            "added_to_world", vehicle.isAddedToWorld(),
                            "indexed", level.getEntity(vehicle.getUUID()) == vehicle,
                            "removed", vehicle.isRemoved());
                }
                if (elapsed == 120 || elapsed == 520 || elapsed == 600 || elapsed == 1000) {
                    record("MOVEMENT_STAGE", "elapsed", elapsed, "distance", distance);
                }
            }
            if (elapsed == 1080) {
                record("MOVEMENT_FIXTURES_REMOVED", "elapsed", elapsed);
                owned.forEach(Entity::discard);
                moving.clear();
            }
            int guidanceEnd = qn506Only ? 1750 : 2170;
            if (elapsed >= 1120 && elapsed < guidanceEnd) {
                int index = (elapsed - 1120) / 210;
                int phase = (elapsed - 1120) % 210;
                if (phase == 0) beginGuided(index);
                guidedTick(phase, index);
                if (phase == 209) endGuided();
            }
            if (elapsed == 2190 && !guidanceOnly) testRocket();
            if (elapsed > 2190 && elapsed <= 2210 && rocket != null && !rocket.isRemoved()) {
                EliteDiagnostics.record(rocket, "elite_flight", "ROCKET_TICK", "position", rocket.position(),
                        "motion", rocket.getDeltaMovement(), "age", rocket.tickCount);
            }
            if (elapsed == 2210 && !guidanceOnly) check("rocket_travelled_after_launch", rocket != null
                    && rocket.position().distanceTo(rocketStart) > 5);
            if (elapsed == (guidanceOnly ? guidanceEnd : 2290)) {
                close(failures == 0 ? "PASS" : "FAIL");
                active = null;
            }
        }

        void beginGuided(int index) {
            String[] types = {"mi28n", "mi24v", "ka50", "bmp2", "m2_bradley"};
            caseId = qn506Only ? "qn_506model" : types[index];
            weapon = qn506Only ? (index == 1 ? "MicroMissile" : "Missile")
                    : index == 0 ? "PassengerMissile" : index >= 3 ? "Missile" : "PilotMissile";
            observer.stopRiding();
            launcherPosition = origin.add(0, 0, 20);
            targetPosition = origin.add(50, 0, 580);
            launcher = spawn(caseId, launcherPosition);
            target = spawn("toyota_jihad_dshk", targetPosition);
            targetHealth = target.getHealth();
            check("guided_weapon_present", launcher.getGunData(weapon) != null);
            check("guided_controller_mounted", observer.startRiding(launcher, true));
            check("guided_weapon_owned_by_occupied_seat", launcher.captureVehicleWeaponGuidanceContext(
                    observer, weapon, launcher.getGunData(weapon)) != null);
            observer.setYRot(0);
            observer.setXRot(0);
            guidedTurn = false;
            ejectionSamples = 0;
            ignitionObserved = false;
            ignitionSpeed = 0;
            missile = null;
            record("GUIDED_CASE_STARTED", "case", caseId, "launcher", launcher.getUUID(),
                    "weapon", weapon, "target", target.getUUID(), "range", launcherPosition.distanceTo(targetPosition));
        }

        void guidedTick(int phase, int index) {
            launcher.setPos(launcherPosition);
            launcher.setDeltaMovement(Vec3.ZERO);
            launcher.setPower(1);
            if (!target.isRemoved()) {
                target.setPos(targetPosition);
                target.setDeltaMovement(Vec3.ZERO);
            }
            Vec3 eye = launcherPosition.add(0, 2, 0);
            Vec3 aim = phase < 30 ? new Vec3(0, 0, 1) : targetPosition.add(0, 1.0, 0).subtract(eye).normalize();
            boolean lockedControl = qn506Only && index == 2;
            if (lockedControl && phase >= 30) aim = new Vec3(-0.5, 0, 1).normalize();
            long sequence = 1_000_000L + elapsed;
            boolean accepted = VehicleHelicopterAtgmCameraRayTransport.admit(observer,
                    new VehicleHelicopterAtgmCameraRayMessage(observer.getUUID(), launcher.getId(),
                            launcher.getUUID(), level.dimension().location(), launcher.getSeatIndex(observer),
                            10_000L + index, sequence, sequence,
                            new Vector3f((float) eye.x, (float) eye.y, (float) eye.z),
                            new Vector3f((float) aim.x, (float) aim.y, (float) aim.z)));
            if (phase == 20) {
                check("guidance_input_admitted", accepted);
                Set<UUID> before = new HashSet<>();
                for (Entity entity : level.getAllEntities()) if (entity instanceof WireGuideMissileEntity) before.add(entity.getUUID());
                launcher.modifyGunData(weapon, data -> {
                    data.resetStatus();
                    data.reload.setPendingProgressPercent(0);
                    data.ammo.set(1);
                    data.virtualAmmo.set(0);
                });
                var shot = launcher.vehicleShootResult(observer, weapon);
                check("guided_launch_accepted", shot.isAccepted());
                for (Entity entity : level.getAllEntities()) {
                    if (entity instanceof WireGuideMissileEntity created && !before.contains(entity.getUUID())) {
                        missile = created;
                        if (lockedControl) created.setTargetUuid(target.getStringUUID());
                        owned.add(created);
                        EliteDiagnostics.includeServerEntity(created.getUUID());
                        initialMissileX = created.getDeltaMovement().normalize().x;
                        initialMissileMotion = created.getDeltaMovement();
                        initialMissilePosition = created.position();
                        var propulsion = ProjectileProfiles.guidedPropulsion(created);
                        check("ejection_launch_speed", propulsion != null
                                && Math.abs(initialMissileMotion.length() - propulsion.launchSpeed()) < 1e-5);
                        check("ejection_launch_phase", created.guidedPropulsionPhase() == GuidedPropulsionPhase.EJECTION);
                        check("ejection_launch_trail_suppressed", created.suppressesGuidedPropulsionTrail());
                        break;
                    }
                }
                check("guided_projectile_created", missile != null);
            }
            if (missile != null && !missile.isRemoved()) {
                int age = missile.tickCount;
                if (age >= 1 && age <= 6) {
                    ejectionSamples++;
                    check("six_unpowered_segments", missile.suppressesGuidedPropulsionTrail());
                    check("ejection_gravity_changes_velocity", missile.getDeltaMovement().y < initialMissileMotion.y - 0.005);
                    // Vanilla advances position before gravity changes the next step's velocity.
                    if (age == 1) check("first_segment_preserves_launch_displacement", Math.abs(missile.getY()
                            - (initialMissilePosition.y + initialMissileMotion.y)) < 1e-5);
                    else check("ejection_dips_below_linear_launch", missile.getY()
                            < initialMissilePosition.y + initialMissileMotion.y * age - 0.003);
                    check("ejection_horizontal_momentum_preserved", Math.abs(missile.getDeltaMovement().x - initialMissileMotion.x) < 1e-5
                            && Math.abs(missile.getDeltaMovement().z - initialMissileMotion.z) < 1e-5);
                    if (age < 6) check("no_early_motor_phase", missile.guidedPropulsionPhase() == GuidedPropulsionPhase.EJECTION);
                    else ignitionSpeed = missile.getDeltaMovement().length();
                }
                if (age == 7) {
                    ignitionObserved = true;
                    check("motor_starts_after_six_ticks", missile.guidedPropulsionPhase() == GuidedPropulsionPhase.THRUST
                            && !missile.suppressesGuidedPropulsionTrail());
                    check("motor_accelerates_from_ejection", missile.getDeltaMovement().length() > ignitionSpeed);
                }
                if (lockedControl && age == 25) {
                    check("locked_top_attack_lofts_despite_opposing_camera",
                            missile.getDeltaMovement().y > 0.02 && missile.getY() > initialMissilePosition.y);
                }
                force(missile.getX(), missile.getZ());
                if (phase > 35 && missile.getDeltaMovement().normalize().x > initialMissileX + 0.02) guidedTurn = true;
                EliteDiagnostics.record(missile, "elite_flight", "GUIDED_TICK", "case", caseId,
                        "phase", phase, "position", missile.position(), "motion", missile.getDeltaMovement(),
                        "speed", missile.guidedPropulsionSpeed(), "propulsion", missile.guidedPropulsionPhase(),
                        "input_admitted", accepted, "distance_to_target", missile.position().distanceTo(target.position()));
            }
        }

        void endGuided() {
            check("all_six_ejection_segments_observed", ejectionSamples == 6);
            check("ignition_transition_observed", ignitionObserved);
            check("guided_missile_turned_toward_target", guidedTurn);
            check("guided_far_target_damaged", target.isRemoved() || target.getHealth() < targetHealth);
            record("GUIDED_CASE_COMPLETE", "case", caseId, "target_health_before", targetHealth,
                    "target_health_after", target.getHealth(), "missile_removed", missile == null || missile.isRemoved());
            observer.stopRiding();
            if (missile != null) missile.discard();
            launcher.discard();
            target.discard();
        }

        void testRocket() {
            caseId = "mi28n_unguided_rocket";
            VehicleEntity rocketLauncher = spawn("mi28n", origin.add(0, 0, 16));
            observer.teleportTo(level, origin.x + 8, origin.y + 3, origin.z, -12, 0);
            Set<UUID> before = new HashSet<>();
            for (Entity entity : level.getAllEntities()) before.add(entity.getUUID());
            rocketLauncher.modifyGunData("Rocket", data -> { data.resetStatus(); data.ammo.set(1); data.virtualAmmo.set(0); });
            var shot = rocketLauncher.vehicleShootResult(null, "Rocket");
            check("rocket_launch_accepted", shot.isAccepted());
            boolean found = false;
            for (Entity entity : level.getAllEntities()) if (!before.contains(entity.getUUID()) && entity instanceof FastThrowableProjectile) {
                found = true;
                owned.add(entity);
                EliteDiagnostics.includeServerEntity(entity.getUUID());
                rocket = entity;
                rocketStart = entity.position();
                check("rocket_has_forward_motion", entity.getDeltaMovement().lengthSqr() > 1);
                EliteDiagnostics.record(entity, "elite_flight", "ROCKET_CREATED", "motion", entity.getDeltaMovement());
            }
            check("rocket_entity_created", found);
        }

        VehicleEntity spawn(String id, Vec3 point) {
            force(point.x, point.z);
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(BertsVehiclePack.MODID, id));
            Entity value = type == null ? null : type.create(level);
            if (!(value instanceof VehicleEntity vehicle)) throw new IllegalStateException("Missing vehicle " + id);
            vehicle.load(new CompoundTag());
            vehicle.moveTo(point.x, point.y, point.z, 0, 0);
            vehicle.setNoGravity(true);
            vehicle.addTag("bvp_elite_flight_fixture");
            vehicle.setEnergy(vehicle.getMaxEnergy());
            for (int slot = 0; slot < vehicle.getInventory().getSlots(); slot++) vehicle.getInventory().setStackInSlot(slot, ItemStack.EMPTY);
            for (String name : vehicle.getGunDataMap().keySet().toArray(String[]::new)) {
                vehicle.modifyGunData(name, data -> { data.resetStatus(); data.ammo.set(0); data.virtualAmmo.set(0); });
            }
            owned.add(vehicle);
            EliteDiagnostics.includeServerEntity(vehicle.getUUID());
            if (!level.addFreshEntity(vehicle)) throw new IllegalStateException("Vehicle insertion failed");
            EliteDiagnostics.record(vehicle, "elite_flight", "FIXTURE_SPAWNED", "case", caseId);
            return vehicle;
        }

        void force(double x, double z) {
            ChunkPos pos = new ChunkPos((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
            if (!level.getForcedChunks().contains(pos.toLong())) {
                level.setChunkForced(pos.x, pos.z, true);
                chunks.add(pos);
            }
            level.getChunk(pos.x, pos.z);
        }

        void check(String name, boolean pass) {
            checks++;
            if (!pass) failures++;
            record(pass ? "ASSERT_PASS" : "ASSERT_FAIL", "check", name, "case", caseId);
        }

        void record(String event, Object... fields) { EliteDiagnostics.record(observer, "elite_flight", event, fields); }

        void close(String status) {
            record("SCENARIO_COMPLETE", "suite", qn506Only ? "qn506_guidance" : guidanceOnly ? "guided_ejection" : "far_flight",
                    "status", status, "assertions", checks, "failures", failures);
            observer.stopRiding();
            owned.forEach(entity -> { if (!entity.isRemoved()) entity.discard(); });
            chunks.forEach(pos -> level.setChunkForced(pos.x, pos.z, false));
            observer.getAbilities().flying = wasFlying;
            observer.onUpdateAbilities();
            observer.teleportTo(level, savedPosition.x, savedPosition.y, savedPosition.z, savedYaw, savedPitch);
            observer.sendSystemMessage(Component.literal("Elite far/flight diagnostics " + status
                    + ": " + checks + " assertions, " + failures + " failures."));
            EliteDiagnostics.INSTANCE.stop(server);
        }
    }
}
