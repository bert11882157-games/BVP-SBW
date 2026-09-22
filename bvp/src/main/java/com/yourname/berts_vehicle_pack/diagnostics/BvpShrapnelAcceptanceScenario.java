package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;

import com.atsuishio.superbwarfare.api.projectile.ImpactFragmentSpawnSpec;
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.projectile.ProjectileEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModEntities;
import com.atsuishio.superbwarfare.item.gun.ProjectileFactory;
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
import net.minecraft.world.entity.projectile.Projectile;
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

/** Focused in-world checks for client-only impact fragments and retained parent damage. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID)
public final class BvpShrapnelAcceptanceScenario {
    private static Run active;

    private BvpShrapnelAcceptanceScenario() { }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) return;
        event.getDispatcher().register(Commands.literal("bvp_shrapnel_check")
                .requires(source -> source.hasPermission(2)).executes(context -> {
                    if (active != null) return 0;
                    var player = context.getSource().getPlayerOrException();
                    EliteDiagnostics.INSTANCE.start(player.server);
                    active = new Run(player);
                    try { active.prepare(); }
                    catch (RuntimeException failure) { active.finish(failure.toString()); return 0; }
                    return 1;
                }));
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || active == null || active.server != event.getServer()) return;
        try { active.tick(); } catch (RuntimeException failure) { active.finish(failure.toString()); }
    }

    @SubscribeEvent
    public static void join(EntityJoinLevelEvent event) {
        if (active == null || active.level != event.getLevel() || event.isCanceled()
                || !(event.getEntity() instanceof Projectile projectile)) return;
        active.projectiles.add(projectile);
        if (projectile instanceof ProjectileEntity bullet && bullet.isImpactShrapnel()) active.serverFragments++;
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        if (active != null && active.server == event.getServer()) active.finish("Server stopped");
    }

    private static final class Run {
        final ServerPlayer observer;
        final MinecraftServer server;
        final ServerLevel level;
        final Vec3 saved;
        final float savedYaw;
        final float savedPitch;
        final Vec3 origin;
        final List<Projectile> projectiles = new ArrayList<>();
        final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        VehicleEntity vehicle;
        Cow target;
        int ticks;
        int checks;
        int failures;
        int serverFragments;
        int accepted;

        Run(ServerPlayer observer) {
            this.observer = observer;
            this.server = observer.server;
            this.level = observer.serverLevel();
            this.saved = observer.position();
            this.savedYaw = observer.getYRot();
            this.savedPitch = observer.getXRot();
            this.origin = saved.add(40, 0, 40);
        }

        void prepare() {
            observer.stopRiding();
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(
                    new ResourceLocation(BertsVehiclePack.MODID, "bmpt"));
            Entity entity = type == null ? null : type.create(level);
            if (!(entity instanceof VehicleEntity created)) throw new IllegalStateException("BMPT unavailable");
            vehicle = created;
            vehicle.load(new CompoundTag());
            vehicle.moveTo(origin.x, origin.y, origin.z, 0, 0);
            vehicle.setNoGravity(true);
            vehicle.setEnergy(vehicle.getMaxEnergy());
            vehicle.addTag("bvp_shrapnel_check_fixture");
            if (!level.addFreshEntity(vehicle)) throw new IllegalStateException("BMPT insertion failed");
            vehicle.modifyGunData("DualCannon", data -> {
                data.resetStatus();
                data.reload.setPendingProgressPercent(0);
                data.ammo.set(850);
                data.virtualAmmo.set(0);
                data.heat.set(0);
                data.overHeat.set(false);
            });
            observer.teleportTo(level, origin.x + 7, origin.y + 2, origin.z - 7, -20, 0);
            if (!observer.startRiding(vehicle, true)) throw new IllegalStateException("BMPT shooter could not board");
            EliteDiagnostics.record(observer, "shrapnel_check", "STARTED");
        }

        void tick() {
            ticks++;
            if (vehicle.isRemoved()) throw new IllegalStateException("BMPT disappeared");
            vehicle.setPos(origin.x, origin.y, origin.z);
            vehicle.setDeltaMovement(Vec3.ZERO);
            vehicle.setYRot(0);
            vehicle.setXRot(0);
            vehicle.setTurretYRot(0);
            vehicle.setTurretXRot(0);
            if (ticks == 20) {
                var muzzle = vehicle.resolveMuzzleFrame("DualCannon", 1.0F);
                if (muzzle == null || Math.abs(muzzle.getDirection().z) < 0.9) {
                    throw new IllegalStateException("BMPT muzzle alignment invalid");
                }
                BlockPos center = BlockPos.containing(muzzle.getPosition().add(muzzle.getDirection().scale(20)));
                for (int x = -4; x <= 4; x++) for (int y = -3; y <= 3; y++) {
                    BlockPos position = center.offset(x, y, 0);
                    blocks.put(position.immutable(), level.getBlockState(position));
                    level.setBlockAndUpdate(position, Blocks.BEDROCK.defaultBlockState());
                }
            }
            if (ticks >= 40 && ticks <= 140 && ticks % 10 == 0) {
                var result = vehicle.vehicleShootResult(observer, "DualCannon");
                check("bmpt_primary_accepted", result.isAccepted());
                if (result.isAccepted()) accepted++;
            }
            if (ticks == 160) {
                target = EntityType.COW.create(level);
                if (target == null) throw new IllegalStateException("Damage target unavailable");
                target.moveTo(origin.x + 10, origin.y, origin.z + 10, 0, 0);
                target.setNoAi(true);
                target.addTag("bvp_shrapnel_check_fixture");
                if (!level.addFreshEntity(target)) throw new IllegalStateException("Damage target insertion failed");
                var result = vehicle.vehicleShootResult(observer, "DualCannon");
                check("parent_damage_shot_accepted", result.isAccepted());
                int redirected = 0;
                for (var id : result.getSpawnedProjectileIds()) {
                    Entity projectile = level.getEntity(id);
                    if (projectile instanceof Projectile) {
                        projectile.setPos(target.getX(), target.getY() + 0.7, target.getZ() - 2);
                        projectile.setDeltaMovement(0, 0, 2);
                        redirected++;
                    }
                }
                check("parent_damage_projectile_present", redirected > 0);
            }
            if (ticks == 190) {
                check("primary_projectile_still_damages", target != null && target.getHealth() < target.getMaxHealth());
                check("zero_server_impact_fragment_entities", serverFragments == 0);
                check("bmpt_wall_shots_executed", accepted == 11);
                legacyChecks();
            }
            if (ticks >= 220) finish(null);
        }

        @SuppressWarnings("deprecation")
        void legacyChecks() {
            ResourceLocation templateId = new ResourceLocation(BertsVehiclePack.MODID,
                    "bmp2/mainmachinegun/belt_ammo_00_russian_762_ap_t");
            var template = ProjectileProfiles.resolve(templateId);
            check("legacy_template_available", template != null);
            int before = projectiles.size();
            check("legacy_factory_overload_disabled", !ProjectileFactory.spawnImpactShrapnel(level, vehicle,
                    origin.add(0, 20, 0), new Vec3(0, 1, 0), 1, 12, 1, templateId, 0.4F, 1));
            if (template != null) {
                var spec = new ImpactFragmentSpawnSpec(origin.add(0, 20, 0), new Vec3(0, 1, 0),
                        1, 12, 1, template, 0.4F, 1);
                check("legacy_factory_spec_disabled", !ProjectileFactory.spawnImpactShrapnel(level, vehicle, spec));
            }
            check("legacy_factories_insert_nothing", projectiles.size() == before);
            ProjectileEntity marked = new ProjectileEntity(ModEntities.PROJECTILE.get(), level);
            marked.markImpactShrapnel();
            marked.tick();
            check("marked_fragment_retires_before_tick", marked.isRemoved() && marked.tickCount == 0);
            ProjectileEntity source = new ProjectileEntity(ModEntities.PROJECTILE.get(), level);
            CompoundTag saved = new CompoundTag();
            source.saveWithoutId(saved);
            CompoundTag data = saved.getCompound("SbwProjectileData");
            data.putBoolean("ImpactShrapnel", true);
            saved.put("SbwProjectileData", data);
            ProjectileEntity restored = new ProjectileEntity(ModEntities.PROJECTILE.get(), level);
            restored.load(saved);
            check("persisted_fragment_retires_on_load", restored.isRemoved());
        }

        void check(String name, boolean passed) {
            checks++;
            if (!passed) failures++;
            EliteDiagnostics.record(observer, "shrapnel_check", "ASSERTION", "name", name, "passed", passed);
        }

        void finish(String error) {
            if (active != this) return;
            if (error != null) failures++;
            String status = failures == 0 ? "PASS" : "FAIL";
            EliteDiagnostics.record(observer, "shrapnel_check", "COMPLETE", "status", status,
                    "checks", checks, "failures", failures, "error", error,
                    "server_fragments", serverFragments, "accepted_wall_shots", accepted);
            for (Projectile projectile : projectiles) if (!projectile.isRemoved()) projectile.discard();
            observer.stopRiding();
            if (vehicle != null) vehicle.discard();
            if (target != null && !target.isRemoved()) target.discard();
            blocks.forEach(level::setBlockAndUpdate);
            observer.teleportTo(level, saved.x, saved.y, saved.z, savedYaw, savedPitch);
            observer.sendSystemMessage(Component.literal("Shrapnel acceptance " + status + ": " + checks + " checks."));
            active = null;
            EliteDiagnostics.INSTANCE.stop(server);
        }
    }
}
