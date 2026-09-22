package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;

import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Opt-in muzzle/tracer observation through accepted vehicle shots in the private test world. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID)
public final class BvpTracerAcceptanceScenario {
    private static Run active;
    private BvpTracerAcceptanceScenario() {}

    private static boolean enabled() {
        return DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")
                && Boolean.getBoolean("bvp.diagnostics.tracers");
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

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        if (!enabled()) return;
        event.getDispatcher().register(Commands.literal("bvp_mounted_tracer_check")
                .requires(source -> source.hasPermission(2)).executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    if (active != null || !privatePlayer(player) || player.getVehicle() != null
                            || EliteDiagnostics.isServerEnabled()) return 0;
                    Run run = new Run(player, true);
                    active = run;
                    try { run.prepare(); }
                    catch (RuntimeException failure) { run.finish(failure.toString()); return 0; }
                    return 1;
                }));
        event.getDispatcher().register(Commands.literal("bvp_tracer_check")
                .requires(source -> source.hasPermission(2)).executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    if (active != null || !privatePlayer(player) || player.getVehicle() != null
                            || EliteDiagnostics.isServerEnabled()) return 0;
                    Run run = new Run(player);
                    active = run;
                    try { run.prepare(); }
                    catch (RuntimeException failure) { run.finish(failure.toString()); return 0; }
                    return 1;
                }));
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        Run run = active;
        if (run == null || run.server != event.getServer() || event.phase != TickEvent.Phase.END) return;
        try { run.tick(); }
        catch (RuntimeException failure) { run.finish(failure.toString()); }
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        if (active != null && active.server == event.getServer()) active.finish("Server stopping");
    }

    private static final class Run {
        final ServerPlayer observer;
        final MinecraftServer server;
        final ServerLevel level;
        final Vec3 savedPosition;
        final float savedYaw;
        final float savedPitch;
        final boolean savedFlying;
        final Vec3 origin;
        final long started = System.nanoTime();
        final List<VehicleEntity> vehicles = new ArrayList<>();
        final List<net.minecraft.world.entity.animal.Cow> operators = new ArrayList<>();
        final List<UUID> projectiles = new ArrayList<>();
        VehicleEntity abrams;
        VehicleEntity bmpt;
        int ticks;
        int accepted;
        int failures;
        boolean captureOwned;
        boolean finished;
        final boolean mounted;
        private static final String[] MOUNTS = {
                "toyota_jihad_dshk", "kord_tripod", "browning_tripod", "zu23_2"};

        Run(ServerPlayer player) {
            this(player, false);
        }

        Run(ServerPlayer player, boolean mounted) {
            this.mounted = mounted;
            observer = player; server = player.server; level = player.serverLevel();
            savedPosition = player.position(); savedYaw = player.getYRot(); savedPitch = player.getXRot();
            savedFlying = player.getAbilities().flying;
            origin = savedPosition.add(0, 3, 14);
        }

        void prepare() {
            observer.getAbilities().flying = true;
            observer.onUpdateAbilities();
            EliteDiagnostics.INSTANCE.start(server);
            captureOwned = true;
            if (mounted) {
                for (int index = 0; index < MOUNTS.length; index++) {
                    VehicleEntity vehicle = spawn(MOUNTS[index], origin.add(index * 18, 0, 0));
                    int seat = index == 0 ? 2 : 0;
                    net.minecraft.world.entity.animal.Cow operator = null;
                    for (int passenger = 0; passenger <= seat; passenger++) {
                        operator = EntityType.COW.create(level);
                        if (operator == null) throw new IllegalStateException("Mounted operator unavailable");
                        operator.setNoAi(true); operator.setSilent(true); operator.setInvisible(true);
                        operator.setPos(vehicle.position());
                        operators.add(operator);
                        if (!level.addFreshEntity(operator) || !operator.startRiding(vehicle, true))
                            throw new IllegalStateException("Mounted operator boarding rejected");
                    }
                    String weapon = index == 0 ? "DShK" : "Cannon";
                    vehicle.modifyGunData(weapon, data -> {
                        data.resetStatus(); data.ammo.set(50); data.virtualAmmo.set(0);
                        data.projectileBeltPhase.set(0);
                    });
                }
                observe(vehicles.get(0));
                EliteDiagnostics.record(observer, "tracer_check", "STARTED",
                        "suite", "mounted", "expected_shots", 32);
                observer.sendSystemMessage(Component.literal("Mounted tracer check started (40 seconds)."));
                return;
            }
            abrams = spawn("m1_abrams_elite", origin);
            bmpt = spawn("bmpt", origin.add(16, 0, 0));
            observe(abrams);
            EliteDiagnostics.record(observer, "tracer_check", "STARTED",
                    "expected_shots", 30, "server_simulation_hz", 20);
            observer.sendSystemMessage(Component.literal("Focused tracer check started (35 seconds)."));
        }

        VehicleEntity spawn(String id, Vec3 position) {
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(BertsVehiclePack.MODID, id));
            Entity entity = type == null ? null : type.create(level);
            if (!(entity instanceof VehicleEntity vehicle)) throw new IllegalStateException("Missing " + id);
            vehicle.load(new CompoundTag());
            vehicle.moveTo(position.x, position.y, position.z, 0, 0);
            vehicle.setNoGravity(true);
            vehicle.setEnergy(vehicle.getMaxEnergy());
            vehicle.addTag("bvp_tracer_check_fixture");
            if (!level.addFreshEntity(vehicle)) throw new IllegalStateException("Vehicle insertion failed");
            vehicles.add(vehicle);
            return vehicle;
        }

        void observe(VehicleEntity vehicle) {
            Vec3 position = vehicle.position();
            observer.teleportTo(level, position.x + 8, position.y + 5, position.z - 12, 34, 15);
        }

        void tick() {
            if (!privatePlayer(observer) || observer.serverLevel() != level || observer.getVehicle() != null
                    || vehicles.stream().anyMatch(Entity::isRemoved)
                    || System.nanoTime() - started > 60_000_000_000L)
                throw new IllegalStateException("Tracer fixture context lost");
            ticks++;
            if (mounted) {
                for (int mount = 0; mount < vehicles.size(); mount++) {
                    vehicles.get(mount).setPos(origin.add(mount * 18, 0, 0));
                    vehicles.get(mount).setDeltaMovement(Vec3.ZERO);
                }
                int index = (ticks - 1) / 200;
                int phase = (ticks - 1) % 200;
                if (index >= MOUNTS.length) { finish(null); return; }
                if (phase == 0) observe(vehicles.get(index));
                if (phase >= 30 && phase <= 170 && phase % 20 == 10)
                    fire(vehicles.get(index), index == 0 ? "DShK" : "Cannon", MOUNTS[index]);
                return;
            }
            if (ticks == 420) observe(bmpt);
            if (ticks >= 20 && ticks <= 120 && ticks % 20 == 0) fire(abrams, "Cannon", "cannon");
            if (ticks >= 160 && ticks <= 260 && ticks % 20 == 0) fire(abrams, "MachineGun", "coax");
            if (ticks >= 300 && ticks <= 400 && ticks % 20 == 0)
                fire(abrams, "PassengerMachineGun", "hmg");
            if (ticks >= 440 && ticks <= 540 && ticks % 20 == 0)
                fire(bmpt, "DualCannon", "autocannon_no_tracer");
            if (ticks == 560) {
                bmpt.modifyGunData("DualCannon", data -> {
                    data.changeAmmoConsumer(1, observer);
                    if (data.selectedAmmoType.get() != 1)
                        throw new IllegalStateException("HE belt selection failed");
                });
            }
            if (ticks >= 580 && ticks <= 680 && ticks % 20 == 0) fire(bmpt, "DualCannon", "autocannon");
            if (ticks >= 700) finish(null);
        }

        void fire(VehicleEntity vehicle, String weapon, String path) {
            if (!mounted) vehicle.modifyGunData(weapon, data -> {
                data.resetStatus(); data.reload.setPendingProgressPercent(0);
                data.ammo.set(1); data.virtualAmmo.set(0); data.heat.set(0); data.overHeat.set(false);
            });
            var muzzle = vehicle.resolveMuzzleFrame(weapon, 1.0F);
            // The native collision path excludes the seated shooter's own vehicle.
            // An ownerless diagnostic shot does not represent a player-operated weapon.
            var shooter = mounted ? vehicle.getPassengers().stream()
                    .filter(net.minecraft.world.entity.LivingEntity.class::isInstance)
                    .map(net.minecraft.world.entity.LivingEntity.class::cast)
                    .reduce((first, last) -> last).orElseThrow() : null;
            var result = vehicle.vehicleShootResult(shooter, weapon);
            if (result.isAccepted()) {
                accepted++;
                projectiles.addAll(result.getSpawnedProjectileIds());
                for (UUID id : result.getSpawnedProjectileIds()) {
                    Entity projectile = level.getEntity(id);
                    var profile = projectile == null ? null : ProjectileProfiles.resolve(projectile);
                    if (profile == null) throw new IllegalStateException("Missing accepted projectile profile");
                    EliteDiagnostics.record(projectile, "tracer_check", "PROJECTILE_POLICY",
                            "path", path, "profile", profile.getId(), "trail_mode", profile.getTrailMode(),
                            "shot_sequence", ProjectileProfiles.shotSequence(projectile));
                }
            } else failures++;
            EliteDiagnostics.record(vehicle, "tracer_check", "SHOT",
                    "path", path, "weapon", weapon, "accepted", result.isAccepted(),
                    "projectile_ids", result.getSpawnedProjectileIds(),
                    "muzzle", muzzle == null ? null : muzzle.getPosition(),
                    "direction", muzzle == null ? null : muzzle.getDirection());
        }

        void finish(String error) {
            if (finished) return;
            finished = true;
            active = null;
            String status = error == null && failures == 0 && accepted == (mounted ? 32 : 30) ? "PASS" : "FAIL";
            try {
                EliteDiagnostics.record(observer, "tracer_check", "FINISHED",
                        "status", status, "accepted", accepted, "rejected", failures, "error", error);
            } finally {
                for (UUID id : projectiles) {
                    Entity entity = level.getEntity(id);
                    if (entity != null) entity.discard();
                }
                for (var operator : operators) operator.discard();
                for (VehicleEntity vehicle : vehicles) vehicle.discard();
                observer.getAbilities().flying = savedFlying;
                observer.onUpdateAbilities();
                observer.teleportTo(level, savedPosition.x, savedPosition.y, savedPosition.z, savedYaw, savedPitch);
                if (captureOwned) EliteDiagnostics.INSTANCE.stop(server);
            }
            observer.sendSystemMessage(Component.literal("Tracer shot fixture " + status
                    + "; visual acceptance requires the matching client capture."));
        }
    }
}
