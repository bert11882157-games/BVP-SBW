package com.yourname.berts_vehicle_pack.effects;

import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.atsuishio.superbwarfare.api.projectile.impact.*;
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.network.message.receive.ShakeClientMessage;
import com.atsuishio.superbwarfare.tools.MinecraftUtil;
import com.atsuishio.superbwarfare.tools.ParticleTool;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.init.ModParticles;
import com.yourname.berts_vehicle_pack.armor.BvpArmorFrames;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.WeakHashMap;

/** Presentation of accepted vehicle hits; never causes extra damage or chunk loading. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID)
public final class BvpVehicleHitEffects {
    private record Hole(WeakReference<ArmoredVehicleEntity> vehicle, BvpArmorFrames.ImpactAnchor anchor, long until, double caliber) {}
    private static final WeakHashMap<ServerLevel, ArrayList<Hole>> HOLES = new WeakHashMap<>();
    private static final WeakHashMap<VehicleEntity, Long> LAST_SHAKE = new WeakHashMap<>();
    private BvpVehicleHitEffects() {}

    public static void impact(ProjectileImpactContext context, ProjectileImpactResult result) {
        if (!(context.getTarget() instanceof VehicleEntity vehicle)
                || !(vehicle.level() instanceof ServerLevel level)) return;
        var descriptor = ProjectileProfiles.combatDescriptor(context.getProjectile());
        double caliber = descriptor != null && descriptor.getCaliberMm() != null ? descriptor.getCaliberMm() : 12.7;
        if (!Double.isFinite(caliber) || caliber <= 0) return;
        boolean aircraft = vehicle.getVehicleType() == VehicleType.AIRPLANE || vehicle.getVehicleType() == VehicleType.HELICOPTER;
        long now = level.getGameTime();
        acceptedHit(vehicle, caliber);
        if (aircraft || result.getPresentationOutcome() != ProjectileImpactPresentationOutcome.PENETRATION) return;
        Vec3 point = context.getHitVec();
        BvpLeanImpactEffects.spawnCaliberVisual(level, point, (float)Math.min(1.1, .08 + caliber / 140));
        if (!(vehicle instanceof ArmoredVehicleEntity armored)) return;
        var anchor = BvpArmorFrames.captureImpactAnchor(context);
        if (anchor == null) return;
        var holes = HOLES.computeIfAbsent(level, ignored -> new ArrayList<>());
        if (holes.size() >= 64) holes.remove(0);
        holes.add(new Hole(new WeakReference<>(armored), anchor,
                now + (long)Math.min(50, 8 + caliber / 3), caliber));
    }

    /** Native weapon callbacks may report a confirmed hit without claiming armor penetration. */
    public static void acceptedHit(VehicleEntity vehicle, double caliber) {
        if (!(vehicle.level() instanceof ServerLevel level) || !Double.isFinite(caliber) || caliber <= 0) return;
        boolean aircraft = vehicle.getVehicleType() == VehicleType.AIRPLANE || vehicle.getVehicleType() == VehicleType.HELICOPTER;
        long now = level.getGameTime();
        if (now - LAST_SHAKE.getOrDefault(vehicle, Long.MIN_VALUE / 2) >= 2) {
            LAST_SHAKE.put(vehicle, now);
            double amplitude = BvpImpactShakeScale.amplitude(caliber, aircraft);
            for (var passenger : vehicle.getPassengers()) if (passenger instanceof ServerPlayer player) {
                MinecraftUtil.sendPacketTo(player, new ShakeClientMessage(aircraft ? 6 : 10, 8, amplitude,
                        vehicle.getX(), vehicle.getY(), vehicle.getZ()));
                com.atsuishio.superbwarfare.tools.SoundTool.playLocalSound(player,
                        com.yourname.berts_vehicle_pack.init.ModSounds.IMPACT_METAL.get(),
                        (float)Math.min(1.8, .22 + caliber / 100),
                        (float)Math.max(.65, 1.2 - caliber / 400));
            }
        }
    }

    @SubscribeEvent public static void tick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        var holes = HOLES.get(level); if (holes == null) return;
        long now = level.getGameTime(); int budget = 24;
        for (var it = holes.iterator(); it.hasNext();) {
            Hole hole = it.next(); var vehicle = hole.vehicle().get();
            if (vehicle == null || vehicle.isRemoved() || now >= hole.until()) { it.remove(); continue; }
            if (now % (hole.caliber() < 40 ? 5 : 2) != 0 || budget-- <= 0) continue;
            Vec3 point = hole.anchor().world(vehicle);
            if (point == null) { it.remove(); continue; }
            // Count zero emits one particle with explicit upward velocity, rather than a random fan.
            ParticleTool.sendParticle(level, ModParticles.IMPACT_SMOKE.get(), point.x,point.y,point.z,
                    0,0,.018 + Math.min(.035,hole.caliber()/4000),0,1,true);
        }
    }
}
