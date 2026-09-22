package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;

import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingFlightStrategy;
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingFlightStrategyProvider;
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingFlightState;
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingAtmosphere;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.network.FixedWingPilotInputReservation;
import com.atsuishio.superbwarfare.network.FixedWingPilotIntentTransport;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.datafixers.util.Either;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.level.ChunkTicketLevelUpdatedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Private flight trajectories change pilot inputs and explicitly declared damage/crew conditions. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID)
public final class BvpFixedWingScenario {
    private static final double STEP_SECONDS = 0.05;
    private static Run active;
    private BvpFixedWingScenario() { }

    private static boolean enabled() {
        return DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")
                && Boolean.getBoolean("bvp.diagnostics.flight");
    }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        if (!enabled()) return;
        event.getDispatcher().register(Commands.literal("bvp_fixed_wing")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("label", StringArgumentType.word()).executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    String label = StringArgumentType.getString(context, "label");
                    if (active != null || !privatePlayer(player) || player.getVehicle() != null
                            || EliteDiagnostics.isServerEnabled() || !label.matches("[A-Za-z0-9_-]{1,40}")) {
                        context.getSource().sendFailure(Component.literal(
                                "Requires the unmounted private flight operator, with diagnostics off."));
                        return 0;
                    }
                    try {
                        Path path = Path.of("config", "bvp-flight-tests", label + ".json");
                        if (Files.size(path) > 65536) throw new IllegalArgumentException("Flight plan too large");
                        Plan plan = new Gson().fromJson(Files.readString(path), Plan.class);
                        plan.validate();
                        active = new Run(player, label, plan);
                        active.prepare();
                        return 1;
                    } catch (Exception failure) {
                        if (active != null) active.finish("ERROR", failure.toString());
                        else player.sendSystemMessage(Component.literal("Flight fixture: " + failure));
                        return 0;
                    }
                })));
        event.getDispatcher().register(Commands.literal("bvp_fixed_wing_stop")
                .requires(source -> source.hasPermission(2)).executes(context -> {
                    if (active == null) return 0;
                    active.finish("STOPPED", "Operator stopped fixture");
                    return 1;
                }));
        event.getDispatcher().register(Commands.literal("bvp_fixed_wing_client_ack")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("run", StringArgumentType.word())
                        .then(Commands.argument("phase", StringArgumentType.word()).executes(context -> {
                            Run run = active;
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            if (run == null || !run.plan.clientInput || run.player != player
                                    || !privatePlayer(player) || !run.runId.toString().equals(
                                    StringArgumentType.getString(context, "run"))) return 0;
                            return run.acknowledge(StringArgumentType.getString(context, "phase"));
                        }))));
    }

    private static boolean privatePlayer(ServerPlayer player) {
        MinecraftServer server = player.server;
        return enabled() && server.isDedicatedServer() && !server.usesAuthentication()
                && "127.0.0.1".equals(server.getLocalIp()) && server.getPort() == BvpFireTrafficControl.PORT
                && server.getPlayerCount() == 1 && player.level().dimension() == Level.OVERWORLD
                && player.isAlive() && !player.isSpectator() && player.getAbilities().instabuild
                && BvpFireTrafficControl.identity(player.getGameProfile().getName(), player.getUUID())
                && BvpFireTrafficControl.loopback(player.connection.connection.getRemoteAddress(), false);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(TickEvent.ServerTickEvent event) {
        Run run = active;
        if (run == null || run.server != event.getServer()) return;
        try {
            if (event.phase == TickEvent.Phase.START) {
                run.controls();
                run.observeTick("START");
            } else {
                run.observeTick("END");
                run.sample();
            }
        } catch (RuntimeException failure) {
            run.observeTick("ERROR");
            run.finish("ERROR", failure.toString());
        }
    }

    @SubscribeEvent
    public static void explosion(ExplosionEvent.Start event) {
        Run run = active;
        if (run != null && event.getLevel() == run.level && run.vehicle != null
                && event.getExplosion().getDirectSourceEntity() == run.vehicle) {
            run.explosions.add(Map.of("serverTick", run.level.getGameTime(),
                    "position", List.of(event.getExplosion().getPosition().x,
                            event.getExplosion().getPosition().y, event.getExplosion().getPosition().z)));
        }
    }

    @SubscribeEvent
    public static void chunkTicket(ChunkTicketLevelUpdatedEvent event) {
        Run run = active;
        if (run != null && event.getLevel() == run.level) {
            run.chunkReadiness.ticket(event.getLevel().getGameTime(), event.getChunkPos(),
                    event.getOldTicketLevel(), event.getNewTicketLevel());
        }
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        if (active != null && active.server == event.getServer()) active.finish("STOPPED", "Server stopping");
    }

    private static final class Plan {
        String vehicleId = BertsVehiclePack.MODID + ":mig19";
        boolean clientInput;
        boolean worldAim;
        boolean horizontalInitialVelocity;
        boolean preparePoseOnClientArm;
        double altitude = 0;
        double speed = 0;
        double pitch = 0;
        double roll = 0;
        double yaw = 0;
        double mousePitchSign = -1;
        double mouseRollSign = -1;
        double mouseLimit = 1;
        double proportionalGain = 0.07;
        double rateDamping = 0.02;
        List<Stage> stages;

        void validate() {
            if (!worldAim) throw new IllegalArgumentException(
                    "This flight strategy requires an explicit worldAim plan; legacy stick plans must be migrated");
            ResourceLocation vehicle = ResourceLocation.tryParse(vehicleId == null ? "" : vehicleId);
            if (vehicle == null || !BertsVehiclePack.MODID.equals(vehicle.getNamespace()))
                throw new IllegalArgumentException("Expected a BVP aircraft resource ID");
            bounded(altitude, 0, 5000); bounded(speed, 0, 250);
            bounded(pitch, -80, 80); bounded(roll, -180, 180);
            bounded(yaw, -180, 180);
            if (preparePoseOnClientArm && !clientInput)
                throw new IllegalArgumentException("Deferred initial pose requires a client-input plan");
            if (Math.abs(mousePitchSign) != 1 || Math.abs(mouseRollSign) != 1)
                throw new IllegalArgumentException("Mouse signs must be +/-1");
            bounded(mouseLimit, 0.01, 1); bounded(proportionalGain, 0, 10);
            bounded(rateDamping, 0, 10);
            if (stages == null || stages.isEmpty() || stages.size() > 32)
                throw new IllegalArgumentException("Expected 1-32 stages");
            int ticks = 0;
            for (Stage stage : stages) {
                stage.validate(); ticks += stage.ticks;
                if (stage.pilotPitchTarget != null || stage.pilotRollTarget != null)
                    throw new IllegalArgumentException("World aim uses manualMask for held keys, not analog stick targets");
                if (clientInput && (stage.levelFlight || stage.landingApproach || stage.stallRecovery || stage.pilotPitchTarget != null
                        || stage.pilotRollTarget != null))
                    throw new IllegalArgumentException("Model target controllers require a server-input plan");
            }
            for (int i = 0; i < stages.size(); i++) {
                if (stages.get(i).expectDestruction && (clientInput || i != stages.size() - 1))
                    throw new IllegalArgumentException("Destruction must be the final server-input stage");
            }
            if (ticks > 4600) throw new IllegalArgumentException("Plan exceeds 230 seconds");
            if (stages.stream().allMatch(stage -> stage.expect.isEmpty() && !stage.expectDestruction))
                throw new IllegalArgumentException("Plan has no acceptance assertions");
        }
    }

    private static final class Stage {
        String name;
        int ticks;
        double throttle;
        double pitch;
        double roll;
        Double aimYaw;
        int manualMask;
        Double pilotPitchTarget;
        Double pilotRollTarget;
        boolean levelFlight;
        boolean landingApproach;
        // Legacy A/D selector; the manual-mask contract owns independent aileron overrides.
        int rudder;
        boolean brake;
        boolean afterburner;
        boolean neutralMouse;
        boolean recenter;
        boolean dismount;
        boolean disableEngine;
        boolean stallRecovery;
        boolean expectDestruction;
        double unloadTarget = -0.35;
        Map<String, double[]> expect = new LinkedHashMap<>();

        void validate() {
            if (name == null || !name.matches("[A-Za-z0-9_-]{1,40}") || ticks < 1 || ticks > 1200)
                throw new IllegalArgumentException("Invalid stage name/duration");
            bounded(throttle, 0, 1); bounded(pitch, -80, 80); bounded(roll, -180, 180);
            if (aimYaw != null) bounded(aimYaw, -180, 180);
            if ((manualMask & ~15) != 0) throw new IllegalArgumentException("Invalid held-key mask");
            if (roll != 0) throw new IllegalArgumentException(
                    "World-aim stages use aimYaw or manualMask; body roll is an observed result");
            if (pilotPitchTarget != null) bounded(pilotPitchTarget, -1, 1);
            if (pilotRollTarget != null) bounded(pilotRollTarget, -1, 1);
            if ((pilotPitchTarget != null || pilotRollTarget != null)
                    && (neutralMouse || dismount || stallRecovery))
                throw new IllegalArgumentException("Normalized pilot targets conflict with inactive/recovery controls");
            if (levelFlight && (pilotPitchTarget != null || pilotRollTarget != null
                    || stallRecovery || neutralMouse || dismount || recenter))
                throw new IllegalArgumentException("Level-flight controller conflicts with other pilot modes");
            if (landingApproach && (levelFlight || stallRecovery || neutralMouse || dismount || recenter
                    || afterburner || manualMask != 0 || rudder != 0))
                throw new IllegalArgumentException("Landing approach conflicts with another pilot mode");
            if (expectDestruction && (landingApproach || stallRecovery || dismount || disableEngine))
                throw new IllegalArgumentException("Destruction requires a physical impact, not scripted damage");
            bounded(unloadTarget, -1, 0);
            if (stallRecovery && (neutralMouse || recenter || dismount))
                throw new IllegalArgumentException("Recovery controller conflicts with inactive controls");
            if (rudder < -1 || rudder > 1) throw new IllegalArgumentException("Invalid rudder");
            if (expect == null) throw new IllegalArgumentException("Missing expectation map");
            for (var row : expect.entrySet()) {
                if (row.getValue() == null || row.getValue().length != 2
                        || !Double.isFinite(row.getValue()[0]) || !Double.isFinite(row.getValue()[1])
                        || row.getValue()[0] > row.getValue()[1])
                    throw new IllegalArgumentException("Invalid expectation " + row.getKey());
            }
        }

    }

    private static void bounded(double value, double minimum, double maximum) {
        if (!Double.isFinite(value) || value < minimum || value > maximum)
            throw new IllegalArgumentException("Value outside [" + minimum + "," + maximum + "]");
    }

    private static double energyBalanceResidual(double energyChange, double thrustWork,
                                                double dragWork, double sideWork, double groundWork,
                                                double liftWork, double collisionWork,
                                                double gravityQuadratureResidual) {
        return energyChange - (thrustWork + dragWork + sideWork + groundWork + liftWork + collisionWork)
                - gravityQuadratureResidual;
    }

    private record EnergyDelta(double specificEnergyChangePerKg, double collisionWorkPerKg,
                               double gravityQuadratureResidualPerKg, double balanceResidualPerKg) { }

    private static final class Run {
        final ServerPlayer player;
        final MinecraftServer server;
        final ServerLevel level;
        final String label;
        final Plan plan;
        final UUID runId = UUID.randomUUID();
        final String clientPlanDigest;
        final Vec3 savedPosition;
        final float savedYaw;
        final float savedPitch;
        final boolean savedFlying;
        final String startedUtc = Instant.now().toString();
        final long startedNanos = System.nanoTime();
        final List<Map<String, Object>> samples = new ArrayList<>();
        final List<Map<String, Object>> results = new ArrayList<>();
        final List<Map<String, Object>> explosions = new ArrayList<>();
        final ArrayDeque<Map<String, Object>> tickObservations = new ArrayDeque<>();
        final ChunkReadinessObservation chunkReadiness;
        VehicleEntity vehicle;
        FixedWingFlightStrategy strategy;
        Vec3 origin;
        double groundY;
        int totalTicks;
        int stageIndex;
        int stageTicks;
        int failures;
        int inputBits;
        double mouseX;
        double mouseY;
        Vec3 worldAimDirection = new Vec3(0, 0, 1);
        int admittedManualMask;
        long admittedIntentSequence = -1;
        FixedWingPilotInputReservation inputReservation;
        int inputReservationTicks;
        boolean awaitingInputReservation;
        Stats stats;
        boolean captureOwned;
        boolean finished;
        boolean startupNeutralInput = true;
        Vec3 requestedInitialMotion = Vec3.ZERO;
        Vec3 admittedInitialMotion = Vec3.ZERO;
        boolean initialMotionAdmitted;
        Double firstCommittedInitialKineticEnergy;
        int clientWarmupTicks = 60;
        Long clientPosePreparedTick;
        String awaitingClientPhase;
        long clientPhaseStartedNanos;
        boolean clientArmed;
        int afterburnerPulsePhase;
        boolean stallRecoveryCaptured;
        int destructionTicks;
        boolean physicalContactSeen;
        long lastMeasuredFlightTick = Long.MIN_VALUE;
        int startupSamplesSkipped;
        Vec3 previousMeasuredPosition;
        double previousMeasuredKineticEnergyPerKg;
        double integratedBodyRollDegrees;

        Run(ServerPlayer player, String label, Plan plan) throws IOException {
            this.player = player; this.label = label; this.plan = plan;
            if (plan.clientInput) {
                var loaded = BvpFlightClientPlan.load(Path.of("config", "bvp-flight-tests"), label);
                if (loaded.plan().stages.size() != plan.stages.size())
                    throw new IllegalArgumentException("Client/server stage count differs");
                for (int i = 0; i < plan.stages.size(); i++) {
                    var clientStage = loaded.plan().stages.get(i);
                    var serverStage = plan.stages.get(i);
                    if (!clientStage.name.equals(serverStage.name) || clientStage.ticks != serverStage.ticks)
                        throw new IllegalArgumentException("Client/server stage differs");
                }
                clientPlanDigest = loaded.sha256();
            } else clientPlanDigest = null;
            server = player.server; level = player.serverLevel();
            chunkReadiness = new ChunkReadinessObservation(level);
            savedPosition = player.position(); savedYaw = player.getYRot(); savedPitch = player.getXRot();
            savedFlying = player.getAbilities().flying;
        }

        void prepare() {
            int x = 2048, z = 2048;
            level.getChunk(x >> 4, z >> 4);
            groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            origin = new Vec3(x + 0.5, groundY, z + 0.5);
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(plan.vehicleId));
            Entity created = type == null ? null : type.create(level);
            if (!(created instanceof VehicleEntity aircraft)
                    || !(created instanceof FixedWingFlightStrategyProvider)
                    || !(aircraft.resolveVehicleFlightStrategy() instanceof FixedWingFlightStrategy selected))
                throw new IllegalStateException("Fixed-wing aircraft unavailable: " + plan.vehicleId);
            vehicle = aircraft;
            strategy = selected;
            vehicle.load(new CompoundTag());
            vehicle.moveTo(origin.x, groundY + plan.altitude, origin.z, (float) plan.yaw, (float) plan.pitch);
            vehicle.setZRot((float) plan.roll);
            float velocityPitch = plan.horizontalInitialVelocity ? 0 : (float) plan.pitch;
            requestedInitialMotion = Vec3.directionFromRotation(velocityPitch,
                    plan.horizontalInitialVelocity ? 0 : (float) plan.yaw).normalize().scale(plan.speed / 20);
            vehicle.setDeltaMovement(requestedInitialMotion);
            admittedInitialMotion = vehicle.getDeltaMovement();
            initialMotionAdmitted = admittedInitialMotion.x == requestedInitialMotion.x
                    && admittedInitialMotion.y == requestedInitialMotion.y
                    && admittedInitialMotion.z == requestedInitialMotion.z;
            if (!initialMotionAdmitted)
                throw new IllegalStateException("Requested initial aircraft velocity was altered before spawn");
            vehicle.setEnergy(vehicle.getMaxEnergy());
            vehicle.addTag("bvp_fixed_wing_fixture");
            for (int slot = 0; slot < vehicle.getInventory().getSlots(); slot++)
                vehicle.getInventory().setStackInSlot(slot, ItemStack.EMPTY);
            for (String name : vehicle.getGunDataMap().keySet().toArray(String[]::new)) {
                vehicle.modifyGunData(name, data -> { data.resetStatus(); data.ammo.set(0); data.virtualAmmo.set(0); });
            }
            if (!level.addFreshEntity(vehicle)) throw new IllegalStateException("Aircraft insertion failed");
            player.teleportTo(level, origin.x, groundY + plan.altitude + 3, origin.z, 0, 0);
            player.getAbilities().flying = false; player.onUpdateAbilities();
            if (!player.startRiding(vehicle, true) || vehicle.getFirstPassenger() != player)
                throw new IllegalStateException("Pilot mount failed");
            if (!plan.clientInput) {
                int duration = 400;
                for (Stage stage : plan.stages) duration = Math.addExact(duration, stage.ticks);
                if (duration > FixedWingPilotIntentTransport.MAX_SERVER_INPUT_TICKS)
                    throw new IllegalArgumentException("Flight plan exceeds private input reservation deadline");
                inputReservationTicks = duration;
                awaitingInputReservation = true;
            }
            EliteDiagnostics.INSTANCE.startForEntities(server, Set.of(player.getUUID(), vehicle.getUUID()));
            captureOwned = true;
            stats = new Stats(vehicle, groundY);
            player.sendSystemMessage(Component.literal("Flight trajectory " + label + " started."));
        }

        Stage stage() { return plan.stages.get(stageIndex); }

        /** Retain the final 128 tick pairs without changing tickets, input admission or sampling gates. */
        void observeTick(String phase) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("phase", phase);
            row.put("serverTick", level.getGameTime());
            row.put("elapsedNanos", System.nanoTime() - startedNanos);
            row.put("lastMeasuredFlightTick", lastMeasuredFlightTick);
            row.put("requestedSequence", admittedIntentSequence);
            try {
                if (vehicle != null) {
                    row.put("chunkObservationId", chunkReadiness.observe(level, vehicle, phase));
                    row.put("vehicleUuid", vehicle.getUUID());
                    row.put("vehicleTickCount", vehicle.tickCount);
                    row.put("position", List.of(vehicle.getX(), vehicle.getY(), vehicle.getZ()));
                    row.put("vehicleChunk", List.of(vehicle.chunkPosition().x, vehicle.chunkPosition().z));
                    row.put("playerChunk", List.of(player.chunkPosition().x, player.chunkPosition().z));
                    row.put("playerTicketCenter", player.getLastSectionPos().toString());
                    row.put("entityTicking", level.isPositionEntityTicking(vehicle.blockPosition()));
                    row.put("removed", vehicle.isRemoved());
                    row.put("removalReason", vehicle.getRemovalReason());
                    row.put("sameLevel", vehicle.level() == player.level());
                    row.put("pilotMounted", player.getVehicle() == vehicle && vehicle.getFirstPassenger() == player);
                    var intent = vehicle.getFixedWingPilotIntentState(player);
                    if (intent != null) {
                        row.put("observedSequence", intent.getAcceptedSequence());
                        row.put("observedEpoch", intent.getControlEpoch());
                    }
                }
                if (strategy != null) {
                    row.put("flightTick", strategy.stateSnapshot().getServerTick());
                    var surface = strategy.controlSurfaceSnapshot();
                    row.put("surfaceTick", surface == null ? null : surface.getServerTick());
                }
                if (inputReservation != null) {
                    row.put("reservedEpoch", inputReservation.getControlEpoch());
                    row.put("reservationStartTick", inputReservation.getStartedServerTick());
                    row.put("reservationExpiresTick", inputReservation.getExpiresAtServerTick());
                    row.put("reservationDurationTicks", inputReservation.getDurationTicks());
                }
            } catch (RuntimeException failure) {
                row.put("observationError", failure.toString());
            }
            if (tickObservations.size() == 256) tickObservations.removeFirst();
            tickObservations.addLast(row);
        }

        void clientPhase(String phase) {
            awaitingClientPhase = phase;
            clientPhaseStartedNanos = System.nanoTime();
            player.sendSystemMessage(Component.literal(BvpFlightClientControl.message(
                    runId, vehicle.getUUID(), vehicle.getId(), label, clientPlanDigest, phase,
                    "ARM".equals(phase) ? level.getGameTime() + 1L : 0L)));
        }

        int acknowledge(String phase) {
            if ("ABORT".equals(phase)) {
                finish("ERROR", "Real client input fixture aborted");
                return 1;
            }
            if (!phase.equals(awaitingClientPhase)) return 0;
            if ("ARM".equals(phase)) clientPhase("S0");
            else if (phase.equals("S" + stageIndex)) awaitingClientPhase = null;
            else return 0;
            return 1;
        }

        void context() {
            if (!privatePlayer(player) || player.serverLevel() != level || vehicle == null
                    || (!stage().expectDestruction && (vehicle.isRemoved() || vehicle.isWreck()))
                    || !EliteDiagnostics.isServerEnabled())
                throw new IllegalStateException("Flight fixture context lost");
            if (!stage().dismount && !destroyed()
                    && (player.getVehicle() != vehicle || vehicle.getFirstPassenger() != player))
                throw new IllegalStateException("Pilot context changed");
            if (System.nanoTime() - startedNanos > 270_000_000_000L)
                throw new IllegalStateException("Flight watchdog expired");
        }

        boolean destroyed() {
            return stage().expectDestruction && (vehicle.isWreck() || vehicle.isRemoved());
        }

        void controls() {
            context();
            if (destroyed()) return;
            BvpVehicleDataDiagnostic.record(vehicle);
            if (plan.clientInput) {
                startupNeutralInput = false;
                if (clientWarmupTicks > 0 && --clientWarmupTicks == 0) {
                    if (plan.preparePoseOnClientArm) {
                        if (totalTicks != 0 || clientPosePreparedTick != null)
                            throw new IllegalStateException("Initial pose may only be prepared before measurement");
                        // Prepare attitude in place before the client acquires its test lease.
                        // Moving back across tracked chunks or rebinding after ARM would destroy
                        // the context being measured. All subsequent steps are ordinary flight.
                        vehicle.setYRot((float) plan.yaw);
                        vehicle.setXRot((float) plan.pitch);
                        vehicle.setZRot((float) plan.roll);
                        vehicle.setDeltaMovement(requestedInitialMotion);
                        strategy.onActivated(vehicle);
                        clientPosePreparedTick = level.getGameTime();
                    }
                    clientArmed = true;
                    clientPhase("ARM");
                }
                if (awaitingClientPhase != null && System.nanoTime() - clientPhaseStartedNanos
                        > BvpFlightClientControl.ARM_NANOS)
                    throw new IllegalStateException("Timed out awaiting client " + awaitingClientPhase);
                return;
            }
            Stage stage = stage();
            if (awaitingInputReservation) {
                // First normal activation resets the pilot epoch; reserve only after its committed step.
                if (strategy.controlSurfaceSnapshot() == null) {
                    startupNeutralInput = true;
                    vehicle.processInput((short) 0);
                    return;
                }
                inputReservation = FixedWingPilotIntentTransport.acquireServerInput(
                        player, vehicle, runId, inputReservationTicks);
                awaitingInputReservation = false;
                if (inputReservation == null)
                    throw new IllegalStateException("Private flight input reservation rejected");
            }
            if (!stage.dismount && (inputReservation == null
                    || !FixedWingPilotIntentTransport.isServerInputReserved(player, inputReservation)))
                throw new IllegalStateException("Private flight input ownership lost");
            if (stageTicks == 0 && stage.disableEngine) {
                if (!(vehicle instanceof ArmoredVehicleEntity combatVehicle))
                    throw new IllegalStateException("Engine condition API unavailable");
                combatVehicle.setModuleHealth("engine", 0);
            }
            if (stage.dismount && player.getVehicle() == vehicle) {
                if (inputReservation != null) {
                    FixedWingPilotIntentTransport.releaseServerInput(player, inputReservation);
                    inputReservation = null;
                }
                player.stopRiding();
                player.getAbilities().flying = true; player.onUpdateAbilities();
            }
            // Stage conditions, including engine failure, apply before the first physical step.
            // The pilot cannot derive closed-loop targets from an uncommitted startup snapshot.
            startupNeutralInput = strategy.controlSurfaceSnapshot() == null;
            if (startupNeutralInput) {
                inputBits = 0;
                mouseX = mouseY = 0;
                vehicle.processInput((short) 0);
                return;
            }
            FixedWingFlightState state = strategy.stateSnapshot();
            double targetThrottle = stage.throttle;
            if (stage.landingApproach) {
                var handling = strategy.getHandling();
                double density = FixedWingAtmosphere.densityRatio(
                        (vehicle.getY() - vehicle.level().getSeaLevel()) / handling.getSimulationLengthScale());
                double approachSpeed = handling.getLiftReferenceSpeedMps() * 1.35 / Math.sqrt(density);
                targetThrottle = vehicle.onGround() || vehicle.getY() - groundY < 1.25 ? 0
                        : Mth.clamp(stage.throttle + (approachSpeed - state.getSpeedMps()) * 0.07, 0, 0.65);
            }
            // Endpoint requests must reach actual idle/full power; an interior tracking
            // tolerance can otherwise leave a heavy aircraft applying thrust while braking.
            inputBits = targetThrottle <= 0 ? (state.getThrottle() > 0 ? 8 : 0)
                    : targetThrottle >= 1 ? (state.getThrottle() < 1 ? 4 : 0)
                    : state.getThrottle() < targetThrottle - 0.015 ? 4
                    : state.getThrottle() > targetThrottle + 0.015 ? 8 : 0;
            if (stage.rudder < 0) inputBits |= 1;
            if (stage.rudder > 0) inputBits |= 2;
            if (stage.brake) inputBits |= 32;
            if (stage.landingApproach && vehicle.onGround()) inputBits |= 32;
            if (stage.afterburner && state.getThrottle() >= 0.999 && !state.getAfterburnerActive()) {
                if (afterburnerPulsePhase == 0) {
                    inputBits &= ~4;
                    afterburnerPulsePhase = 1;
                } else if (afterburnerPulsePhase == 1) {
                    inputBits |= 4;
                    afterburnerPulsePhase = 2;
                }
            }
            mouseX = 0;
            mouseY = 0;
            if (!stage.dismount) {
                vehicle.processInput((short) inputBits);
                admitWorldAim(state, stage);
            }
        }

        void admitWorldAim(FixedWingFlightState state, Stage stage) {
            var lease = vehicle.getFixedWingPilotIntentState(player);
            if (lease == null) throw new IllegalStateException("Missing mounted pilot intent lease");
            double pitch = stage.pitch;
            if (stage.levelFlight) {
                var handling = strategy.getHandling();
                double density = FixedWingAtmosphere.densityRatio(
                        (vehicle.getY() - vehicle.level().getSeaLevel()) / handling.getSimulationLengthScale());
                double speed = Math.max(state.getSpeedMps(), handling.getMinimumControlSpeedMps());
                double ratio = handling.getLiftReferenceSpeedMps() / speed;
                double bankCosine = Math.max(0.3, Math.cos(Math.toRadians(state.getBodyRollDegrees())));
                double trimAlpha = Mth.clamp(ratio * ratio
                        / (density * handling.getNormalizedLiftSlopePerDegree() * bankCosine),
                        0, handling.getStallAngleDegrees() * 0.8);
                double climb = Mth.clamp((plan.altitude - (vehicle.getY() - groundY)) * 0.35, -5, 5);
                pitch = -Math.toDegrees(Math.atan(bankCosine * Math.tan(Math.toRadians(trimAlpha)))
                        + Math.asin(Mth.clamp(climb / speed, -0.5, 0.5)));
            }
            if (stage.landingApproach) {
                var handling = strategy.getHandling();
                double density = FixedWingAtmosphere.densityRatio(
                        (vehicle.getY() - vehicle.level().getSeaLevel()) / handling.getSimulationLengthScale());
                double speed = Math.max(state.getSpeedMps(), handling.getMinimumControlSpeedMps());
                double ratio = handling.getLiftReferenceSpeedMps() / speed;
                double trimAlpha = Mth.clamp(ratio * ratio
                        / (density * handling.getNormalizedLiftSlopePerDegree()),
                        0, handling.getStallAngleDegrees() * 0.8);
                double height = Math.max(0, vehicle.getY() - groundY);
                double sink = -Mth.clamp(height * 0.15, 0.35, 2.5);
                double verticalRequest = sink + 0.4 * (sink - vehicle.getDeltaMovement().y * 20);
                // The approach changes only pilot requests; contact, lift and braking remain physical.
                pitch = vehicle.onGround() ? 0 : -Math.toDegrees(Math.toRadians(trimAlpha)
                        + Math.asin(Mth.clamp(verticalRequest / speed, -0.3, 0.1)));
            }
            worldAimDirection = Vec3.directionFromRotation((float) pitch,
                    (float) (stage.aimYaw == null ? plan.yaw : stage.aimYaw)).normalize();
            admittedManualMask = stage.manualMask;
            if (stage.rudder < 0) admittedManualMask |= 4;
            if (stage.rudder > 0) admittedManualMask |= 8;
            if (stage.stallRecovery) {
                Vec3 velocity = vehicle.getDeltaMovement();
                var handling = strategy.getHandling();
                double density = FixedWingAtmosphere.densityRatio(
                        (vehicle.getY() - vehicle.level().getSeaLevel()) / handling.getSimulationLengthScale());
                double recoverySpeed = handling.getLiftReferenceSpeedMps() * 1.35 / Math.sqrt(density);
                if (state.getStallActive()) stallRecoveryCaptured = false;
                else if (state.getSpeedMps() >= recoverySpeed) stallRecoveryCaptured = true;
                if (!stallRecoveryCaptured) {
                    if (velocity.lengthSqr() > 1e-8) worldAimDirection = velocity.normalize();
                    if (state.getStallActive()) admittedManualMask |= 1;
                } else {
                    // After unloading, request a physical pullout instead of following the
                    // descending velocity vector indefinitely. Only pilot intent is changed.
                    double speed = Math.max(state.getSpeedMps(), handling.getMinimumControlSpeedMps());
                    double ratio = handling.getLiftReferenceSpeedMps() / speed;
                    double trimAlpha = Mth.clamp(ratio * ratio
                            / (density * handling.getNormalizedLiftSlopePerDegree()),
                            0, handling.getStallAngleDegrees() * 0.8);
                    double verticalRequest = Mth.clamp(-0.35 * velocity.y * 20, -3, 3);
                    double recoveryPitch = -Math.toDegrees(Math.toRadians(trimAlpha)
                            + Math.asin(Mth.clamp(verticalRequest / speed, -0.15, 0.15)));
                    worldAimDirection = Vec3.directionFromRotation((float) recoveryPitch,
                            (float) (stage.aimYaw == null ? plan.yaw : stage.aimYaw)).normalize();
                }
            }
            if (stage.neutralMouse || stage.recenter) worldAimDirection = new Vec3(lease.getIntent().getDirectionX(),
                    lease.getIntent().getDirectionY(), lease.getIntent().getDirectionZ());
            admittedIntentSequence = Math.addExact(lease.getAcceptedSequence(), 1);
            if (lease.getControlEpoch() != inputReservation.getControlEpoch())
                throw new IllegalStateException("Private flight input epoch changed");
            if (!vehicle.acceptFixedWingPilotIntent(player, inputReservation.getControlEpoch(), admittedIntentSequence,
                    worldAimDirection.x, worldAimDirection.y, worldAimDirection.z,
                    admittedManualMask, stage.recenter && stageTicks == 0))
                throw new IllegalStateException("Server rejected fixture pilot intent");
        }

        void sample() {
            context();
            physicalContactSeen |= vehicle.onGround() || vehicle.horizontalCollision || vehicle.verticalCollision
                    || vehicle.hasRecentFixedWingWorldContact();
            if (destroyed()) {
                // Destruction queues its real explosion for the next server tick. Observe that
                // lifecycle before releasing the fixture; never create a diagnostic explosion.
                if (++destructionTicks < 5) return;
                List<String> errors = new ArrayList<>();
                if (!physicalContactSeen) errors.add("No physical contact observed");
                if (!vehicle.getCrash()) errors.add("Destruction was not marked as a collision");
                if (vehicle.getHealth() > 0) errors.add("Destruction without exhausted health");
                if (explosions.size() != 1) errors.add("Expected one source-matched aircraft explosion, got " + explosions.size());
                Map<String, Double> measures = stats.measures(vehicle, strategy.stateSnapshot(), groundY);
                checkExpectations(measures, errors);
                failures += errors.size();
                results.add(Map.of("stage", stage().name, "destruction", true,
                        "health", vehicle.getHealth(), "contactObserved", physicalContactSeen,
                        "explosionCount", explosions.size(), "measurements", measures, "failures", errors));
                finish(failures == 0 ? "PASS" : "FAIL", null);
                return;
            }
            if (!plan.clientInput && !startupNeutralInput && !stage().dismount) {
                var observed = vehicle.getFixedWingPilotIntentState(player);
                if (observed == null || observed.getAcceptedSequence() != admittedIntentSequence)
                    throw new IllegalStateException("Fixture input was replaced before its physical sample");
            }
            FixedWingFlightState state = strategy.stateSnapshot();
            var surfaces = strategy.controlSurfaceSnapshot();
            if (firstCommittedInitialKineticEnergy == null && surfaces != null) {
                double requestedEnergy = 200 * requestedInitialMotion.lengthSqr();
                double declaredEnergy = 0.5 * plan.speed * plan.speed;
                double actualEnergy = state.getPreStepKineticEnergyPerKg();
                if (!Double.isFinite(actualEnergy) || surfaces.getServerTick() != state.getServerTick()
                        || Math.abs(actualEnergy - requestedEnergy) > Math.max(1e-8, requestedEnergy * 1e-10)
                        || Math.abs(actualEnergy - declaredEnergy) > Math.max(1e-8, declaredEnergy * 1e-10))
                    throw new IllegalStateException("First committed step changed the admitted initial velocity");
                firstCommittedInitialKineticEnergy = actualEnergy;
            }
            if (plan.clientInput && (clientWarmupTicks > 0 || awaitingClientPhase != null)) return;
            // Startup can precede the first committed step. Client stage handshakes may
            // omit steps; they may not reuse a stale one or claim an energy delta over a gap.
            if (surfaces == null) {
                if (lastMeasuredFlightTick != Long.MIN_VALUE || ++startupSamplesSkipped > 20)
                    throw new IllegalStateException("Missing committed flight step");
                return;
            }
            if (surfaces.getServerTick() != state.getServerTick()
                    || (lastMeasuredFlightTick != Long.MIN_VALUE
                    && (state.getServerTick() <= lastMeasuredFlightTick
                    || (!plan.clientInput && state.getServerTick() != lastMeasuredFlightTick + 1))))
                throw new IllegalStateException("Non-contiguous committed flight steps");
            boolean adjacentPhysicalSample = previousMeasuredPosition != null
                    && state.getServerTick() == lastMeasuredFlightTick + 1;
            lastMeasuredFlightTick = state.getServerTick();
            totalTicks++; stageTicks++;
            Vec3 position = vehicle.position(), motion = vehicle.getDeltaMovement();
            for (double value : new double[]{position.x, position.y, position.z, motion.x, motion.y, motion.z,
                    vehicle.getXRot(), vehicle.getYRot(), vehicle.getRoll(), state.getThrottle(),
                    state.getQuaternionX(), state.getQuaternionY(), state.getQuaternionZ(), state.getQuaternionW(),
                    state.getVirtualPitchTarget(), state.getVirtualRollTarget(), state.getAirflowAuthority(),
                    state.getSideslipDegrees(), state.getSignedLiftAccelerationMps2(), state.getDragAccelerationMps2(),
                    state.getSideDragAccelerationMps2(), state.getOverspeedDragAccelerationMps2(),
                    state.getThrustAccelerationMps2(), state.getPreStepKineticEnergyPerKg(),
                    state.getPostStepKineticEnergyPerKg(), state.getStepThrustWorkPerKg(),
                    state.getStepGravityWorkPerKg(), state.getStepDragWorkPerKg(), state.getStepSideWorkPerKg(),
                    state.getStepLiftWorkPerKg(), state.getStepGroundResistanceWorkPerKg(),
                    state.getRollRateDegPerSecond(), state.getPitchRateDegPerSecond(), state.getYawRateDegPerSecond(),
                    state.getGravityAccelerationMps2(), state.getMassKg()}) {
                if (!Double.isFinite(value)) throw new IllegalStateException("Non-finite trajectory");
            }
            double actualKineticEnergy = 0.5 * motion.lengthSqr() * 400;
            double actualSpecificEnergy = actualKineticEnergy + state.getGravityAccelerationMps2() * position.y;
            double collisionWork = actualKineticEnergy - state.getPostStepKineticEnergyPerKg();
            double bodyRollDelta = state.getRollRateDegPerSecond() * STEP_SECONDS;
            integratedBodyRollDegrees += bodyRollDelta;
            EnergyDelta energy = null;
            if (adjacentPhysicalSample) {
                double heightDelta = position.y - previousMeasuredPosition.y;
                double energyChange = actualKineticEnergy - previousMeasuredKineticEnergyPerKg
                        + state.getGravityAccelerationMps2() * heightDelta;
                double quadrature = state.getStepGravityWorkPerKg()
                        + state.getGravityAccelerationMps2() * heightDelta;
                double residual = energyBalanceResidual(energyChange, state.getStepThrustWorkPerKg(),
                        state.getStepDragWorkPerKg(), state.getStepSideWorkPerKg(),
                        state.getStepGroundResistanceWorkPerKg(), state.getStepLiftWorkPerKg(),
                        collisionWork, quadrature);
                if (!Double.isFinite(energyChange) || !Double.isFinite(quadrature) || !Double.isFinite(residual))
                    throw new IllegalStateException("Non-finite energy delta");
                energy = new EnergyDelta(energyChange, collisionWork, quadrature, residual);
            }
            if (!Double.isFinite(actualSpecificEnergy) || !Double.isFinite(collisionWork)
                    || !Double.isFinite(integratedBodyRollDegrees))
                throw new IllegalStateException("Non-finite physical energy/roll sample");
            previousMeasuredPosition = position;
            previousMeasuredKineticEnergyPerKg = actualKineticEnergy;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tick", totalTicks); row.put("serverTick", level.getGameTime()); row.put("stage", stage().name);
            row.put("flightTick", state.getServerTick());
            row.put("startupNeutralInput", startupNeutralInput);
            row.put("stallRecoveryCaptured", stallRecoveryCaptured);
            if (plan.clientInput) {
                var lease = vehicle.getFixedWingPilotIntentState(player);
                if (lease == null) throw new IllegalStateException("Measured client input lost its pilot lease");
                var intent = lease.getIntent();
                row.put("worldAimDirection", List.of(intent.getDirectionX(), intent.getDirectionY(), intent.getDirectionZ()));
                row.put("manualMask", intent.getManualMask());
                row.put("screenRollInput", intent.getScreenRollInput());
                row.put("admittedIntentSequence", lease.getAcceptedSequence());
            } else {
                row.put("worldAimDirection", List.of(worldAimDirection.x, worldAimDirection.y, worldAimDirection.z));
                row.put("manualMask", admittedManualMask);
                row.put("admittedIntentSequence", admittedIntentSequence);
            }
            row.put("position", List.of(position.x, position.y, position.z));
            row.put("velocity", List.of(motion.x * 20, motion.y * 20, motion.z * 20));
            row.put("altitude", position.y - groundY); row.put("speed", motion.length() * 20);
            row.put("pitch", vehicle.getXRot()); row.put("roll", vehicle.getRoll()); row.put("yaw", vehicle.getYRot());
            row.put("throttle", state.getThrottle()); row.put("stall", state.getStallActive());
            row.put("aoa", state.getAngleOfAttackDegrees()); row.put("onGround", vehicle.onGround());
            row.put("health", vehicle.getHealth());
            row.put("quaternion", List.of(state.getQuaternionX(), state.getQuaternionY(),
                    state.getQuaternionZ(), state.getQuaternionW()));
            row.put("bodyRatesDegPerSecond", List.of(state.getPitchRateDegPerSecond(),
                    state.getRollRateDegPerSecond(), state.getYawRateDegPerSecond()));
            row.put("virtualPitchTarget", state.getVirtualPitchTarget());
            row.put("virtualRollTarget", state.getVirtualRollTarget());
            row.put("airflowAuthority", state.getAirflowAuthority());
            row.put("controlEffectiveness", state.getControlEffectiveness());
            row.put("stallSeverity", state.getStallSeverity());
            row.put("sideslipDegrees", state.getSideslipDegrees());
            row.put("massKg", state.getMassKg());
            row.put("gravityAccelerationMps2", state.getGravityAccelerationMps2());
            row.put("liftForceNewtons", state.getLiftForceNewtons());
            row.put("dragForceNewtons", state.getDragForceNewtons());
            row.put("signedLiftAccelerationMps2", state.getSignedLiftAccelerationMps2());
            row.put("dragAccelerationMps2", state.getDragAccelerationMps2());
            row.put("sideDragAccelerationMps2", state.getSideDragAccelerationMps2());
            row.put("overspeedDragAccelerationMps2", state.getOverspeedDragAccelerationMps2());
            row.put("thrustAccelerationMps2", state.getThrustAccelerationMps2());
            row.put("preStepKineticEnergyPerKg", state.getPreStepKineticEnergyPerKg());
            row.put("postStepKineticEnergyPerKg", state.getPostStepKineticEnergyPerKg());
            row.put("stepThrustWorkPerKg", state.getStepThrustWorkPerKg());
            row.put("stepGravityWorkPerKg", state.getStepGravityWorkPerKg());
            row.put("stepDragWorkPerKg", state.getStepDragWorkPerKg());
            row.put("stepSideWorkPerKg", state.getStepSideWorkPerKg());
            row.put("stepLiftWorkPerKg", state.getStepLiftWorkPerKg());
            row.put("stepGroundResistanceWorkPerKg", state.getStepGroundResistanceWorkPerKg());
            row.put("actualSpecificEnergyPerKg", actualSpecificEnergy);
            row.put("collisionExternalWorkPerKg", collisionWork);
            row.put("bodyRollDeltaDegrees", bodyRollDelta);
            row.put("integratedBodyRollDegrees", integratedBodyRollDegrees);
            row.put("energyDeltaAvailable", energy != null);
            if (energy != null) {
                row.put("deltaSpecificEnergyPerKg", energy.specificEnergyChangePerKg());
                row.put("gravityQuadratureResidualPerKg", energy.gravityQuadratureResidualPerKg());
                row.put("energyBalanceResidualPerKg", energy.balanceResidualPerKg());
            }
            if (plan.clientInput) {
                int acceptedBits = (vehicle.leftInputDown() ? 1 : 0)
                        | (vehicle.rightInputDown() ? 2 : 0) | (vehicle.forwardInputDown() ? 4 : 0)
                        | (vehicle.backInputDown() ? 8 : 0) | (vehicle.upInputDown() ? 16 : 0)
                        | (vehicle.downInputDown() ? 32 : 0) | (vehicle.sprintInputDown() ? 256 : 0);
                row.put("acceptedInputBits", acceptedBits);
            } else row.put("inputBits", inputBits);
            row.put("thrustNewtons", state.getThrustForceNewtons());
            if (!plan.clientInput) row.put("mouse", List.of(mouseX, mouseY));
            if (surfaces != null) {
                row.put("controlSurfaceTick", surfaces.getServerTick());
                row.put("acceptedControls", List.of(surfaces.getElevator(), surfaces.getAileron(),
                        surfaces.getRudder(), surfaces.getAirbrake()));
            }
            samples.add(row);
            stats.observe(vehicle, state, groundY, bodyRollDelta, energy);
            if (stageTicks >= stage().ticks) {
                Map<String, Double> measures = stats.measures(vehicle, state, groundY);
                List<String> errors = new ArrayList<>();
                checkExpectations(measures, errors);
                if (stage().expectDestruction) errors.add("Aircraft survived the declared impact deadline");
                failures += errors.size();
                results.add(Map.of("stage", stage().name, "measurements", measures, "failures", errors));
                player.sendSystemMessage(Component.literal("Flight " + stage().name + ": "
                        + (errors.isEmpty() ? "PASS" : errors) + "; speed=" + measures.get("endSpeed")
                        + ", altitude=" + measures.get("endAltitude")));
                stageIndex++; stageTicks = 0; afterburnerPulsePhase = 0; stallRecoveryCaptured = false;
                if (stageIndex >= plan.stages.size()) finish(failures == 0 ? "PASS" : "FAIL", null);
                else {
                    stats = new Stats(vehicle, groundY);
                    physicalContactSeen = false;
                    if (plan.clientInput) clientPhase("S" + stageIndex);
                }
            }
        }

        void checkExpectations(Map<String, Double> measures, List<String> errors) {
            for (var expected : stage().expect.entrySet()) {
                Double value = measures.get(expected.getKey());
                if (value == null || !Double.isFinite(value)
                        || value < expected.getValue()[0] || value > expected.getValue()[1]) {
                    errors.add(expected.getKey() + "=" + value + " outside ["
                            + expected.getValue()[0] + "," + expected.getValue()[1] + "]");
                }
            }
        }

        void finish(String status, String error) {
            if (finished) return;
            finished = true; active = null;
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("schema", "bvp-fixed-wing-trajectory-v1"); report.put("label", label);
            report.put("vehicleId", plan.vehicleId);
            if (strategy != null) report.put("flightProfileId", strategy.getProfile().getId());
            report.put("startedUtc", startedUtc); report.put("finishedUtc", Instant.now().toString());
            report.put("status", status); report.put("error", error); report.put("plan", plan);
            report.put("measuredTicks", totalTicks); report.put("failures", failures);
            report.put("inputPath", plan.clientInput ? "real_client_handlers" : "server_admitted_pilot_controls");
            report.put("runUuid", runId.toString());
            report.put("clientPlanSha256", clientPlanDigest);
            report.put("motionForcedDuringMeasurement", false);
            report.put("clientPosePreparedServerTick", clientPosePreparedTick);
            report.put("initialMotionAdmitted", initialMotionAdmitted);
            report.put("requestedInitialMotionBlocksPerTick", List.of(
                    requestedInitialMotion.x, requestedInitialMotion.y, requestedInitialMotion.z));
            report.put("admittedInitialMotionBlocksPerTick", List.of(
                    admittedInitialMotion.x, admittedInitialMotion.y, admittedInitialMotion.z));
            report.put("requestedInitialKineticEnergyPerKg", 200 * requestedInitialMotion.lengthSqr());
            report.put("firstCommittedInitialKineticEnergyPerKg", firstCommittedInitialKineticEnergy);
            report.put("startupSamplesSkipped", startupSamplesSkipped);
            report.put("energyAccounting", "adjacent_committed_actual_samples_with_separate_gravity_quadrature");
            report.put("keyboardAxisMeaning", "W/S and A/D: independent elevator and aileron overrides; world aim requests taxi yaw");
            report.put("integratedBodyRollDegrees", integratedBodyRollDegrees);
            report.put("stages", results); report.put("samples", samples);
            report.put("sourceMatchedExplosions", explosions);
            report.put("physicalContactSeen", physicalContactSeen);
            report.put("tickObservationHistory", new ArrayList<>(tickObservations));
            report.put("chunkReadinessDiagnostics", chunkReadiness.report());
            try {
                Path destination = Path.of("logs", "fixed-wing-tests", label + "-" + server.getTickCount() + ".json");
                Files.createDirectories(destination.getParent());
                Files.writeString(destination, new GsonBuilder().setPrettyPrinting().create().toJson(report));
            } catch (IOException failure) {
                player.sendSystemMessage(Component.literal("Flight report write failed: " + failure));
            } finally {
                if (inputReservation != null) {
                    FixedWingPilotIntentTransport.releaseServerInput(player, inputReservation);
                    inputReservation = null;
                }
                if (plan.clientInput && clientArmed && vehicle != null) {
                    player.sendSystemMessage(Component.literal(BvpFlightClientControl.message(
                            runId, vehicle.getUUID(), vehicle.getId(), label, clientPlanDigest, "STOP")));
                }
                if (vehicle != null) {
                    vehicle.processInput((short) 0);
                    vehicle.mouseInput(0, 0);
                    if (player.getVehicle() == vehicle) player.stopRiding();
                    vehicle.discard();
                }
                player.getAbilities().flying = savedFlying; player.onUpdateAbilities();
                player.teleportTo(level, savedPosition.x, savedPosition.y, savedPosition.z, savedYaw, savedPitch);
                if (captureOwned) EliteDiagnostics.INSTANCE.stop(server);
            }
            player.sendSystemMessage(Component.literal("Flight trajectory " + label + " " + status));
        }
    }

    /** Non-loading, bounded observations; ticket-requested status is not future readiness. */
    private static final class ChunkReadinessObservation {
        static final int MAX_CHUNKS = 50;
        static final int MAX_SNAPSHOTS = 64;
        static final int MAX_TRANSITIONS = 256;
        static final int MAX_TICKET_EVENTS = 256;
        static final int MAX_PROBES_PER_TICK = 3;
        static final int MAX_TICKET_EVENTS_PER_TICK = 128;
        static final List<String> COLUMNS = List.of("x", "z", "entityVisibility",
                "entityManagerTicking", "simulationRange", "entityDataLoaded",
                "visibleAccess", "visibleTicket", "visibleQueue", "visibleRequestedFullStatus",
                "visibleGenerationFullFuture", "visibleFullFuture", "visibleBlockFuture", "visibleEntityFuture",
                "updatingAccess", "updatingTicket", "updatingQueue", "updatingRequestedFullStatus",
                "updatingGenerationFullFuture", "updatingFullFuture", "updatingBlockFuture", "updatingEntityFuture");
        final ChunkReadinessAccess access;
        final ArrayDeque<Map<String, Object>> snapshots = new ArrayDeque<>();
        final ArrayDeque<Map<String, Object>> transitions = new ArrayDeque<>();
        final ArrayDeque<List<Object>> ticketEvents = new ArrayDeque<>();
        final Map<Long, List<Object>> previous = new LinkedHashMap<>();
        Set<Long> watched = Set.of();
        long observationId;
        long probeTick = Long.MIN_VALUE;
        long ticketTick = Long.MIN_VALUE;
        int probesThisTick;
        int ticketsThisTick;
        long probesDropped;
        long ticketsDropped;
        long snapshotsEvicted;
        long transitionsEvicted;
        long ticketsEvicted;
        long totalProbeNanos;
        long maximumProbeNanos;

        ChunkReadinessObservation(ServerLevel level) {
            access = new ChunkReadinessAccess(level);
        }

        long observe(ServerLevel level, VehicleEntity vehicle, String phase) {
            long tick = level.getGameTime();
            if (probeTick != tick) { probeTick = tick; probesThisTick = 0; }
            if (++probesThisTick > MAX_PROBES_PER_TICK) { probesDropped++; return -1; }
            long started = System.nanoTime();
            long id = ++observationId;
            ChunkPos current = vehicle.chunkPosition();
            Vec3 destination = vehicle.position().add(vehicle.getDeltaMovement());
            boolean finiteDestination = Double.isFinite(destination.x) && Double.isFinite(destination.y)
                    && Double.isFinite(destination.z) && Math.abs(destination.x) <= 30_000_000
                    && Math.abs(destination.z) <= 30_000_000;
            ChunkPos predicted = finiteDestination
                    ? new ChunkPos(Mth.floor(destination.x) >> 4, Mth.floor(destination.z) >> 4) : current;
            watched = halo(current.x, current.z, predicted.x, predicted.z);
            previous.keySet().retainAll(watched);
            List<List<Object>> rows = new ArrayList<>(watched.size());
            for (long key : watched) {
                ChunkPos pos = new ChunkPos(key);
                List<Object> row = new ArrayList<>(COLUMNS.size());
                row.add(pos.x); row.add(pos.z); row.add(access.visibility(key));
                // This public getter is exactly entityManager.canPositionTick, without distance gating.
                row.add(level.isNaturalSpawningAllowed(pos));
                row.add(level.getChunkSource().chunkMap.getDistanceManager().inEntityTickingRange(key));
                row.add(level.areEntitiesLoaded(key));
                access.appendHolder(row, level.getChunkSource().chunkMap, key, true);
                access.appendHolder(row, level.getChunkSource().chunkMap, key, false);
                List<Object> immutable = List.copyOf(row);
                rows.add(immutable);
                List<Object> old = previous.put(key, immutable);
                if (!immutable.equals(old)) {
                    Map<String, Object> transition = new LinkedHashMap<>();
                    transition.put("observationId", id); transition.put("serverTick", tick);
                    transition.put("kind", old == null ? "FIRST_SEEN" : "CHANGED");
                    transition.put("before", old); transition.put("after", immutable);
                    if (boundedAdd(transitions, transition, MAX_TRANSITIONS)) transitionsEvicted++;
                }
            }
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("id", id); snapshot.put("phase", phase); snapshot.put("serverTick", tick);
            snapshot.put("currentChunk", List.of(current.x, current.z));
            snapshot.put("predictedChunk", finiteDestination ? List.of(predicted.x, predicted.z) : null);
            snapshot.put("prediction", finiteDestination ? "POSITION_PLUS_CURRENT_VELOCITY_ONE_TICK"
                    : "INVALID_DESTINATION_CURRENT_HALO_ONLY");
            snapshot.put("rows", rows);
            long elapsed = System.nanoTime() - started;
            snapshot.put("probeNanos", elapsed);
            totalProbeNanos += elapsed; maximumProbeNanos = Math.max(maximumProbeNanos, elapsed);
            if (boundedAdd(snapshots, snapshot, MAX_SNAPSHOTS)) snapshotsEvicted++;
            return id;
        }

        void ticket(long tick, long chunk, int oldLevel, int newLevel) {
            if (!watched.contains(chunk)) return;
            if (ticketTick != tick) { ticketTick = tick; ticketsThisTick = 0; }
            if (++ticketsThisTick > MAX_TICKET_EVENTS_PER_TICK) { ticketsDropped++; return; }
            ChunkPos pos = new ChunkPos(chunk);
            if (boundedAdd(ticketEvents, List.of(tick, pos.x, pos.z, oldLevel, newLevel),
                    MAX_TICKET_EVENTS)) ticketsEvicted++;
        }

        Map<String, Object> report() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schema", "bvp-flight-chunk-readiness-v1");
            result.put("limits", Map.of("chunksPerProbe", MAX_CHUNKS, "probesPerTick", MAX_PROBES_PER_TICK,
                    "snapshots", MAX_SNAPSHOTS, "transitions", MAX_TRANSITIONS,
                    "ticketEvents", MAX_TICKET_EVENTS, "ticketEventsPerTick", MAX_TICKET_EVENTS_PER_TICK));
            result.put("columns", COLUMNS);
            result.put("ticketColumns", List.of("serverTick", "x", "z", "oldLevel", "newLevel"));
            result.put("holderStatusMeaning", "REQUESTED_FROM_TICKET_LEVEL_NOT_COMPLETED_READINESS");
            result.put("haloMeaning", "UNION_OF_CURRENT_AND_LINEAR_NEXT_STEP_5_BY_5_FULL_HALOS");
            result.put("access", access.description());
            result.put("observations", observationId);
            result.put("dropped", Map.of("probes", probesDropped, "tickets", ticketsDropped));
            result.put("evicted", Map.of("snapshots", snapshotsEvicted,
                    "transitions", transitionsEvicted, "tickets", ticketsEvicted));
            result.put("totalProbeNanos", totalProbeNanos); result.put("maximumProbeNanos", maximumProbeNanos);
            result.put("snapshots", new ArrayList<>(snapshots));
            result.put("transitions", new ArrayList<>(transitions));
            result.put("ticketEvents", new ArrayList<>(ticketEvents));
            return result;
        }

        static Set<Long> halo(int x, int z, int nextX, int nextZ) {
            Set<Long> result = new LinkedHashSet<>(MAX_CHUNKS);
            for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
                result.add(ChunkPos.asLong(x + dx, z + dz));
                result.add(ChunkPos.asLong(nextX + dx, nextZ + dz));
            }
            return result;
        }

        static <T> boolean boundedAdd(ArrayDeque<T> rows, T row, int capacity) {
            boolean evicted = rows.size() == capacity;
            if (evicted) rows.removeFirst();
            rows.addLast(row);
            return evicted;
        }

        static String futureState(CompletableFuture<? extends Either<?, ?>> future) {
            if (future == null) return "MISSING";
            if (future.isCancelled()) return "CANCELLED";
            if (future.isCompletedExceptionally()) return "FAILED";
            if (!future.isDone()) return "PENDING";
            try {
                Either<?, ?> value = future.getNow(null);
                return value == null ? "MISSING" : value.left().isPresent() ? "READY" : "UNLOADED";
            } catch (RuntimeException failure) {
                return "FAILED";
            }
        }
    }

    /** Verified 1.20.1 SRG bridge reads maps/futures only; inaccessible state is never reported ready. */
    private static final class ChunkReadinessAccess {
        final Method visible;
        final Method updating;
        final Long2ObjectMap<?> visibility;

        ChunkReadinessAccess(ServerLevel level) {
            visible = holderMethod("m_140327_");
            updating = holderMethod("m_140174_");
            Long2ObjectMap<?> found = null;
            try {
                Object manager = ObfuscationReflectionHelper.findField(ServerLevel.class, "f_143244_").get(level);
                Object states = ObfuscationReflectionHelper.findField(
                        PersistentEntitySectionManager.class, "f_157497_").get(manager);
                if (states instanceof Long2ObjectMap<?> map) found = map;
            } catch (ReflectiveOperationException | RuntimeException failure) {
                // Explicit ACCESS_UNAVAILABLE is emitted below; no fallback invents visibility.
            }
            visibility = found;
        }

        private static Method holderMethod(String srgName) {
            try {
                return ObfuscationReflectionHelper.findMethod(ChunkMap.class, srgName, long.class);
            } catch (RuntimeException failure) {
                return null;
            }
        }

        String visibility(long chunk) {
            if (visibility == null) return "ACCESS_UNAVAILABLE";
            Object value = visibility.get(chunk);
            return value instanceof Visibility state ? state.name() : "ACCESS_ERROR";
        }

        void appendHolder(List<Object> row, ChunkMap map, long chunk, boolean visibleMap) {
            Method method = visibleMap ? visible : updating;
            String status = "ACCESS_UNAVAILABLE";
            ChunkHolder holder = null;
            if (method != null) {
                try {
                    Object value = method.invoke(map, chunk);
                    holder = value instanceof ChunkHolder found ? found : null;
                    status = value == null ? "NO_HOLDER" : holder == null ? "ACCESS_ERROR" : "AVAILABLE";
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    status = "ACCESS_ERROR";
                }
            }
            row.add(status);
            row.add(holder == null ? -1 : holder.getTicketLevel());
            row.add(holder == null ? -1 : holder.getQueueLevel());
            row.add(holder == null ? status : holder.getFullStatus().name());
            row.add(holder == null ? status : ChunkReadinessObservation.futureState(
                    holder.getFutureIfPresentUnchecked(ChunkStatus.FULL)));
            row.add(holder == null ? status : ChunkReadinessObservation.futureState(holder.getFullChunkFuture()));
            row.add(holder == null ? status : ChunkReadinessObservation.futureState(holder.getTickingChunkFuture()));
            row.add(holder == null ? status : ChunkReadinessObservation.futureState(holder.getEntityTickingChunkFuture()));
        }

        Map<String, String> description() {
            return Map.of("visibleHolderMethod", "m_140327_", "updatingHolderMethod", "m_140174_",
                    "entityManagerField", "f_143244_", "chunkVisibilityField", "f_157497_",
                    "visibleAccess", visible == null ? "ACCESS_UNAVAILABLE" : "AVAILABLE",
                    "updatingAccess", updating == null ? "ACCESS_UNAVAILABLE" : "AVAILABLE",
                    "visibilityAccess", visibility == null ? "ACCESS_UNAVAILABLE" : "AVAILABLE");
        }
    }

    private static final class Stats {
        final Vec3 start;
        final double initialAltitude;
        final double initialHealth;
        double minimumSpeed = Double.POSITIVE_INFINITY;
        double maximumSpeed;
        double minimumAltitude = Double.POSITIVE_INFINITY;
        double maximumAltitude = Double.NEGATIVE_INFINITY;
        double maximumAbsoluteRoll;
        double maximumAbsolutePitch;
        double headingChange;
        float previousYaw;
        boolean previousGround;
        int groundTransitions;
        int grounded;
        int airborne;
        int stalled;
        int airStreak;
        int longestAirStreak;
        int groundStreak;
        double integratedRollTravel;
        double absoluteRollTravel;
        double minAirflowAuthority = Double.POSITIVE_INFINITY;
        double maxAirflowAuthority = Double.NEGATIVE_INFINITY;
        int energyDeltaSamples;
        int skippedEnergyDeltas;
        double energyChange;
        double energyBalanceResidualMaxAbs;
        double energyBalanceResidualSum;
        double gravityQuadratureResidualSum;
        double thrustWorkSum;
        double dragWorkSum;
        double sideWorkSum;
        double liftWorkSum;
        double groundResistanceWorkSum;
        double gravityWorkSum;
        double collisionWorkSum;

        Stats(VehicleEntity vehicle, double groundY) {
            start = vehicle.position(); initialAltitude = start.y - groundY;
            initialHealth = vehicle.getHealth();
            previousYaw = vehicle.getYRot(); previousGround = vehicle.onGround();
        }

        void observe(VehicleEntity vehicle, FixedWingFlightState state, double groundY,
                     double bodyRollDelta, EnergyDelta energy) {
            double speed = vehicle.getDeltaMovement().length() * 20;
            double altitude = vehicle.getY() - groundY;
            minimumSpeed = Math.min(minimumSpeed, speed); maximumSpeed = Math.max(maximumSpeed, speed);
            minimumAltitude = Math.min(minimumAltitude, altitude); maximumAltitude = Math.max(maximumAltitude, altitude);
            maximumAbsoluteRoll = Math.max(maximumAbsoluteRoll, Math.abs(vehicle.getRoll()));
            maximumAbsolutePitch = Math.max(maximumAbsolutePitch, Math.abs(vehicle.getXRot()));
            headingChange += Mth.wrapDegrees(vehicle.getYRot() - previousYaw); previousYaw = vehicle.getYRot();
            if (vehicle.onGround() != previousGround) groundTransitions++;
            previousGround = vehicle.onGround();
            if (vehicle.onGround()) {
                grounded++; groundStreak++; airStreak = 0;
            } else {
                airborne++; airStreak++; groundStreak = 0;
                longestAirStreak = Math.max(longestAirStreak, airStreak);
            }
            if (state.getStallActive()) stalled++;
            integratedRollTravel += bodyRollDelta;
            absoluteRollTravel += Math.abs(bodyRollDelta);
            minAirflowAuthority = Math.min(minAirflowAuthority, state.getAirflowAuthority());
            maxAirflowAuthority = Math.max(maxAirflowAuthority, state.getAirflowAuthority());
            if (energy == null) {
                skippedEnergyDeltas++;
            } else {
                // Work sums cover exactly the same paired physical steps as energyChange.
                energyDeltaSamples++;
                energyChange += energy.specificEnergyChangePerKg();
                energyBalanceResidualMaxAbs = Math.max(energyBalanceResidualMaxAbs,
                        Math.abs(energy.balanceResidualPerKg()));
                energyBalanceResidualSum += energy.balanceResidualPerKg();
                gravityQuadratureResidualSum += energy.gravityQuadratureResidualPerKg();
                thrustWorkSum += state.getStepThrustWorkPerKg();
                dragWorkSum += state.getStepDragWorkPerKg();
                sideWorkSum += state.getStepSideWorkPerKg();
                liftWorkSum += state.getStepLiftWorkPerKg();
                groundResistanceWorkSum += state.getStepGroundResistanceWorkPerKg();
                gravityWorkSum += state.getStepGravityWorkPerKg();
                collisionWorkSum += energy.collisionWorkPerKg();
            }
        }

        Map<String, Double> measures(VehicleEntity vehicle, FixedWingFlightState state, double groundY) {
            Map<String, Double> values = new LinkedHashMap<>();
            values.put("minSpeed", minimumSpeed); values.put("maxSpeed", maximumSpeed);
            values.put("endSpeed", vehicle.getDeltaMovement().length() * 20);
            values.put("minAltitude", minimumAltitude); values.put("maxAltitude", maximumAltitude);
            values.put("endAltitude", vehicle.getY() - groundY);
            values.put("heightChange", vehicle.getY() - groundY - initialAltitude);
            values.put("distance", vehicle.position().subtract(start).horizontalDistance());
            values.put("headingChange", headingChange); values.put("groundTransitions", (double) groundTransitions);
            values.put("endYaw", (double) Mth.wrapDegrees(vehicle.getYRot()));
            values.put("groundTicks", (double) grounded); values.put("airTicks", (double) airborne);
            values.put("stallTicks", (double) stalled); values.put("endThrottle", state.getThrottle());
            values.put("endStall", state.getStallActive() ? 1.0 : 0.0);
            values.put("endAoA", state.getAngleOfAttackDegrees());
            values.put("endControlAuthority", state.getControlEffectiveness());
            values.put("minAirflowAuthority", minAirflowAuthority);
            values.put("maxAirflowAuthority", maxAirflowAuthority);
            values.put("endAirflowAuthority", state.getAirflowAuthority());
            values.put("endVirtualPitchTarget", state.getVirtualPitchTarget());
            values.put("endVirtualRollTarget", state.getVirtualRollTarget());
            values.put("integratedRollTravel", integratedRollTravel);
            values.put("absoluteRollTravel", absoluteRollTravel);
            values.put("energyDeltaSamples", (double) energyDeltaSamples);
            values.put("skippedEnergyDeltas", (double) skippedEnergyDeltas);
            if (energyDeltaSamples > 0) {
                values.put("energyChange", energyChange);
                values.put("energyBalanceResidualMaxAbs", energyBalanceResidualMaxAbs);
                values.put("energyBalanceResidualSum", energyBalanceResidualSum);
                values.put("gravityQuadratureResidualSum", gravityQuadratureResidualSum);
                values.put("thrustWorkSum", thrustWorkSum);
                values.put("dragWorkSum", dragWorkSum);
                values.put("sideWorkSum", sideWorkSum);
                values.put("liftWorkSum", liftWorkSum);
                values.put("groundResistanceWorkSum", groundResistanceWorkSum);
                values.put("gravityWorkSum", gravityWorkSum);
                values.put("collisionWorkSum", collisionWorkSum);
            }
            values.put("longestAirStreak", (double) longestAirStreak);
            values.put("finalGroundStreak", (double) groundStreak);
            values.put("endThrust", state.getThrustForceNewtons());
            values.put("endPitch", (double) vehicle.getXRot()); values.put("endRoll", (double) vehicle.getRoll());
            values.put("maxAbsRoll", maximumAbsoluteRoll); values.put("maxAbsPitch", maximumAbsolutePitch);
            var controls = vehicle.getVehicleFlightControlSurfaceSnapshot(1.0F);
            if (controls != null) {
                values.put("endElevator", (double) controls.getElevator());
                values.put("endAileron", (double) controls.getAileron());
                values.put("endRudder", (double) controls.getRudder());
                values.put("endAfterburner", controls.getAfterburnerActive() ? 1.0 : 0.0);
            }
            values.put("endVerticalSpeed", vehicle.getDeltaMovement().y * 20);
            values.put("health", (double) vehicle.getHealth());
            values.put("healthChange", vehicle.getHealth() - initialHealth);
            return values;
        }
    }
}
