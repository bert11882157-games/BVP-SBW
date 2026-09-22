package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.projectile.ProjectileEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.tools.InventoryTool;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Private finite-ammunition acceptance through the real shot transaction and server reload ticks. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID)
public final class BvpExpansionWeaponScenario {
    private static Run active;
    private BvpExpansionWeaponScenario() { }

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
        event.getDispatcher().register(Commands.literal("bvp_expansion_weapon_test")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("vehicle", StringArgumentType.word())
                        .then(Commands.argument("weaponCount", IntegerArgumentType.integer(0, 32))
                                .then(Commands.argument("reload", BoolArgumentType.bool()).executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                    if (active != null || !privateOperator(player) || player.getVehicle() != null
                                            || EliteDiagnostics.isServerEnabled()) return 0;
                                    String id = StringArgumentType.getString(context, "vehicle");
                                    if (!id.matches("[a-z0-9_]{1,64}")) return 0;
                                    active = new Run(player, id, IntegerArgumentType.getInteger(context, "weaponCount"),
                                            BoolArgumentType.getBool(context, "reload"));
                                    try { active.prepare(); }
                                    catch (RuntimeException failure) { active.finish("ERROR", failure.toString()); }
                                    return active == null ? 0 : 1;
                                })))));
        event.getDispatcher().register(Commands.literal("bvp_expansion_weapon_stop")
                .requires(source -> source.hasPermission(2)).executes(context -> {
                    if (active == null) return 0;
                    active.finish("STOPPED", "Operator request"); return 1;
                }));
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        Run run = active;
        if (run == null || run.server != event.getServer()) return;
        try {
            if (event.phase == TickEvent.Phase.START) run.holdFixture();
            else run.tick();
        } catch (RuntimeException failure) { run.finish("ERROR", failure.toString()); }
    }

    @SubscribeEvent
    public static void spawned(EntityJoinLevelEvent event) {
        Run run = active;
        if (run == null || event.getLevel() != run.level || !run.firing) return;
        Entity entity = event.getEntity();
        run.projectiles.add(entity);
        run.record("PROJECTILE_SPAWN", "weapon", run.weapon(), "type", entity.getType().toString(),
                "position", entity.position(), "velocity", entity.getDeltaMovement());
        run.check("shrapnel_not_a_server_bullet", !(entity instanceof ProjectileEntity projectile)
                || !projectile.isImpactShrapnel());
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        if (active != null && active.server == event.getServer()) active.finish("STOPPED", "Server stopping");
    }

    private enum Phase { SETTLE, GEAR_UP, FIRE, RELOAD_CANCEL, RELOAD_CANCEL_GAP, RELOAD_COMPLETE, NEXT, GEAR_DOWN }

    private static final class Run {
        final ServerPlayer player;
        final MinecraftServer server;
        final ServerLevel level;
        final String id;
        final int expectedWeapons;
        final boolean testReload;
        final Vec3 savedPosition;
        final float savedYaw, savedPitch;
        final boolean savedFlying;
        final List<Entity> projectiles = new ArrayList<>();
        List<String> weapons = List.of();
        VehicleEntity vehicle;
        Vec3 anchor;
        Phase phase = Phase.SETTLE;
        int phaseTicks, elapsed, weaponIndex, checks, failures, shotsRemaining, shotWait;
        int extendedBoxes, reloadDeadline;
        float healthBefore;
        boolean firing, captureOwned, reloadExercised;

        Run(ServerPlayer player, String id, int expectedWeapons, boolean testReload) {
            this.player = player; this.server = player.server; this.level = player.serverLevel();
            this.id = id; this.expectedWeapons = expectedWeapons; this.testReload = testReload;
            savedPosition = player.position(); savedYaw = player.getYRot(); savedPitch = player.getXRot();
            savedFlying = player.getAbilities().flying;
        }

        void prepare() {
            var type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(BertsVehiclePack.MODID, id));
            Entity created = type == null ? null : type.create(level);
            if (!(created instanceof VehicleEntity candidate)) throw new IllegalArgumentException("Missing vehicle " + id);
            vehicle = candidate;
            anchor = new Vec3(savedPosition.x, Math.min(level.getMaxBuildHeight() - 40, savedPosition.y + 60), savedPosition.z + 24);
            level.getChunk((int) Math.floor(anchor.x) >> 4, (int) Math.floor(anchor.z) >> 4);
            vehicle.load(new CompoundTag()); vehicle.moveTo(anchor.x, anchor.y, anchor.z, 0, 0);
            vehicle.addTag("bvp_expansion_weapon_fixture");
            if (!level.addFreshEntity(vehicle)) throw new IllegalStateException("Vehicle insertion failed");
            vehicle.setEnergy(vehicle.getMaxEnergy());
            for (int slot = 0; slot < vehicle.getInventory().getSlots(); slot++)
                vehicle.getInventory().setStackInSlot(slot, ItemStack.EMPTY);
            for (String weapon : vehicle.getGunDataMap().keySet().toArray(String[]::new))
                vehicle.modifyGunData(weapon, data -> reset(data, 0, 0));
            player.teleportTo(level, anchor.x + 8, anchor.y + 3, anchor.z - 4, 0, 0);
            player.getAbilities().flying = true; player.onUpdateAbilities();
            EliteDiagnostics.INSTANCE.start(server); captureOwned = true;
            record("SCENARIO_STARTED", "vehicle", id, "expected_weapons", expectedWeapons,
                    "motion_forced", true, "scope", "weapon_and_gear_acceptance_not_flight_dynamics");
        }

        String weapon() { return weaponIndex < weapons.size() ? weapons.get(weaponIndex) : "none"; }
        GunData gun() { return vehicle.getGunData(weapon()); }
        void enter(Phase next) { phase = next; phaseTicks = 0; }

        void holdFixture() {
            if (vehicle == null || vehicle.isRemoved() || !player.isAlive())
                throw new IllegalStateException("Fixture or observer no longer available");
            // This fixture measures weapons and gear; flight trajectories use a separate unfrozen scenario.
            vehicle.setPos(anchor); vehicle.setDeltaMovement(Vec3.ZERO); vehicle.setOnGround(false);
        }

        void tick() {
            elapsed++; phaseTicks++;
            if (elapsed > 9000) throw new IllegalStateException("Weapon fixture deadline exceeded");
            switch (phase) {
                case SETTLE -> {
                    if (phaseTicks < 25) return;
                    LinkedHashSet<String> bound = new LinkedHashSet<>();
                    for (var seat : vehicle.computed().seats()) bound.addAll(seat.weapons());
                    weapons = List.copyOf(bound);
                    check("bound_weapon_count", weapons.size() == expectedWeapons);
                    for (String name : weapons) check("bound_weapon_exists_" + name, vehicle.getGunData(name) != null);
                    if (failures > 0 || weapons.isEmpty()) { finish(failures == 0 ? "PASS" : "FAIL", "Weapon census"); return; }
                    check("operator_mounted", player.startRiding(vehicle, true));
                    if (player.getVehicle() != vehicle) throw new IllegalStateException("Weapon operator mount failed");
                    if (InventoryTool.hasCreativeAmmoBox(vehicle))
                        throw new IllegalStateException("Finite-ammunition fixture cannot use a creative ammo box");
                    healthBefore = vehicle.getHealth();
                    extendedBoxes = vehicle.getOBBs().size();
                    if (vehicle.hasFixedWingLandingGear()) {
                        for (String name : weapons) if (vehicle.getGunData(name).get(GunProp.REQUIRES_RETRACTED_LANDING_GEAR)) {
                            vehicle.modifyGunData(name, data -> reset(data, data.get(GunProp.AMMO_COST_PER_SHOOT), 0));
                            int before = vehicle.getGunData(name).ammo.get();
                            check("gear_down_blocks_" + name, !vehicle.vehicleShootResult(player, name).isAccepted());
                            check("gear_block_preserves_ammo", vehicle.getGunData(name).ammo.get() == before);
                        }
                        selectSeat(0);
                        check("gear_up_request", vehicle.requestFixedWingLandingGearToggle(player));
                        enter(Phase.GEAR_UP);
                    } else beginWeapon();
                }
                case GEAR_UP -> {
                    if (phaseTicks < 90) return;
                    check("gear_fully_retracted", vehicle.getSynchedGearRot() == 1F);
                    check("gear_collision_removed", vehicle.getOBBs().size() < extendedBoxes);
                    beginWeapon();
                }
                case FIRE -> {
                    if (--shotWait > 0) return;
                    boolean expected = shotsRemaining > 0;
                    fire(expected);
                    if (shotsRemaining-- > 0) shotWait = interval();
                    else if (testReload && !reloadExercised && !gun().useBackpackAmmo()) {
                        reloadExercised = true;
                        beginReload(true);
                    }
                    else enter(Phase.NEXT);
                }
                case RELOAD_CANCEL -> {
                    if (phaseTicks == 1) check("reload_started", gun().reloading());
                    if (phaseTicks < Math.max(1, reloadDeadline / 2)) return;
                    check("reload_active_before_cancel", gun().reloading());
                    vehicle.modifyGunData(weapon(), data -> reset(data, 0, 0));
                    check("reload_canceled", !gun().reloading());
                    record("RELOAD_CANCELED", "weapon", weapon());
                    enter(Phase.RELOAD_CANCEL_GAP);
                }
                case RELOAD_CANCEL_GAP -> {
                    check("canceled_reload_stays_stopped", !gun().reloading());
                    if (phaseTicks >= 20) beginReload(false);
                }
                case RELOAD_COMPLETE -> {
                    if (phaseTicks == 1) check("reload_started", gun().reloading());
                    if (phaseTicks < reloadDeadline + 5) return;
                    check("reload_completes", !gun().reloading() && gun().ammo.get() == 1 && gun().virtualAmmo.get() == 0);
                    record("RELOAD_COMPLETED", "weapon", weapon()); enter(Phase.NEXT);
                }
                case NEXT -> {
                    if (phaseTicks < 10) return;
                    check("firing_did_not_damage_own_hull", vehicle.getHealth() >= healthBefore - 0.001F);
                    record("HULL_RESULT", "weapon", weapon(), "health_before", healthBefore,
                            "health_after", vehicle.getHealth());
                    if (++weaponIndex < weapons.size()) beginWeapon();
                    else if (vehicle.hasFixedWingLandingGear()) {
                        selectSeat(0);
                        check("gear_down_request", vehicle.requestFixedWingLandingGearToggle(player)); enter(Phase.GEAR_DOWN);
                    } else finish(failures == 0 ? "PASS" : "FAIL", "All bound weapons checked");
                }
                case GEAR_DOWN -> {
                    if (phaseTicks < 90) return;
                    check("gear_fully_extended", vehicle.getSynchedGearRot() == 0F);
                    check("gear_collision_restored", vehicle.getOBBs().size() == extendedBoxes);
                    finish(failures == 0 ? "PASS" : "FAIL", "Weapons and gear checked");
                }
            }
        }

        int interval() { return Math.max(5, (int) Math.ceil(1200.0 / Math.max(1, gun().get(GunProp.RPM))) + 5); }
        void selectSeat(int seat) {
            if (vehicle.getSeatIndex(player) != seat && !vehicle.changeSeat(player, seat))
                throw new IllegalStateException("Weapon station unavailable: " + seat);
            check("operator_in_requested_seat", vehicle.getSeatIndex(player) == seat);
        }
        void beginWeapon() {
            int station = -1;
            var seats = vehicle.computed().seats();
            for (int index = 0; index < seats.size(); index++) {
                if (seats.get(index).weapons().contains(weapon())) { station = index; break; }
            }
            if (station < 0) throw new IllegalStateException("Weapon has no operator station: " + weapon());
            selectSeat(station);
            healthBefore = vehicle.getHealth();
            int cost = gun().get(GunProp.AMMO_COST_PER_SHOOT), magazine = gun().get(GunProp.MAGAZINE);
            boolean reserveFed = gun().useBackpackAmmo();
            check("valid_ammo_supply_" + weapon(), cost > 0 && (reserveFed || magazine >= cost));
            if (cost <= 0 || (!reserveFed && magazine < cost))
                throw new IllegalStateException("Invalid ammunition cost or magazine capacity");
            shotsRemaining = reserveFed ? 3 : Math.min(3, magazine / cost);
            int rounds = Math.multiplyExact(shotsRemaining, cost);
            vehicle.modifyGunData(weapon(), data -> {
                reset(data, reserveFed ? 0 : rounds, reserveFed ? rounds : 0);
                data.projectileBeltPhase.set(0);
            });
            check("exact_initial_supply", gun().currentAvailableAmmo(vehicle.getAmmoSupplier()) == rounds);
            record("AMMO_SUPPLY", "weapon", weapon(), "mode", reserveFed ? "RESERVE" : "MAGAZINE",
                    "rounds", rounds, "reload_applicable", !reserveFed);
            shotWait = interval(); enter(Phase.FIRE);
        }

        void fire(boolean expected) {
            int before = gun().currentAvailableAmmo(vehicle.getAmmoSupplier());
            int loadedBefore = gun().ammo.get(), reserveBefore = gun().virtualAmmo.get();
            int beltPhase = gun().projectileBeltPhase.get(), spawned = projectiles.size();
            var belt = gun().get(GunProp.PROJECTILE_BELT);
            int cost = gun().get(GunProp.AMMO_COST_PER_SHOOT);
            firing = true;
            try {
                if (player.getVehicle() != vehicle) throw new IllegalStateException("Weapon operator dismounted");
                var result = vehicle.vehicleShootResult(player, weapon());
                check(expected ? "loaded_shot_accepted" : "empty_shot_rejected", result.isAccepted() == expected);
                check("exact_ammo_debit", gun().currentAvailableAmmo(vehicle.getAmmoSupplier())
                        == before - (result.isAccepted() ? cost : 0));
                check("inactive_supply_preserved", gun().useBackpackAmmo()
                        ? gun().ammo.get() == loadedBefore : gun().virtualAmmo.get() == reserveBefore);
                int expectedPhase = result.isAccepted() && belt != null ? (beltPhase + 1) % belt.cycleLength() : beltPhase;
                check("belt_advances_only_for_shot", gun().projectileBeltPhase.get() == expectedPhase);
                check("accepted_shot_spawns_projectile", !result.isAccepted() || projectiles.size() > spawned);
                check("rejected_shot_has_no_projectile", result.isAccepted() || projectiles.size() == spawned);
                record("SHOT_RESULT", "weapon", weapon(), "accepted", result.isAccepted(), "reason", result.getReason(),
                        "ammo_before", before, "ammo_after", gun().currentAvailableAmmo(vehicle.getAmmoSupplier()),
                        "loaded_before", loadedBefore, "loaded_after", gun().ammo.get(),
                        "reserve_before", reserveBefore, "reserve_after", gun().virtualAmmo.get(),
                        "projectiles", projectiles.size() - spawned);
            } finally { firing = false; }
        }

        void beginReload(boolean cancel) {
            reloadDeadline = gun().get(GunProp.EMPTY_RELOAD_TIME);
            if (reloadDeadline < 2 || reloadDeadline > 1200) throw new IllegalStateException("Reload deadline outside fixture bounds");
            vehicle.modifyGunData(weapon(), data -> { reset(data, 0, 1); data.startReload(); });
            // The request becomes an active reload during the next normal gun tick.
            check("reload_request_queued", gun().reload.reloadStarter.shouldStart());
            enter(cancel ? Phase.RELOAD_CANCEL : Phase.RELOAD_COMPLETE);
        }

        static void reset(GunData data, int ammo, int reserve) {
            data.resetStatus(); data.reload.setPendingProgressPercent(0);
            data.ammo.set(ammo); data.virtualAmmo.set(reserve); data.heat.set(0); data.overHeat.set(false);
        }
        void check(String name, boolean passed) {
            checks++; if (!passed) failures++;
            record(passed ? "ASSERT_PASS" : "ASSERT_FAIL", "check", name, "weapon", weapon());
        }
        void record(String event, Object... fields) { EliteDiagnostics.record(player, "expansion_weapon", event, fields); }
        void finish(String status, String reason) {
            record("SCENARIO_COMPLETE", "status", status, "reason", reason, "vehicle", id,
                    "assertions", checks, "failures", failures, "ticks", elapsed);
            player.stopRiding();
            for (Entity projectile : projectiles) projectile.discard();
            if (vehicle != null) vehicle.discard();
            player.teleportTo(level, savedPosition.x, savedPosition.y, savedPosition.z, savedYaw, savedPitch);
            player.getAbilities().flying = savedFlying; player.onUpdateAbilities();
            player.sendSystemMessage(Component.literal("Expansion weapons " + id + " " + status + ": "
                    + checks + " checks, " + failures + " failures. " + reason));
            if (captureOwned) EliteDiagnostics.INSTANCE.stop(server);
            active = null;
        }
    }
}
