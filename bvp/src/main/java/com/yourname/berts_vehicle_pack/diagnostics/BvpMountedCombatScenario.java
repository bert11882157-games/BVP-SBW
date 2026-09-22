package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.api.item.gun.FireMode;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
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

/** Bounded disposable-world tests of real projectile collision, damage and seated crew protection. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID)
public final class BvpMountedCombatScenario {
    private static Run active;
    private static final List<String> TYPES = List.of("toyota_jihad_dshk", "toyota_jihad_spg9",
            "toyota_jihad_bmp1", "toyota_jihad_s5", "zu23_2", "kord_tripod",
            "browning_tripod", "milan_tripod", "tow_tripod", "spg9_tripod");
    private record Fixture(String type, int seat, boolean exposed) { }
    private BvpMountedCombatScenario() { }

    private static boolean admitted(ServerPlayer player) {
        MinecraftServer server = player.server;
        return DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")
                && server.isDedicatedServer() && !server.usesAuthentication()
                && "127.0.0.1".equals(server.getLocalIp()) && server.getPort() == BvpFireTrafficControl.PORT
                && server.getPlayerCount() == 1 && player.level().dimension() == Level.OVERWORLD
                && player.isAlive() && !player.isSpectator() && player.getAbilities().instabuild
                && BvpFireTrafficControl.identity(player.getGameProfile().getName(), player.getUUID())
                && BvpFireTrafficControl.loopback(player.connection.connection.getRemoteAddress(), false);
    }

    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) return;
        event.getDispatcher().register(Commands.literal("bvp_mounted_combat")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("suite", StringArgumentType.word()).executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    String suite = StringArgumentType.getString(context, "suite");
                    if (active != null || !admitted(player) || player.getVehicle() != null
                            || EliteDiagnostics.isServerEnabled()
                            || !List.of("cannon", "machinegun", "crew_sbw", "crew_tacz").contains(suite)) return 0;
                    Run run = new Run(player, suite); active = run;
                    try { run.start(); }
                    catch (RuntimeException failure) { run.finish(failure.toString()); return 0; }
                    return 1;
                })));
    }

    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        Run run = active;
        if (run == null || event.phase != TickEvent.Phase.END || event.getServer() != run.player.server) return;
        try { run.tick(); } catch (RuntimeException failure) { run.finish(failure.toString()); }
    }

    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        if (active != null && active.player.server == event.getServer()) active.finish("Server stopping");
    }

    private static final class Run {
        final ServerPlayer player;
        final ServerLevel level;
        final String suite;
        final Vec3 savedPosition, origin;
        final float savedYaw, savedPitch;
        final boolean savedFlying;
        final ItemStack savedHand;
        final List<Entity> owned = new ArrayList<>();
        final List<Entity> caseOwned = new ArrayList<>();
        final List<Fixture> fixtures;
        final long started = System.nanoTime();
        VehicleEntity target, launcher;
        Villager occupant;
        Vec3 aim;
        float crewHealth;
        int tick, checks, failures, shots;
        boolean captureOwned, finished;

        Run(ServerPlayer player, String suite) {
            this.player = player; this.level = player.serverLevel(); this.suite = suite;
            savedPosition = player.position(); savedYaw = player.getYRot(); savedPitch = player.getXRot();
            savedFlying = player.getAbilities().flying; savedHand = player.getMainHandItem().copy();
            origin = savedPosition.add(64, 24, 0);
            fixtures = suite.startsWith("crew_") ? List.of(
                    new Fixture("kord_tripod", 0, true), new Fixture("zu23_2", 0, true),
                    new Fixture("toyota_jihad_dshk", 2, true), new Fixture("toyota_jihad_dshk", 0, false),
                    new Fixture("toyota_jihad_bmp1", 2, false))
                    : (suite.equals("cannon") ? TYPES : List.of("kord_tripod", "zu23_2", "toyota_jihad_dshk"))
                    .stream().map(type -> new Fixture(type, -1, false)).toList();
        }

        void start() {
            player.getAbilities().flying = true; player.onUpdateAbilities();
            EliteDiagnostics.INSTANCE.start(player.server); captureOwned = true;
            launcher = vehicle(suite.equals("cannon") ? "m1_abrams_elite" : "kord_tripod", origin.add(0, 0, -24));
            record("STARTED", "suite", suite, "cases", fixtures.size(),
                    "sbw_route", "native gun factory; fixture retargets projectile before its first physics tick");
        }

        VehicleEntity vehicle(String id, Vec3 position) {
            var type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(BertsVehiclePack.MODID, id));
            Entity entity = type == null ? null : type.create(level);
            if (!(entity instanceof VehicleEntity vehicle)) throw new IllegalStateException("Missing " + id);
            vehicle.load(new CompoundTag()); vehicle.moveTo(position.x, position.y, position.z, 0, 0);
            vehicle.setNoGravity(true); vehicle.setEnergy(vehicle.getMaxEnergy());
            if (!level.addFreshEntity(vehicle)) throw new IllegalStateException("Insertion rejected");
            owned.add(vehicle); EliteDiagnostics.includeServerEntity(vehicle.getUUID());
            return vehicle;
        }

        void prepare(Fixture fixture) {
            shots = 0; occupant = null;
            target = vehicle(fixture.type, origin); caseOwned.add(target);
            for (int index = 0; index <= fixture.seat; index++) {
                // Peaceful disposable worlds discard hostile mobs even with NoAI enabled.
                Villager crew = EntityType.VILLAGER.create(level);
                if (crew == null) throw new IllegalStateException("Crew fixture missing");
                crew.setNoAi(true); crew.setSilent(true); crew.setPos(origin);
                if (index < fixture.seat) { crew.setInvisible(true); crew.setInvulnerable(true); }
                if (!level.addFreshEntity(crew) || !crew.startRiding(target, true))
                    throw new IllegalStateException("Crew boarding rejected");
                owned.add(crew); caseOwned.add(crew); occupant = crew;
                EliteDiagnostics.includeServerEntity(crew.getUUID());
            }
            if (occupant != null) crewHealth = occupant.getHealth();
            if (suite.equals("crew_tacz")) {
                ItemStack gun = GunItemBuilder.create().setId(new ResourceLocation("tacz", "ak47"))
                        .setAmmoCount(4).setAmmoInBarrel(true).setFireMode(FireMode.SEMI).build();
                if (gun.isEmpty()) throw new IllegalStateException("TaCZ AK47 unavailable");
                player.setItemInHand(InteractionHand.MAIN_HAND, gun);
                IGunOperator.fromLivingEntity(player).initialData();
                IGunOperator.fromLivingEntity(player).draw(player::getMainHandItem);
            }
            record("CASE_STARTED", "type", fixture.type, "seat", fixture.seat,
                    "expected_exposed", fixture.exposed, "target", target.getUUID(), "hp", target.getHealth());
        }

        void aim(Fixture fixture) {
            if (occupant != null && (!occupant.isAlive() || occupant.getVehicle() != target
                    || target.getSeatIndex(occupant) != fixture.seat))
                throw new IllegalStateException("Crew fixture removed or displaced before firing; check NPC spawning policy");
            if (occupant != null) aim = occupant.position().add(0, occupant.getBbHeight() * 0.65, 0);
            else {
                var box = target.getOBBs().stream().findFirst().orElseThrow();
                aim = new Vec3(box.center.x, box.center.y, box.center.z);
            }
            Vec3 eye = aim.add(12, 0, 0);
            Vec3 direction = aim.subtract(eye);
            float yaw = (float)Math.toDegrees(Math.atan2(-direction.x, direction.z));
            player.teleportTo(level, eye.x, eye.y - player.getEyeHeight(), eye.z, yaw, 0);
            if (occupant != null) check("seat_exposure_classification",
                    target.exposesPassengerToFire(occupant) == fixture.exposed);
            if (occupant != null) record("SEAT_STATE", "riding", occupant.getVehicle() == target,
                    "seat", target.getSeatIndex(occupant), "alive", occupant.isAlive(),
                    "exposed", target.exposesPassengerToFire(occupant));
        }

        void fire() {
            shots++;
            if (suite.equals("crew_tacz")) {
                check("tacz_actual_shot", IGunOperator.fromLivingEntity(player)
                        .shoot(player::getXRot, player::getYRot) == ShootResult.SUCCESS);
                return;
            }
            launcher.modifyGunData("Cannon", data -> {
                data.resetStatus(); data.ammo.set(1); data.virtualAmmo.set(0);
            });
            var result = launcher.vehicleShootResult(null, "Cannon");
            record("SHOT_RESULT", "accepted", result.isAccepted(), "result", result.toString(),
                    "launcher_hp", launcher.getHealth(), "launcher_wreck", launcher.isWreck());
            check("sbw_actual_shot", result.isAccepted() && !result.getSpawnedProjectileIds().isEmpty());
            for (var id : result.getSpawnedProjectileIds()) {
                Entity projectile = level.getEntity(id);
                if (projectile == null) throw new IllegalStateException("Accepted shot lost entity");
                Vec3 from = aim.add(12, 0, 0);
                // Retarget direction only: lowering bullet speed also reduces native damage.
                double speed = projectile.getDeltaMovement().length();
                if (!Double.isFinite(speed) || speed <= 0) throw new IllegalStateException("Invalid shot velocity");
                projectile.setPos(from); projectile.setDeltaMovement(aim.subtract(from).normalize().scale(speed));
                owned.add(projectile); caseOwned.add(projectile);
                record("PROJECTILE_IN_FLIGHT", "projectile", id, "from", from, "aim", aim);
            }
        }

        void tick() {
            if (!admitted(player) || player.serverLevel() != level || player.getVehicle() != null
                    || System.nanoTime() - started > 120_000_000_000L)
                throw new IllegalStateException("Private fixture context lost");
            int index = tick / 80, phase = tick % 80;
            if (index >= fixtures.size()) { finish(null); return; }
            // Native vehicle gravity is independent of Entity.noGravity. Hold the firing
            // fixture clear of the ground so impact damage cannot destroy the test launcher.
            if (launcher != null && !launcher.isRemoved()) {
                launcher.setPos(origin.add(0, 0, -24));
                launcher.setDeltaMovement(Vec3.ZERO);
            }
            Fixture fixture = fixtures.get(index);
            if (phase == 0) prepare(fixture);
            if (target != null && !target.isRemoved()) { target.setPos(origin); target.setDeltaMovement(Vec3.ZERO); }
            if (phase == 20) aim(fixture);
            if (suite.equals("crew_tacz")) IGunOperator.fromLivingEntity(player).aim(true);
            if (phase == 40) fire();
            if (suite.equals("machinegun") && phase > 40 && phase <= 64 && phase % 8 == 0
                    && !target.isRemoved() && target.getHealth() > 0) fire();
            if (phase == 75) {
                if (occupant == null) {
                    if (suite.equals("cannon")) {
                        check("one_tank_round_kills", target.isRemoved() || target.getHealth() <= 0);
                        if (fixture.type.startsWith("toyota_") || fixture.type.equals("zu23_2"))
                            check("light_vehicle_leaves_wreck", !target.isRemoved() && target.isWreck());
                    } else {
                        check("hmg_damages_unarmored_hull", target.getHealth() < target.getMaxHealth());
                        if (fixture.type.startsWith("toyota_") || fixture.type.equals("zu23_2"))
                            check("light_vehicle_survives_short_hmg_burst", !target.isRemoved() && target.getHealth() > 0);
                    }
                }
                else check(fixture.exposed ? "exposed_crew_takes_damage" : "enclosed_crew_protected",
                        fixture.exposed ? occupant.getHealth() < crewHealth : occupant.getHealth() == crewHealth);
                record("CASE_FINISHED", "type", fixture.type, "shots", shots,
                        "hp", target.getHealth(), "removed", target.isRemoved(),
                        "crew_hp", occupant == null ? null : occupant.getHealth());
                caseOwned.forEach(Entity::discard); caseOwned.clear();
            }
            tick++;
        }

        void check(String name, boolean passed) {
            checks++; if (!passed) failures++;
            record(passed ? "ASSERT_PASS" : "ASSERT_FAIL", "check", name, "case", tick / 80);
        }
        void record(String event, Object... fields) {
            EliteDiagnostics.record(player, "mounted_combat", event, fields);
        }
        void finish(String error) {
            if (finished) return; finished = true; active = null;
            String status = error == null && failures == 0 ? "PASS" : "FAIL";
            try { record("FINISHED", "suite", suite, "status", status, "checks", checks,
                    "failures", failures, "error", error); }
            finally {
                owned.forEach(Entity::discard);
                player.setItemInHand(InteractionHand.MAIN_HAND, savedHand);
                if (suite.equals("crew_tacz")) {
                    IGunOperator.fromLivingEntity(player).initialData();
                    IGunOperator.fromLivingEntity(player).draw(player::getMainHandItem);
                }
                player.getAbilities().flying = savedFlying; player.onUpdateAbilities();
                player.teleportTo(level, savedPosition.x, savedPosition.y, savedPosition.z, savedYaw, savedPitch);
                if (captureOwned) EliteDiagnostics.INSTANCE.stop(player.server);
            }
            player.sendSystemMessage(Component.literal("Mounted combat " + suite + ": " + status));
        }
    }
}
