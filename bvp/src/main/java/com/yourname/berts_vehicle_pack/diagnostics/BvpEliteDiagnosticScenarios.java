package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;

import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.projectile.ProjectileEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModEntities;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.armor.BvpMaterialImpactSounds;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Opt-in real-world impact fixtures; assertions and rendered/audio evidence share one capture. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID)
public final class BvpEliteDiagnosticScenarios {
    private static Run active;
    private BvpEliteDiagnosticScenarios() { }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) return;
        event.getDispatcher().register(Commands.literal("bvp_elite_test")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    if (active != null) return 0;
                    var player = context.getSource().getPlayerOrException();
                    EliteDiagnostics.INSTANCE.start(player.server);
                    active = new Run(player);
                    try { active.prepare(); }
                    catch (RuntimeException failure) { fail(failure); return 0; }
                    return 1;
                }));
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || active == null || active.server != event.getServer()) return;
        try { active.tick(); } catch (RuntimeException failure) { fail(failure); }
    }

    private static void fail(RuntimeException failure) {
        if (active == null) return;
        active.record("SCENARIO_ERROR", "error", failure.toString());
        active.close("ERROR");
        active = null;
    }

    @SubscribeEvent
    public static void join(EntityJoinLevelEvent event) {
        if (active == null || event.getLevel() != active.level) return;
        if (event.getEntity() instanceof ProjectileEntity fragment && fragment.isImpactShrapnel()) {
            active.fragments.add(fragment);
            active.owned.add(fragment);
            EliteDiagnostics.record(fragment, "elite_suite", "FRAGMENT_SPAWN",
                    "velocity", fragment.getDeltaMovement(), "position", fragment.position(),
                    "lifetime", fragment.impactShrapnelLifetime(), "fixture", active.currentFixture);
        }
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
        final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        final List<Entity> owned = new ArrayList<>();
        final List<ProjectileEntity> fragments = new ArrayList<>();
        final List<ChunkPos> chunks = new ArrayList<>();
        final Map<VehicleEntity, Vec3> fixedVehicles = new LinkedHashMap<>();
        final TreeMap<Integer, List<Runnable>> actions = new TreeMap<>();
        VehicleEntity impactSource;
        int elapsed;
        int checks;
        int failures;
        String currentFixture = "setup";

        Run(ServerPlayer player) {
            server = player.server;
            level = player.serverLevel();
            observer = player;
            savedPosition = player.position();
            savedYaw = player.getYRot();
            savedPitch = player.getXRot();
            wasFlying = player.getAbilities().flying;
            origin = new Vec3(Math.floor(player.getX()), Math.floor(player.getY()) + 8, Math.floor(player.getZ()));
        }

        void prepare() {
            observer.stopRiding();
            // A prior interrupted diagnostic run may have saved its tagged fixtures.
            for (Entity entity : level.getAllEntities()) {
                if (entity.getTags().contains("bvp_elite_fixture")) entity.discard();
            }
            observer.getAbilities().flying = true;
            observer.onUpdateAbilities();
            observer.teleportTo(level, origin.x, origin.y + 4, origin.z - 8, 0, 14);
            level.setDayTime(2000);
            level.setWeatherParameters(6000, 0, false, false);
            record("SCENARIO_STARTED", "suite", "impacts_tracers", "origin", origin);
            impactSource = spawnVehicle("leo2a6", origin.add(40, 0, -12));
            Block[] surfaces = {Blocks.STONE, Blocks.OAK_PLANKS, Blocks.DIRT, Blocks.GLASS, Blocks.IRON_BLOCK};
            String[] names = {"stone", "wood", "dirt", "glass", "metal"};
            String[] profiles = {"leo2a6/machinegun/ammo_00_western_762_ap",
                    "mi28n/cannon/belt_ammo_00_3ubr6_ap_t", "leo2a6/cannon/ammo_00_dm53_apfsds"};
            for (int material = 0; material < surfaces.length; material++) {
                final Block surface = surfaces[material];
                final String name = names[material];
                final BlockPos center = BlockPos.containing(origin.add((material - 2) * 8, 0, 20));
                force(center.getX(), center.getZ());
                for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                    put(center.offset(x, 0, z), surface.defaultBlockState());
                }
                for (int caliber = 0; caliber < profiles.length; caliber++) {
                    final int c = caliber;
                    final String profile = profiles[caliber];
                    at(40 + (material * 3 + caliber) * 24, () -> {
                        // Restore the exact material after a prior penetrating shot breaks it.
                        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                            level.setBlockAndUpdate(center.offset(x, 0, z), surface.defaultBlockState());
                        }
                        currentFixture = name + "_caliber_" + c;
                        Vec3 hit = Vec3.atCenterOf(center).add(0, 0.501, 0);
                        observer.teleportTo(level, hit.x, hit.y + 3, hit.z - 8, 0, 20);
                        Vec3 incoming = c == 1 ? new Vec3(0, -1, 0) : new Vec3(0.8, -0.45, 0.6).normalize();
                        fireImpact(profile, hit.subtract(incoming.scale(5)), incoming, 2.0);
                    });
                }
            }
            at(420, () -> {
                currentFixture = "vehicle_metal";
                VehicleEntity target = spawnVehicle("leo2a6", origin.add(0, 0, 32));
                Vec3 hit = target.position().add(0, 1.3, -2);
                observer.teleportTo(level, hit.x + 4, hit.y + 2, hit.z - 8, 25, 12);
                fireImpact(profiles[0], hit.add(0, 0, -6), new Vec3(0, 0, 1), 2.0);
            });
            at(470, () -> {
                currentFixture = "live_tracers";
                VehicleEntity launcher = spawnVehicle("m1_abrams_elite", origin.add(0, 4, 0));
                observer.teleportTo(level, origin.x + 7, origin.y + 6, origin.z - 6, -15, 0);
                for (int shot = 0; shot < 6; shot++) {
                    at(500 + shot * 20, () -> {
                        launcher.modifyGunData("Cannon", data -> reset(data, 1, 0));
                        var result = launcher.vehicleShootResult(null, "Cannon");
                        check("normal_vehicle_tracer_launch", result.isAccepted());
                        record("TRACER_FIRED", "vehicle", launcher.getUUID(), "accepted", result.isAccepted());
                    });
                }
            });
            at(630, () -> {
                currentFixture = "cyclic_attacker_destruction";
                VehicleEntity victim = spawnVehicle("leo2a6", origin.add(36, 0, 36));
                victim.setLastAttackerUUID(victim.getUUID().toString());
                check("self_attacker_fixture_present", victim.getLastAttacker() == victim);
                record("CYCLIC_ATTACKER_DESTRUCTION_STARTED", "target", victim.getUUID());
                victim.destroy();
                at(660, () -> {
                    check("cyclic_attacker_destruction_finished", victim.isWreck());
                    record("CYCLIC_ATTACKER_DESTRUCTION_FINISHED", "target", victim.getUUID(),
                            "wreck", victim.isWreck(), "removed", victim.isRemoved());
                });
            });
            at(670, () -> {
                check("impact_fragments_remain_client_only", fragments.isEmpty());
                float small = BvpMaterialImpactSounds.volume(7.62);
                float medium = BvpMaterialImpactSounds.volume(30);
                float large = BvpMaterialImpactSounds.volume(120);
                check("caliber_volume_increases_below_engine_clamp", small < medium && medium < large && large < 1);
                close(failures == 0 ? "PASS" : "FAIL");
                active = null;
            });
        }

        void fireImpact(String profile, Vec3 start, Vec3 direction, double speed) {
            ProjectileEntity bullet = new ProjectileEntity(ModEntities.PROJECTILE.get(), level);
            // Block-impact admission requires the same BVP vehicle ownership as a fired round.
            bullet.shooter(impactSource);
            bullet.setPos(start);
            ProjectileProfiles.recordServerLaunchPosition(bullet, start);
            bullet.setDeltaMovement(direction.scale(speed));
            bullet.setGravity(0);
            bullet.setLife(18);
            bullet.setDamage(25);
            ProjectileProfiles.assign(bullet, new ResourceLocation(BertsVehiclePack.MODID, profile));
            check("impact_profile_present", ProjectileProfiles.combatDescriptor(bullet) != null);
            owned.add(bullet);
            check("impact_projectile_spawned", level.addFreshEntity(bullet));
            EliteDiagnostics.record(bullet, "elite_suite", "IMPACT_FIRED",
                    "fixture", currentFixture, "profile", profile, "direction", direction, "start", start);
        }

        VehicleEntity spawnVehicle(String id, Vec3 position) {
            force(position.x, position.z);
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(BertsVehiclePack.MODID, id));
            Entity entity = type == null ? null : type.create(level);
            if (!(entity instanceof VehicleEntity vehicle)) throw new IllegalStateException("Missing vehicle " + id);
            vehicle.load(new CompoundTag());
            vehicle.moveTo(position.x, position.y, position.z, 0, 0);
            vehicle.setNoGravity(true);
            vehicle.addTag("bvp_elite_fixture");
            vehicle.setEnergy(vehicle.getMaxEnergy());
            for (int slot = 0; slot < vehicle.getInventory().getSlots(); slot++) {
                vehicle.getInventory().setStackInSlot(slot, ItemStack.EMPTY);
            }
            for (String name : vehicle.getGunDataMap().keySet().toArray(String[]::new)) {
                vehicle.modifyGunData(name, data -> reset(data, 0, 0));
            }
            owned.add(vehicle);
            fixedVehicles.put(vehicle, position);
            if (!level.addFreshEntity(vehicle)) throw new IllegalStateException("Vehicle insertion failed");
            return vehicle;
        }

        static void reset(GunData data, int loaded, int reserve) {
            data.resetStatus();
            data.reload.setPendingProgressPercent(0);
            data.ammo.set(loaded);
            data.virtualAmmo.set(reserve);
            data.heat.set(0);
            data.overHeat.set(false);
        }

        void force(double x, double z) {
            ChunkPos pos = new ChunkPos((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
            if (!level.getForcedChunks().contains(pos.toLong())) {
                level.setChunkForced(pos.x, pos.z, true);
                chunks.add(pos);
            }
            level.getChunk(pos.x, pos.z);
        }

        void put(BlockPos pos, BlockState state) {
            blocks.putIfAbsent(pos.immutable(), level.getBlockState(pos));
            level.setBlockAndUpdate(pos, state);
        }

        void at(int tick, Runnable action) { actions.computeIfAbsent(tick, ignored -> new ArrayList<>()).add(action); }

        void tick() {
            elapsed++;
            // BVP vehicle physics has its own gravity; setNoGravity alone does not pin a fixture.
            fixedVehicles.forEach((vehicle, position) -> {
                if (!vehicle.isRemoved()) {
                    vehicle.setPos(position);
                    vehicle.setDeltaMovement(Vec3.ZERO);
                }
            });
            List<Runnable> ready = actions.remove(elapsed);
            if (ready != null) ready.forEach(Runnable::run);
            if (active == null) return;
            for (ProjectileEntity fragment : fragments) if (!fragment.isRemoved()) {
                EliteDiagnostics.record(fragment, "elite_suite", "FRAGMENT_TICK",
                        "age", fragment.tickCount, "position", fragment.position(),
                        "velocity", fragment.getDeltaMovement(), "lifetime", fragment.impactShrapnelLifetime());
            }
        }

        void check(String name, boolean pass) {
            checks++;
            if (!pass) failures++;
            record(pass ? "ASSERT_PASS" : "ASSERT_FAIL", "check", name, "fixture", currentFixture);
        }

        void record(String event, Object... fields) { EliteDiagnostics.record(observer, "elite_suite", event, fields); }

        void close(String status) {
            record("SCENARIO_COMPLETE", "suite", "impacts_tracers", "status", status,
                    "assertions", checks, "failures", failures);
            owned.forEach(entity -> { if (!entity.isRemoved()) entity.discard(); });
            blocks.forEach(level::setBlockAndUpdate);
            chunks.forEach(pos -> level.setChunkForced(pos.x, pos.z, false));
            observer.stopRiding();
            observer.getAbilities().flying = wasFlying;
            observer.onUpdateAbilities();
            observer.teleportTo(level, savedPosition.x, savedPosition.y, savedPosition.z, savedYaw, savedPitch);
            observer.sendSystemMessage(Component.literal("Elite impact/tracer diagnostics " + status
                    + ": " + checks + " assertions, " + failures + " failures."));
            EliteDiagnostics.INSTANCE.stop(server);
        }
    }
}
