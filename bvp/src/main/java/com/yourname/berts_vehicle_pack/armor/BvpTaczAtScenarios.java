package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.entity.EntityKineticBullet;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Opt-in live TaCZ launcher acceptance, using the normal gun operator and collision pipeline. */
public final class BvpTaczAtScenarios {
    private static Run active;
    private BvpTaczAtScenarios() { }
    public static void register() {
        if (Boolean.getBoolean("bvp.diagnostics.scenarios")) MinecraftForge.EVENT_BUS.register(BvpTaczAtScenarios.class);
    }

    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("bvp_tacz_at_test").requires(source -> source.hasPermission(2))
                .executes(context -> {
                    if (active != null) return 0;
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    EliteDiagnostics.INSTANCE.start(player.server);
                    active = new Run(player);
                    active.record("SCENARIO_STARTED", "suite", "tacz_at");
                    return 1;
                }));
    }

    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (active == null || event.phase != TickEvent.Phase.END || event.getServer() != active.player.server) return;
        try { active.tick(); }
        catch (RuntimeException failure) {
            active.record("SCENARIO_ERROR", "error", failure.toString());
            active.close("ERROR");
        }
    }

    private static final class Run {
        final ServerPlayer player;
        final ServerLevel level;
        final Vec3 savedPosition;
        final float savedYaw, savedPitch;
        final boolean wasFlying;
        final ItemStack savedHand;
        final Vec3 origin;
        final List<Entity> owned = new ArrayList<>();
        final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        ArmoredVehicleEntity target;
        Vec3 targetPosition, aim;
        String caseId;
        float healthBefore;
        int elapsed, checks, failures;

        Run(ServerPlayer player) {
            this.player = player;
            level = player.serverLevel();
            savedPosition = player.position();
            savedYaw = player.getYRot(); savedPitch = player.getXRot();
            wasFlying = player.getAbilities().flying;
            savedHand = player.getMainHandItem().copy();
            origin = savedPosition.add(0, 12, 0);
            player.stopRiding();
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        }

        void tick() {
            int index = elapsed / 100, phase = elapsed % 100;
            if (index >= 4) { close(failures == 0 ? "PASS" : "FAIL"); return; }
            if (phase == 0) prepare(index);
            IGunOperator.fromLivingEntity(player).aim(true);
            if (target != null && !target.isRemoved()) {
                target.setPos(targetPosition);
                target.setDeltaMovement(Vec3.ZERO);
            }
            if (phase == 40) fire(index);
            if (phase == 90) {
                if (index == 1) check("handheld_rpg_hull_damage_109", target != null && !target.isRemoved()
                        && Math.abs((healthBefore - target.getHealth()) - 109.0) < .02);
                if (index == 2) check("400mm_chemical_blocked_by_1000mm_plate", target != null
                        && Math.abs(healthBefore - target.getHealth()) < .001);
                record("CASE_COMPLETE", "case", caseId, "health_before", healthBefore,
                        "health_after", target == null ? null : target.getHealth());
                if (target != null) target.discard();
                target = null;
            }
            elapsed++;
        }

        void prepare(int index) {
            caseId = new String[]{"rpg_stone", "rpg_weak_armor", "rpg_strong_armor", "ordinary_rifle"}[index];
            ResourceLocation gunId = new ResourceLocation("tacz", index == 3 ? "ak47" : "rpg7");
            check("vanilla_gun_index_present", TimelessAPI.getCommonGunIndex(gunId).isPresent());
            ItemStack gun = GunItemBuilder.create().setId(gunId).setAmmoCount(1)
                    .setAmmoInBarrel(true).setFireMode(FireMode.SEMI).build();
            check("vanilla_gun_item_created", !gun.isEmpty());
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, gun);
            IGunOperator operator = IGunOperator.fromLivingEntity(player);
            operator.initialData();
            operator.draw(player::getMainHandItem);
            if (index == 1 || index == 2) {
                targetPosition = origin.add(index * 24, 0, 20);
                var type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(BertsVehiclePack.MODID, "leo2a6"));
                target = type == null ? null : (ArmoredVehicleEntity) type.create(level);
                if (target == null) throw new IllegalStateException("Missing Leopard armor fixture");
                target.load(new CompoundTag());
                target.moveTo(targetPosition.x, targetPosition.y, targetPosition.z, 0, 0);
                target.setEnergy(target.getMaxEnergy());
                for (int slot = 0; slot < target.getInventory().getSlots(); slot++) target.getInventory().setStackInSlot(slot, ItemStack.EMPTY);
                for (String name : target.getGunDataMap().keySet().toArray(String[]::new)) target.modifyGunData(name,
                        data -> { data.resetStatus(); data.ammo.set(0); data.virtualAmmo.set(0); });
                level.addFreshEntity(target); owned.add(target);
                healthBefore = target.getHealth();
                String plateId = index == 1 ? "leo2a6_85mm_armor_26" : "leo2a6_1000mm_armor_00";
                var plate = ArmorProfiles.get("leo2a6").plates.stream().filter(p -> p.name.equals(plateId)).findFirst().orElseThrow();
                var coordinates = ArmorTargetAdapters.resolve(target);
                aim = coordinates.armorLocalPointToWorld(plate.centroid());
                // Approach along the plate's armor face (its largest face pointing along the hint),
                // which works for box and mesh volumes alike.
                var hint = index == 1 ? new ArmorProfiles.Vec(-1, 0, 0) : new ArmorProfiles.Vec(0, 0, -1);
                var face = plate.volume.dominantFaceNormal(hint);
                var normal = face == null ? hint : face;
                Vec3 eye = coordinates.armorLocalPointToWorld(plate.centroid().add(normal.scale(12)));
                positionPlayer(eye, aim);
                record("ARMOR_FIXTURE", "case", caseId, "plate", plateId, "target", target.getUUID(), "aim", aim);
            } else {
                BlockPos center = BlockPos.containing(origin.add(index * 24, 0, 20));
                for (int x = -4; x <= 4; x++) for (int y = -3; y <= 3; y++) for (int z = -4; z <= 4; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    blocks.putIfAbsent(pos.immutable(), level.getBlockState(pos));
                    if (y == 0) level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
                }
                aim = Vec3.atCenterOf(center).add(0, .501, 0);
                positionPlayer(aim.add(0, 4, -10), aim);
            }
            record("CASE_STARTED", "case", caseId, "gun", gunId);
        }

        void positionPlayer(Vec3 eye, Vec3 hit) {
            Vec3 direction = hit.subtract(eye);
            float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z)));
            player.teleportTo(level, eye.x, eye.y - player.getEyeHeight(), eye.z, yaw, pitch);
        }

        void fire(int index) {
            Set<UUID> before = new HashSet<>();
            for (Entity entity : level.getAllEntities()) if (entity instanceof EntityKineticBullet) before.add(entity.getUUID());
            check("gun_aim_fully_settled", IGunOperator.fromLivingEntity(player).getSynAimingProgress() == 1.0f);
            ShootResult result = IGunOperator.fromLivingEntity(player).shoot(player::getXRot, player::getYRot);
            check("actual_gun_operator_fired", result == ShootResult.SUCCESS);
            int found = 0;
            for (Entity entity : level.getAllEntities()) if (entity instanceof EntityKineticBullet bullet && !before.contains(entity.getUUID())) {
                found++; owned.add(bullet);
                var combat = ProjectileProfiles.combatDescriptor(bullet);
                check("projectile_policy_selected", index == 3 ? ProjectileProfiles.profileId(bullet) == null
                        : combat != null && combat.getCaliberMm() == 110 && combat.getPenetrationMm() == 400
                        && combat.getHullDamage() == 109);
                EliteDiagnostics.record(bullet, "tacz_at_suite", "SHOT_FIRED", "case", caseId, "gun", bullet.getGunId(),
                        "profile", ProjectileProfiles.profileId(bullet), "position", bullet.position(), "motion", bullet.getDeltaMovement());
            }
            check("actual_tacz_projectile_created", found == 1);
        }

        void check(String name, boolean passed) {
            checks++; if (!passed) failures++;
            record(passed ? "ASSERT_PASS" : "ASSERT_FAIL", "check", name, "case", caseId);
        }
        void record(String event, Object... fields) { EliteDiagnostics.record(player, "tacz_at_suite", event, fields); }
        void close(String status) {
            record("SCENARIO_COMPLETE", "suite", "tacz_at", "status", status, "assertions", checks, "failures", failures);
            owned.forEach(entity -> { if (!entity.isRemoved()) entity.discard(); });
            blocks.forEach(level::setBlockAndUpdate);
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, savedHand);
            IGunOperator.fromLivingEntity(player).initialData();
            IGunOperator.fromLivingEntity(player).draw(player::getMainHandItem);
            player.getAbilities().flying = wasFlying; player.onUpdateAbilities();
            player.teleportTo(level, savedPosition.x, savedPosition.y, savedPosition.z, savedYaw, savedPitch);
            player.sendSystemMessage(Component.literal("TaCZ AT diagnostics " + status + ": " + checks + " assertions, " + failures + " failures."));
            EliteDiagnostics.INSTANCE.stop(player.server);
            active = null;
        }
    }
}
