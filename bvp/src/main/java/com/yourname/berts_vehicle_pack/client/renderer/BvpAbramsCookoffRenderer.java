package com.yourname.berts_vehicle_pack.client.renderer;

import com.yourname.berts_vehicle_pack.armor.BvpArmorFrames;
import com.yourname.berts_vehicle_pack.effects.BvpAbramsCookoff;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import com.yourname.berts_vehicle_pack.init.ModParticles;
import com.yourname.berts_vehicle_pack.particle.SizedExplosionParticleOptions;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Native and far render copies share the same authored turret-bustle outlets. */
final class BvpAbramsCookoffRenderer {
    private static final LinkedHashMap<UUID, State> STATES = new LinkedHashMap<>();
    private static ClientLevel budgetLevel;
    private static long budgetTick;
    private static int emissions;
    private static final class State { long first, last = Long.MIN_VALUE; State(long tick) { first=tick; } }
    static void emit(ArmoredVehicleEntity vehicle) {
        if (!vehicle.isWreck() || !BvpAbramsCookoff.applies(vehicle)
                || !(vehicle.level() instanceof ClientLevel level)) return;
        Vec3 center = BvpArmorFrames.rearTurretAmmoWorldCenter(vehicle);
        if (center == null) return;
        long tick=level.getGameTime();
        if (budgetLevel!=level) { STATES.clear(); budgetLevel=level; }
        State state=STATES.computeIfAbsent(vehicle.getUUID(), ignored -> new State(tick));
        while (STATES.size()>256) STATES.remove(STATES.keySet().iterator().next());
        if (tick-state.first>160 || tick==state.last) return;
        state.last=tick;
        if (budgetLevel!=level || budgetTick!=tick) { budgetLevel=level; budgetTick=tick; emissions=0; }
        if (emissions>=24) return;
        if (tick==state.first) level.addAlwaysVisibleParticle(new SizedExplosionParticleOptions(1.7f),true,
                center.x,center.y+.25,center.z,0,0,0);
        Vec3 side=vehicle.getBarrelVector(1).cross(new Vec3(0,1,0)).normalize();
        for (int outlet=-1;outlet<=1;outlet++) {
            Vec3 point=center.add(side.scale(outlet*.58)).add(0,outlet==0?.3:.08,0);
            Vec3 motion=side.scale(outlet*.055).add(0,outlet==0?.15:.06,0);
            level.addAlwaysVisibleParticle(ParticleTypes.FLAME,true,point.x,point.y,point.z,motion.x,motion.y,motion.z);
            emissions++;
            if (tick%3==0) level.addAlwaysVisibleParticle(ModParticles.WRECK_SMOKE.get(),true,
                    point.x,point.y,point.z,motion.x*.25,.055,motion.z*.25);
        }
    }
    private BvpAbramsCookoffRenderer() {}
}
