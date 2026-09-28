package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.api.aircraft.AircraftMissileLauncher;
import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;
import com.atsuishio.superbwarfare.api.vehicle.weapon.GroundSamLauncher;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.protocol.game.ClientboundPlayerLookAtPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Diagnostic ({@code bvp.diagnostics.scenarios}): {@code /bvp_sam <player> <weapon> <target type> <distance> <height>}.
 * The player rides a ground SAM vehicle as its first crew member. A target of the given type is summoned ahead of
 * the vehicle, held aloft and drifting sideways; the player's view is turned onto it (the turret follows), the lock
 * is logged, and the named weapon fires once the lock is ready (or after 8 s regardless; a ground target is given
 * 3 s to settle first). The log then follows the
 * FFA interceptors and the target's health: {@code [BVP sam]}.
 */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID)
public final class BvpSamScenario {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final List<Run> RUNS = new ArrayList<>();

    private static final class Run {
        final ServerPlayer player;
        final VehicleEntity vehicle;
        final String weapon;
        final Entity target;
        final Vec3 drift;
        final float startHealth;
        /** A target summoned on the ground is briefly off the ground while it settles; hold fire until then. */
        final int minFireAge;
        int age;
        int firedAt = -1;

        Run(ServerPlayer player, VehicleEntity vehicle, String weapon, Entity target, Vec3 drift) {
            this.player = player;
            this.vehicle = vehicle;
            this.weapon = weapon;
            this.target = target;
            this.drift = drift;
            this.startHealth = health(target);
            this.minFireAge = drift.lengthSqr() > 0 ? 0 : 60;
        }
    }

    private BvpSamScenario() {
    }

    private static float health(Entity entity) {
        return entity instanceof VehicleEntity vehicle ? vehicle.getHealth() : -1;
    }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) return;
        event.getDispatcher().register(Commands.literal("bvp_sam")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("weapon", StringArgumentType.word())
                                .then(Commands.argument("target", ResourceLocationArgument.id())
                                        .then(Commands.argument("distance", IntegerArgumentType.integer(16, 1000))
                                                .then(Commands.argument("height", IntegerArgumentType.integer(0, 300))
                                                        .executes(context -> start(
                                                                EntityArgument.getPlayer(context, "player"),
                                                                StringArgumentType.getString(context, "weapon"),
                                                                ResourceLocationArgument.getId(context, "target"),
                                                                IntegerArgumentType.getInteger(context, "distance"),
                                                                IntegerArgumentType.getInteger(context, "height")))))))));
    }

    private static int start(ServerPlayer player, String weapon, ResourceLocation targetType, int distance, int height) {
        if (!(player.getVehicle() instanceof VehicleEntity vehicle)) {
            LOGGER.info("[BVP sam] {} is not riding a vehicle", player.getName().getString());
            return 0;
        }
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(targetType);
        if (type == null) return 0;
        int seat = vehicle.getSeatIndex(player);
        for (int i = 0; i < 8; i++) if (weapon.equals(vehicle.getGunName(seat, i))) vehicle.changeWeapon(seat, i, false);
        Vec3 forward = vehicle.getShootVec(weapon, 1f);
        Vec3 flat = new Vec3(forward.x, 0, forward.z);
        flat = flat.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : flat.normalize();
        Vec3 at = vehicle.position().add(flat.scale(distance)).add(0, height, 0);
        Entity target = type.spawn(player.serverLevel(), net.minecraft.core.BlockPos.containing(at), MobSpawnType.COMMAND);
        if (target == null) return 0;
        target.moveTo(at.x, at.y, at.z, 0, 0);
        Vec3 drift = new Vec3(-flat.z, 0, flat.x).scale(height > 0 ? 0.5 : 0);
        RUNS.add(new Run(player, vehicle, weapon, target, drift));
        LOGGER.info("[BVP sam] start {} {} target {} at {} m, {} m up; definition={} ffa={}", vehicle.getType(), weapon,
                targetType, distance, height, GroundSamLauncher.INSTANCE.definition(vehicle) != null,
                AircraftMissileLauncher.INSTANCE.available());
        return 1;
    }

    private static int interceptors(Run run) {
        int count = 0;
        for (Entity e : run.player.serverLevel().getEntities((Entity) null,
                run.vehicle.getBoundingBox().inflate(700), e -> e.getClass().getName().equals("dev.ballistics.InterceptorEntity")))
            count++;
        return count;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || RUNS.isEmpty()) return;
        RUNS.removeIf(run -> {
            run.age++;
            boolean gone = run.target.isRemoved();
            if (gone || run.age > 500 || (run.firedAt >= 0 && run.age - run.firedAt > 240) || run.player.getVehicle() != run.vehicle) {
                LOGGER.info("[BVP sam] end age={} target removed={} health {} -> {} interceptors={}", run.age, gone,
                        run.startHealth, health(run.target), interceptors(run));
                if (!gone) run.target.discard();
                return true;
            }
            if (run.drift.lengthSqr() > 0) run.target.setDeltaMovement(run.drift);
            if (run.firedAt < 0) run.player.connection.send(new ClientboundPlayerLookAtPacket(
                    EntityAnchorArgument.Anchor.EYES, run.target, EntityAnchorArgument.Anchor.EYES));
            var state = AircraftMissileLauncher.INSTANCE.state(run.vehicle, GroundSamLauncher.CHANNEL_PREFIX + run.weapon);
            boolean ready = state.getBoolean("Ready");
            if (run.age % 10 == 0) {
                Vec3 bore = run.vehicle.getShootVec(run.weapon, 1f);
                Vec3 los = run.target.getBoundingBox().getCenter().subtract(run.vehicle.getBoundingBox().getCenter());
                double off = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, bore.normalize().dot(los.normalize())))));
                LOGGER.info("[BVP sam] t={} selected={} lock target={} progress={} ready={} bore-off={}deg range={} "
                                + "target y={} airborne={} health={} interceptors={}", run.age,
                        run.vehicle.getGunName(run.vehicle.getSeatIndex(run.player)), state.hasUUID("TargetUUID"),
                        String.format("%.2f", state.getDouble("Progress")), ready, String.format("%.1f", off),
                        String.format("%.0f", los.length()), String.format("%.1f", run.target.getY()),
                        !run.target.onGround(), health(run.target), interceptors(run));
            }
            if (run.firedAt < 0 && ((ready && run.age >= run.minFireAge) || run.age >= 160)) {
                run.vehicle.modifyGunData(run.weapon, data -> {
                    data.resetStatus();
                    data.ammo.set(Math.max(1, data.ammo.get()));
                });
                int before = interceptors(run);
                var result = run.vehicle.vehicleShootResult(run.player, run.weapon);
                run.firedAt = run.age;
                LOGGER.info("[BVP sam] fire t={} ready={} result={} interceptors {} -> {}", run.age, ready, result,
                        before, interceptors(run));
            }
            return false;
        });
    }
}
