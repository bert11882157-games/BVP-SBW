package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;

import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.entity.helicopter.BvpHelicopterEntity;
import com.yourname.berts_vehicle_pack.entity.helicopter.HelicopterFlightProfile;
import com.yourname.berts_vehicle_pack.entity.helicopter.HelicopterPhysicalControls;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/** Private trajectory acceptance using admitted pilot inputs and unforced production motion. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID)
public final class BvpHelicopterScenario {
    private static Run active;

    private BvpHelicopterScenario() { }

    private static boolean enabled() { return DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios"); }

    private static boolean privateOperator(ServerPlayer player) {
        MinecraftServer server = player.server;
        return enabled() && server.isDedicatedServer() && !server.usesAuthentication()
                && "127.0.0.1".equals(server.getLocalIp()) && server.getPort() == BvpFireTrafficControl.PORT
                && server.getPlayerCount() == 1 && player.level().dimension() == Level.OVERWORLD
                && player.isAlive() && !player.isSpectator() && player.getAbilities().instabuild
                && BvpFireTrafficControl.identity(player.getGameProfile().getName(), player.getUUID())
                && BvpFireTrafficControl.loopback(player.connection.connection.getRemoteAddress(), false);
    }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        if (!enabled()) return;
        event.getDispatcher().register(Commands.literal("bvp_helicopter_test")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("vehicle", StringArgumentType.word()).executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    if (active != null || !privateOperator(player) || player.getVehicle() != null
                            || EliteDiagnostics.isServerEnabled()) return 0;
                    String id = StringArgumentType.getString(context, "vehicle");
                    if (!id.matches("[a-z0-9_]{1,64}")) return 0;
                    active = new Run(player, id);
                    try { active.prepare(); }
                    catch (RuntimeException failure) { active.finish("ERROR", failure.toString()); }
                    return active == null ? 0 : 1;
                })));
        event.getDispatcher().register(Commands.literal("bvp_helicopter_stop")
                .requires(source -> source.hasPermission(2)).executes(context -> {
                    if (active == null) return 0;
                    active.finish("STOPPED", "Operator request"); return 1;
                }));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(TickEvent.ServerTickEvent event) {
        Run run = active;
        if (run == null || run.server != event.getServer()) return;
        try {
            if (event.phase == TickEvent.Phase.START) run.controls();
            else run.sample();
        } catch (RuntimeException failure) { run.finish("ERROR", failure.toString()); }
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        if (active != null && active.server == event.getServer()) active.finish("STOPPED", "Server stopping");
    }

    private enum Phase {
        SETTLE(100), OFF(60), SPOOL(160), TAKEOFF(300), HOVER(120), FORWARD(160), BRAKE(180),
        BANK_LEFT(80), BANK_RIGHT(120), YAW(160), LAND(600), SHUTDOWN(180);
        final int ticks;
        Phase(int ticks) { this.ticks = ticks; }
    }

    private static final class Run {
        final ServerPlayer player;
        final MinecraftServer server;
        final ServerLevel level;
        final String id;
        final Vec3 savedPosition;
        final float savedYaw, savedPitch;
        final boolean savedFlying;
        final long startedNanos = System.nanoTime();
        BvpHelicopterEntity vehicle;
        HelicopterPhysicalControls physicalControls;
        Phase phase = Phase.SETTLE;
        Stats stats;
        double groundY, startHealth, pitchRate, rollRate;
        double previousPitch, previousRoll, targetYaw, brakeStartSpeed;
        int phaseTicks, elapsed, assertions, failures, inputBits;
        double mouseX, mouseY;
        boolean captureOwned, finished;

        Run(ServerPlayer player, String id) {
            this.player = player; this.server = player.server; this.level = player.serverLevel(); this.id = id;
            savedPosition = player.position(); savedYaw = player.getYRot(); savedPitch = player.getXRot();
            savedFlying = player.getAbilities().flying;
        }

        void prepare() {
            physicalControls = switch (id) {
                case "mi_24a" -> HelicopterFlightProfile.mi24a().physicalControls();
                case "mi_24d" -> HelicopterFlightProfile.mi24d().physicalControls();
                case "ah_1f" -> HelicopterFlightProfile.ah1f().physicalControls();
                case "mi_26" -> HelicopterFlightProfile.mi26().physicalControls();
                case "ch_46e" -> HelicopterFlightProfile.ch46e().physicalControls();
                case "eurocopter_tiger" -> HelicopterFlightProfile.eurocopterTiger().physicalControls();
                case "ah_64d" -> HelicopterFlightProfile.ah64d().physicalControls();
                default -> throw new IllegalArgumentException("No helicopter trajectory profile: " + id);
            };
            int x = 3072, z = 3072;
            level.getChunk(x >> 4, z >> 4);
            groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            for (int dx = -32; dx <= 32; dx += 16) for (int dz = -32; dz <= 32; dz += 16) {
                level.getChunk((x + dx) >> 4, (z + dz) >> 4);
                if (level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz) != groundY)
                    throw new IllegalStateException("Helicopter fixture requires a flat test area");
            }
            var type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(BertsVehiclePack.MODID, id));
            Entity created = type == null ? null : type.create(level);
            if (!(created instanceof BvpHelicopterEntity helicopter))
                throw new IllegalStateException("Helicopter unavailable: " + id);
            vehicle = helicopter;
            vehicle.load(new CompoundTag());
            vehicle.moveTo(x + 0.5, groundY + 0.25, z + 0.5, 0, 0);
            vehicle.setDeltaMovement(Vec3.ZERO);
            vehicle.setEnergy(vehicle.getMaxEnergy());
            vehicle.addTag("bvp_helicopter_fixture");
            for (int slot = 0; slot < vehicle.getInventory().getSlots(); slot++)
                vehicle.getInventory().setStackInSlot(slot, ItemStack.EMPTY);
            for (String name : vehicle.getGunDataMap().keySet().toArray(String[]::new))
                vehicle.modifyGunData(name, data -> { data.resetStatus(); data.ammo.set(0); data.virtualAmmo.set(0); });
            if (!level.addFreshEntity(vehicle)) throw new IllegalStateException("Helicopter insertion failed");
            player.teleportTo(level, x + 0.5, groundY + 4, z + 0.5, 0, 0);
            player.getAbilities().flying = false; player.onUpdateAbilities();
            if (!player.startRiding(vehicle, true) || vehicle.getFirstPassenger() != player)
                throw new IllegalStateException("Pilot mount failed");
            startHealth = vehicle.getHealth();
            stats = new Stats(vehicle, groundY);
            EliteDiagnostics.INSTANCE.start(server); captureOwned = true;
            record("SCENARIO_STARTED", "vehicle", id, "motion_forced_after_spawn", false,
                    "scope", "server_pilot_admission_force_rotor_landing_not_client_input_or_rendering");
        }

        void context() {
            if (!privateOperator(player) || player.serverLevel() != level || vehicle == null
                    || vehicle.isRemoved() || vehicle.isWreck() || player.getVehicle() != vehicle
                    || vehicle.getFirstPassenger() != player || !EliteDiagnostics.isServerEnabled())
                throw new IllegalStateException("Helicopter fixture context lost");
            if (System.nanoTime() - startedNanos > 240_000_000_000L)
                throw new IllegalStateException("Helicopter fixture watchdog expired");
        }

        void controls() {
            context();
            double throttle = phase == Phase.SETTLE || phase == Phase.OFF || phase == Phase.SHUTDOWN ? 0 : 1;
            inputBits = vehicle.getBvpThrottleTarget() < throttle - 0.01 ? 32
                    : vehicle.getBvpThrottleTarget() > throttle + 0.01 ? 256 : 0;
            // Braking uses an active cyclic flare; the gentler landing correction only holds position.
            double pitch = phase == Phase.FORWARD ? 10 : phase == Phase.BRAKE
                    ? Mth.clamp(-vehicle.getBvpForwardSpeedKmh(), -10, 10) : phase == Phase.LAND
                    ? Mth.clamp(-vehicle.getBvpForwardSpeedKmh() * 0.25, -10, 10) : 0;
            double roll = phase == Phase.BANK_LEFT ? -12 : phase == Phase.BANK_RIGHT ? 12 : 0;
            if (phase == Phase.YAW || phase == Phase.LAND || phase == Phase.SHUTDOWN) targetYaw = 30;
            mouseX = Mth.clamp(Mth.wrapDegrees(targetYaw - vehicle.getYRot()) * 0.2, -4, 4);
            mouseY = Mth.clamp((pitch - vehicle.getXRot()) * 0.3 - pitchRate * 0.15, -4, 4);
            double rollDemand = Mth.wrapDegrees(roll - vehicle.getRoll()) - rollRate * 0.35;
            if (rollDemand < -1.5) inputBits |= 1;
            else if (rollDemand > 1.5) inputBits |= 2;
            if (phase == Phase.OFF) { mouseX = 4; mouseY = 4; inputBits |= 2; }
            if (phase == Phase.SETTLE) { mouseX = mouseY = 0; inputBits &= ~(1 | 2); }
            if (phase.ordinal() >= Phase.TAKEOFF.ordinal() && phase != Phase.SHUTDOWN) {
                double altitude = vehicle.getY() - groundY;
                double desiredVertical = phase == Phase.LAND
                        ? -Math.min(2, Math.max(0.35, altitude * 0.3))
                        : Mth.clamp((24 - altitude) * 0.35, -2, 3);
                double verticalError = desiredVertical - vehicle.getDeltaMovement().y * 20;
                if (verticalError > 0.25) inputBits |= 4;
                else if (verticalError < -0.25) inputBits |= 8;
            }
            if (phase == Phase.SHUTDOWN) { mouseX = mouseY = 0; inputBits &= ~(1 | 2); }
            vehicle.processInput((short) inputBits);
            vehicle.acceptVehicleMouseInput(player, mouseX, mouseY);
        }

        void sample() {
            context(); elapsed++; phaseTicks++;
            Vec3 motion = vehicle.getDeltaMovement();
            if (!Double.isFinite(motion.lengthSqr()) || !Double.isFinite(vehicle.getXRot())
                    || !Double.isFinite(vehicle.getRoll()) || !Double.isFinite(vehicle.getYRot()))
                throw new IllegalStateException("Non-finite trajectory");
            pitchRate = Mth.wrapDegrees(vehicle.getXRot() - previousPitch) * 20;
            rollRate = Mth.wrapDegrees(vehicle.getRoll() - previousRoll) * 20;
            previousPitch = vehicle.getXRot(); previousRoll = vehicle.getRoll();
            stats.sample(vehicle, groundY);
            record("TRAJECTORY", "vehicle", id, "phase", phase, "tick", elapsed,
                    "position", vehicle.position(), "motion", motion, "pitch", vehicle.getXRot(),
                    "yaw", vehicle.getYRot(), "roll", vehicle.getRoll(), "grounded", vehicle.onGround(),
                    "rotor", vehicle.getBvpRotorLiftPower(), "throttle", vehicle.getBvpThrottleTarget(),
                    "collective", vehicle.getBvpCollectiveTarget(), "input_bits", inputBits,
                    "mouse_x", mouseX, "mouse_y", mouseY,
                    "vertical_rotor_accel", vehicle.getBvpForceVerticalRotorAccelMps2(),
                    "health", vehicle.getHealth());
            if (vehicle.getY() - groundY > 120 || Math.abs(vehicle.getXRot()) > 70 || Math.abs(vehicle.getRoll()) > 85)
                throw new IllegalStateException("Trajectory outside fixture recovery envelope");
            boolean landed = phase == Phase.LAND && phaseTicks >= 40 && vehicle.onGround();
            if (phaseTicks < phaseDuration() && !landed) return;
            verifyPhase();
            if (phase == Phase.SHUTDOWN) {
                finish(failures == 0 ? "PASS" : "FAIL", "All trajectory phases complete"); return;
            }
            phase = Phase.values()[phase.ordinal() + 1]; phaseTicks = 0;
            if (phase == Phase.BRAKE) brakeStartSpeed = vehicle.getDeltaMovement().horizontalDistance();
            stats = new Stats(vehicle, groundY);
            record("PHASE_STARTED", "phase", phase, "duration_ticks", phaseDuration());
        }

        int phaseDuration() {
            if (phase != Phase.SPOOL && phase != Phase.SHUTDOWN) return phase.ticks;
            double rotorRate = phase == Phase.SPOOL ? physicalControls.rotorSpoolUpPerSecond()
                    : physicalControls.rotorSpoolDownPerSecond();
            // Throttle and rotor move concurrently; include one second for discrete target settling.
            double limitingRate = Math.min(rotorRate, physicalControls.throttlePerSecond());
            return Math.max(phase.ticks, (int) Math.ceil(20.0 / limitingRate) + 20);
        }

        void verifyPhase() {
            check("health_preserved", vehicle.getHealth() >= startHealth - 0.01);
            check("bounded_horizontal_speed", stats.maxHorizontalMps < 75);
            switch (phase) {
                case SETTLE -> check("settled_before_rotor_off_input", vehicle.onGround()
                        && vehicle.getDeltaMovement().lengthSqr() < 0.0001);
                case OFF -> {
                    check("rotor_off", stats.maxRotor < 0.001);
                    check("no_attitude_authority_without_rotor", stats.maxPitchChange < 0.1
                            && stats.maxRollChange < 0.1 && stats.maxYawChange < 0.1);
                }
                case SPOOL -> {
                    check("rotor_spooled", vehicle.getBvpRotorLiftPower() > 0.95);
                    check("spool_has_intermediate_values", stats.intermediateRotorTicks >= 20);
                }
                case TAKEOFF -> check("climbed_from_ground", stats.maxAltitude > 12 && !vehicle.onGround());
                case HOVER -> {
                    check("hover_altitude_bounded", stats.minAltitude > 12 && stats.maxAltitude < 40);
                    check("hover_vertical_speed_bounded", stats.maxAbsVerticalMps < 6);
                }
                case FORWARD -> {
                    check("cyclic_pitches_nose_down", stats.maxPitch > 3);
                    check("forward_translation", vehicle.position().distanceTo(stats.start) > 3
                            && stats.maxForwardKmh > 5);
                }
                case BRAKE -> check("cyclic_brakes_translation",
                        vehicle.getDeltaMovement().horizontalDistance() < Math.max(0.04, brakeStartSpeed * 0.7));
                case BANK_LEFT -> check("left_bank_response", stats.minRoll < -3);
                case BANK_RIGHT -> check("right_bank_response", stats.maxRoll > 3);
                case YAW -> check("yaw_response", Math.abs(Mth.wrapDegrees(30 - vehicle.getYRot())) < 12);
                case LAND -> check("landed_on_collision", vehicle.onGround() && Math.abs(vehicle.getDeltaMovement().y) < 0.05);
                case SHUTDOWN -> {
                    check("rotor_spooled_down", vehicle.getBvpRotorLiftPower() < 0.02);
                    check("remained_landed", vehicle.onGround());
                }
            }
            record("PHASE_RESULT", "phase", phase, "min_altitude", stats.minAltitude,
                    "max_altitude", stats.maxAltitude, "max_horizontal_mps", stats.maxHorizontalMps,
                    "max_vertical_mps", stats.maxAbsVerticalMps, "rotor", vehicle.getBvpRotorLiftPower());
        }

        void check(String name, boolean passed) {
            assertions++; if (!passed) failures++;
            record(passed ? "ASSERT_PASS" : "ASSERT_FAIL", "phase", phase, "check", name);
        }
        void record(String event, Object... fields) { EliteDiagnostics.record(player, "helicopter_trajectory", event, fields); }

        void finish(String status, String reason) {
            if (finished) return;
            finished = true;
            record("SCENARIO_COMPLETE", "vehicle", id, "status", status, "reason", reason,
                    "assertions", assertions, "failures", failures, "ticks", elapsed);
            player.stopRiding();
            if (vehicle != null) { vehicle.processInput((short) 0); vehicle.discard(); }
            player.teleportTo(level, savedPosition.x, savedPosition.y, savedPosition.z, savedYaw, savedPitch);
            player.getAbilities().flying = savedFlying; player.onUpdateAbilities();
            player.sendSystemMessage(Component.literal("Helicopter " + id + " " + status + ": "
                    + assertions + " checks, " + failures + " failures. " + reason));
            if (captureOwned) EliteDiagnostics.INSTANCE.stop(server);
            active = null;
        }
    }

    private static final class Stats {
        final Vec3 start;
        final float startPitch, startRoll, startYaw;
        double maxPitchChange, maxRollChange, maxYawChange;
        double minAltitude, maxAltitude, maxAbsPitch, maxAbsRoll, maxPitch, minRoll, maxRoll;
        double maxRotor, maxHorizontalMps, maxAbsVerticalMps, maxForwardKmh;
        int intermediateRotorTicks;

        Stats(BvpHelicopterEntity vehicle, double groundY) {
            start = vehicle.position(); minAltitude = maxAltitude = vehicle.getY() - groundY;
            startPitch = vehicle.getXRot(); startRoll = vehicle.getRoll(); startYaw = vehicle.getYRot();
            minRoll = maxRoll = vehicle.getRoll();
        }

        void sample(BvpHelicopterEntity vehicle, double groundY) {
            maxPitchChange = Math.max(maxPitchChange, Math.abs(Mth.wrapDegrees(vehicle.getXRot() - startPitch)));
            maxRollChange = Math.max(maxRollChange, Math.abs(Mth.wrapDegrees(vehicle.getRoll() - startRoll)));
            maxYawChange = Math.max(maxYawChange, Math.abs(Mth.wrapDegrees(vehicle.getYRot() - startYaw)));
            minAltitude = Math.min(minAltitude, vehicle.getY() - groundY);
            maxAltitude = Math.max(maxAltitude, vehicle.getY() - groundY);
            maxAbsPitch = Math.max(maxAbsPitch, Math.abs(vehicle.getXRot()));
            maxPitch = Math.max(maxPitch, vehicle.getXRot());
            maxAbsRoll = Math.max(maxAbsRoll, Math.abs(vehicle.getRoll()));
            minRoll = Math.min(minRoll, vehicle.getRoll()); maxRoll = Math.max(maxRoll, vehicle.getRoll());
            double rotor = vehicle.getBvpRotorLiftPower();
            maxRotor = Math.max(maxRotor, rotor);
            if (rotor > 0.01 && rotor < 0.99) intermediateRotorTicks++;
            maxHorizontalMps = Math.max(maxHorizontalMps, vehicle.getDeltaMovement().horizontalDistance() * 20);
            maxAbsVerticalMps = Math.max(maxAbsVerticalMps, Math.abs(vehicle.getDeltaMovement().y) * 20);
            maxForwardKmh = Math.max(maxForwardKmh, vehicle.getBvpForwardSpeedKmh());
        }
    }
}
