package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;

import com.atsuishio.superbwarfare.api.weapon.VehicleReloadAudio;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Opt-in development scenarios exercise normal server weapon ticks and the shared diagnostic sink. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID)
public final class BvpVehicleDiagnosticScenarios {
    private static Run active;

    private BvpVehicleDiagnosticScenarios() { }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) return;
        event.getDispatcher().register(Commands.literal("bvp_diagnostics")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("start").executes(context -> {
                    if (active != null) throw new IllegalStateException("A vehicle scenario is already active");
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    EliteDiagnostics.INSTANCE.start(player.server);
                    active = new Run(player);
                    try {
                        active.prepare();
                    } catch (RuntimeException exception) {
                        active.record("SCENARIO_ERROR", "exception", exception.toString());
                        active.close("FAILED");
                        active = null;
                        return 0;
                    }
                    context.getSource().sendSuccess(() -> Component.literal(
                            "Vehicle diagnostics started; results are written to Elite diagnostics."), false);
                    return 1;
                }))
                .then(Commands.literal("stop").executes(context -> {
                    if (active != null) active.close("STOPPED");
                    active = null;
                    return 1;
                })));
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || active == null || active.server != event.getServer()) return;
        try {
            active.tick();
        } catch (RuntimeException exception) {
            active.record("SCENARIO_ERROR", "exception", exception.toString());
            active.close("FAILED");
            active = null;
        }
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        if (active != null && active.server == event.getServer()) {
            active.close("SERVER_STOPPING");
            active = null;
        }
    }

    private static final class Run {
        private final MinecraftServer server;
        private final ServerLevel level;
        private final ServerPlayer observer;
        private final Vec3 origin;
        private final TreeMap<Integer, List<Runnable>> actions = new TreeMap<>();
        private final Map<String, VehicleEntity> vehicles = new LinkedHashMap<>();
        private final Map<String, String> weapons = new LinkedHashMap<>();
        private final List<ChunkPos> ownedForcedChunks = new ArrayList<>();
        private final List<VehicleEntity> distant = new ArrayList<>();
        private int elapsed;
        private int assertions;
        private int failures;

        private Run(ServerPlayer player) {
            server = player.server;
            level = player.serverLevel();
            observer = player;
            origin = new Vec3(player.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    player.blockPosition().getX(), player.blockPosition().getZ()), player.getZ());
        }

        private void prepare() {
            record("SCENARIO_STARTED", "origin", origin);
            observer.stopRiding();
            List<Entity> previousFixtures = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                if (entity.getTags().contains("bvp_diagnostic_fixture")
                        || entity.getTags().contains("bvp_visual_fixture")) previousFixtures.add(entity);
            }
            previousFixtures.forEach(Entity::discard);
            level.setDayTime(2000);
            level.setWeatherParameters(6000, 0, false, false);
            observer.teleportTo(level, origin.x, origin.y, origin.z, 0, -2);
            String[] ids = {"t72b", "m1128", "m48a3_elite", "m1_abrams_elite",
                    "m1a2_abrams_sep_v2", "leo2a6", "ztz99a"};
            for (int index = 0; index < ids.length; index++) {
                String id = ids[index];
                VehicleEntity vehicle = spawn(id, origin.x + (index - 3) * 12, origin.y, origin.z + 28);
                vehicles.put(id, vehicle);
                check(vehicle, "summon_component_health", vehicle.getTurretHealth() == vehicle.getTurretMaxHealth()
                        && vehicle.getLeftWheelHealth() == vehicle.getWheelMaxHealth()
                        && vehicle.getRightWheelHealth() == vehicle.getWheelMaxHealth()
                        && vehicle.getMainEngineHealth() == vehicle.getEngineMaxHealth()
                        && vehicle.getSubEngineHealth() == vehicle.getEngineMaxHealth());
                for (int slot = 0; slot < vehicle.getInventory().getSlots(); slot++) {
                    vehicle.getInventory().setStackInSlot(slot, ItemStack.EMPTY);
                }
                for (String name : vehicle.getGunDataMap().keySet().toArray(String[]::new)) {
                    vehicle.modifyGunData(name, data -> resetAmmo(data, 0, 0));
                }
                String weapon = id.equals("ztz99a")
                        ? vehicle.getGunDataMap().keySet().stream().filter(name -> name.startsWith("MachineGun")).findFirst().orElse(null)
                        : "PassengerMachineGun";
                check(vehicle, "weapon_present", weapon != null && vehicle.getGunData(weapon) != null);
                if (weapon == null || vehicle.getGunData(weapon) == null) continue;
                weapons.put(id, weapon);
                if (id.equals("ztz99a")) {
                    check(vehicle, "coax_only", vehicle.getGunDataMap().keySet().stream().noneMatch(name -> name.contains("Passenger")));
                }
                at(40, () -> {
                    vehicle.modifyGunData(weapon, data -> {
                        int cost = data.get(GunProp.AMMO_COST_PER_SHOOT);
                        resetAmmo(data, cost * 3, 0);
                        data.projectileBeltPhase.set(0);
                    });
                    GunData data = vehicle.getGunData(weapon);
                    check(vehicle, "positive_magazine", data.get(GunProp.MAGAZINE) > 0);
                    check(vehicle, "positive_reload", data.get(GunProp.EMPTY_RELOAD_TIME) > 0
                            && data.get(GunProp.NORMAL_RELOAD_TIME) > 0);
                });
                for (int shot = 0; shot < 4; shot++) {
                    int ordinal = shot;
                    at(60 + shot * 20, () -> fire(vehicle, weapon, ordinal < 3));
                }
                at(140, () -> vehicle.modifyGunData(weapon, data -> {
                    resetAmmo(data, 0, 13);
                    data.reload.setPendingProgressPercent(66);
                    data.startReload();
                }));
                at(340, () -> {
                    GunData data = vehicle.getGunData(weapon);
                    check(vehicle, "credited_reload_complete", !data.reloading() && data.ammo.get() == 13
                            && data.virtualAmmo.get() == 0);
                });
                if (vehicle.getGunData("Cannon") != null) {
                    at(360, () -> vehicle.modifyGunData("Cannon", data -> {
                        resetAmmo(data, 0, 1);
                        data.startReload();
                    }));
                    at(400, () -> {
                        VehicleReloadAudio.cancel(vehicle, "Cannon", "diagnostic_cancellation");
                        vehicle.modifyGunData("Cannon", data -> resetAmmo(data, 1, 0));
                        EliteDiagnostics.record(vehicle, "scenario", "CANCEL_REQUESTED", "weapon", "Cannon");
                    });
                    at(450, () -> vehicle.modifyGunData("Cannon", data -> {
                        resetAmmo(data, 0, 1);
                        data.startReload();
                    }));
                    at(660, () -> {
                        GunData data = vehicle.getGunData("Cannon");
                        check(vehicle, "next_reload_complete", !data.reloading() && data.ammo.get() == 1);
                    });
                }
            }
            at(350, () -> check(vehicles.get("m1_abrams_elite"), "audio_listener_mounted",
                    observer.startRiding(vehicles.get("m1_abrams_elite"), true)));
            at(530, () -> {
                observer.stopRiding();
                observer.teleportTo(level, origin.x, origin.y, origin.z, 0, -2);
                record("AUDIO_LISTENER_DISMOUNTED", "target_uuid", vehicles.get("m1_abrams_elite").getUUID());
            });
            at(680, () -> {
                for (String id : List.of("t72b", "m1_abrams_elite", "leo2a6")) {
                    ArmoredVehicleEntity vehicle = (ArmoredVehicleEntity) vehicles.get(id);
                    float rightBefore = vehicle.getModuleHealth("righttrack");
                    vehicle.damageModule("lefttrack", vehicle.position(), 1000);
                    check(vehicle, "single_track_damage", vehicle.isLeftTrackBroken() && !vehicle.isRightTrackBroken()
                            && vehicle.getModuleHealth("righttrack") == rightBefore);
                }
            });
            at(720, () -> {
                for (String id : List.of("t72b", "m1_abrams_elite", "leo2a6")) {
                    VehicleEntity vehicle = vehicles.get(id);
                    moveDistant(vehicle, origin.x + distant.size() * 18, origin.y, origin.z + 600);
                }
                VehicleEntity helicopter = spawn("mi28n", origin.x + 35, origin.y + 45, origin.z + 640);
                helicopter.setNoGravity(true);
                distant.add(helicopter);
                VehicleEntity aircraft = spawn("mig19", origin.x - 40, origin.y + 75, origin.z + 680);
                aircraft.setNoGravity(true);
                distant.add(aircraft);
                observer.teleportTo(level, origin.x, origin.y + 4, origin.z, 0, -2);
                record("FAR_STAGE", "entities", distant.stream().map(Entity::getUUID).toList());
            });
            at(900, () -> {
                int index = 0;
                for (VehicleEntity vehicle : distant) {
                    vehicle.setPos(origin.x + (index++ - 2) * 16, origin.y + (index > 3 ? 12 : 0), origin.z + 42);
                }
                record("NEAR_STAGE");
            });
            at(1060, () -> {
                for (VehicleEntity vehicle : distant) vehicle.discard();
                record("REMOVAL_STAGE");
            });
            at(1070, () -> {
                VehicleEntity vehicle = spawn("m1_abrams_elite", origin.x + 8, origin.y, origin.z + 8);
                vehicles.put("reload_completion", vehicle);
                check(vehicle, "completion_listener_mounted", observer.startRiding(vehicle, true));
            });
            at(1080, () -> vehicles.get("reload_completion").modifyGunData("Cannon", data -> {
                resetAmmo(data, 0, 1);
                data.startReload();
            }));
            at(1360, () -> {
                VehicleEntity vehicle = vehicles.get("reload_completion");
                GunData data = vehicle.getGunData("Cannon");
                check(vehicle, "mounted_normal_reload_complete", !data.reloading() && data.ammo.get() == 1);
                EliteDiagnostics.record(vehicle, "scenario", "NORMAL_RELOAD_COMPLETED", "weapon", "Cannon");
            });
            at(1380, () -> {
                observer.stopRiding();
                observer.teleportTo(level, origin.x, origin.y, origin.z, 0, -2);
            });
            at(1420, () -> {
                close(failures == 0 ? "PASS" : "FAIL");
                active = null;
            });
        }

        private static void resetAmmo(GunData data, int loaded, int reserve) {
            data.resetStatus();
            data.reload.setPendingProgressPercent(0);
            data.ammo.set(loaded);
            data.virtualAmmo.set(reserve);
            data.heat.set(0);
            data.overHeat.set(false);
        }

        private void fire(VehicleEntity vehicle, String weapon, boolean expectedAccepted) {
            GunData before = vehicle.getGunData(weapon);
            int ammo = before.ammo.get();
            int phase = before.projectileBeltPhase.get();
            var belt = before.get(GunProp.PROJECTILE_BELT);
            int cost = before.get(GunProp.AMMO_COST_PER_SHOOT);
            var result = vehicle.vehicleShootResult(null, weapon);
            GunData after = vehicle.getGunData(weapon);
            check(vehicle, expectedAccepted ? "loaded_shot_accepted" : "empty_shot_rejected",
                    result.isAccepted() == expectedAccepted);
            check(vehicle, "shot_ammo_accounting", after.ammo.get() == ammo - (result.isAccepted() ? cost : 0));
            int expectedPhase = result.isAccepted() && belt != null ? (phase + 1) % belt.cycleLength() : phase;
            check(vehicle, "belt_advances_only_on_accepted_shot", after.projectileBeltPhase.get() == expectedPhase);
            EliteDiagnostics.record(vehicle, "scenario", "SHOT_RESULT", "weapon", weapon,
                    "accepted", result.isAccepted(), "reason", result.getReason(),
                    "ammo_before", ammo, "ammo_after", after.ammo.get(), "phase_after", after.projectileBeltPhase.get());
        }

        private VehicleEntity spawn(String id, double x, double y, double z) {
            forceChunk(x, z);
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(BertsVehiclePack.MODID, id));
            Entity entity = type == null ? null : type.create(level);
            if (!(entity instanceof VehicleEntity vehicle)) throw new IllegalArgumentException("Missing vehicle " + id);
            // The command path loads sparse NBT before placement, unlike a bare type.create call.
            vehicle.load(new CompoundTag());
            vehicle.moveTo(x, y, z, 0, 0);
            vehicle.addTag("bvp_diagnostic_fixture");
            if (!level.addFreshEntity(vehicle)) throw new IllegalStateException("Vehicle spawn rejected: " + id);
            vehicle.setEnergy(vehicle.getMaxEnergy());
            EliteDiagnostics.record(vehicle, "scenario", "FIXTURE_SPAWNED");
            return vehicle;
        }

        private void moveDistant(VehicleEntity vehicle, double x, double y, double z) {
            forceChunk(x, z);
            vehicle.setPos(x, y, z);
            distant.add(vehicle);
        }

        private void forceChunk(double x, double z) {
            ChunkPos chunk = new ChunkPos((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
            if (!level.getForcedChunks().contains(chunk.toLong())) {
                level.setChunkForced(chunk.x, chunk.z, true);
                ownedForcedChunks.add(chunk);
            }
            level.getChunk(chunk.x, chunk.z);
        }

        private void at(int tick, Runnable action) {
            actions.computeIfAbsent(tick, ignored -> new ArrayList<>()).add(action);
        }

        private void tick() {
            elapsed++;
            List<Runnable> ready = actions.remove(elapsed);
            if (ready != null) ready.forEach(Runnable::run);
        }

        private void check(VehicleEntity vehicle, String name, boolean passed) {
            assertions++;
            if (!passed) failures++;
            EliteDiagnostics.record(vehicle, "scenario", passed ? "ASSERT_PASS" : "ASSERT_FAIL", "check", name);
        }

        private void record(String event, Object... fields) {
            EliteDiagnostics.record(observer, "scenario", event, fields);
        }

        private void close(String status) {
            record("SCENARIO_COMPLETE", "status", status, "assertions", assertions, "failures", failures);
            for (ChunkPos chunk : ownedForcedChunks) level.setChunkForced(chunk.x, chunk.z, false);
            ownedForcedChunks.clear();
            observer.sendSystemMessage(Component.literal("Vehicle diagnostics " + status + ": "
                    + assertions + " assertions, " + failures + " failures."));
            EliteDiagnostics.INSTANCE.stop(server);
        }
    }
}
