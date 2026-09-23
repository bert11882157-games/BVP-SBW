package com.yourname.berts_vehicle_pack.effects;

import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.atsuishio.superbwarfare.api.projectile.ProjectileHullDamageClass;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactContext;
import com.atsuishio.superbwarfare.tools.ParticleTool;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.init.ModParticles;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.ArrayList;
import java.util.WeakHashMap;

/** Accepted-impact decoration only. Never creates damaging fragments, explosions or chunk tickets. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID)
public final class BvpBallisticImpactEffects {
    private static final int MAX_DEBRIS_PER_IMPACT=24, MAX_DEBRIS_PER_TICK=128, MAX_PENDING=64;
    private record Pending(long due, Vec3 point, float diameter) {}
    private static final class State {
        long tick=Long.MIN_VALUE; int debris;
        final ArrayList<Pending> pending=new ArrayList<>();
    }
    private static final WeakHashMap<ServerLevel,State> STATES=new WeakHashMap<>();
    private BvpBallisticImpactEffects() {}

    public static int debrisCount(double caliber) {
        if(!Double.isFinite(caliber)||caliber<12.7)return 0;
        return Math.min(MAX_DEBRIS_PER_IMPACT,4+(int)Math.min(20,Math.floor((caliber-12.7)/5)));
    }
    public static boolean secondary(double caliber,boolean highExplosive) {
        return Double.isFinite(caliber)&&caliber>80&&!highExplosive;
    }
    public static void impact(ProjectileImpactContext context,Vec3 normal,boolean highExplosive) {
        var source=context.getProjectile();
        if(!(source.level() instanceof ServerLevel level))return;
        var combat=ProjectileProfiles.combatDescriptor(source);
        if(combat==null||combat.getCaliberMm()==null)return;
        double caliber=combat.getCaliberMm();
        if(!Double.isFinite(caliber)||caliber<=0)return;
        String munition=combat.getMunitionType()==null?"":combat.getMunitionType().toString();
        if(combat.getHullDamageClass()==ProjectileHullDamageClass.ATGM||munition.contains("rocket")
                ||munition.contains("missile")||munition.contains("atgm")||munition.contains("bomb"))return;
        Vec3 point=context.getHitVec().add(normal.scale(.06));
        int smoke=Math.min(18,6+(int)Math.min(12,caliber/8));
        ParticleTool.sendParticle(level,ModParticles.IMPACT_SMOKE.get(),point.x,point.y,point.z,
                smoke,.12,.08,.12,.024,true);
        State state=STATES.computeIfAbsent(level,ignored->new State());
        long now=level.getGameTime();
        if(state.tick!=now){state.tick=now;state.debris=0;}
        if(context.getKind()==ProjectileImpactContext.Kind.BLOCK&&!context.getBlockState().isAir()){
            int count=Math.min(debrisCount(caliber),MAX_DEBRIS_PER_TICK-state.debris);
            if(count>0){
                state.debris+=count;
                ParticleTool.sendParticle(level,new BlockParticleOption(ParticleTypes.BLOCK,context.getBlockState()),
                        point.x,point.y,point.z,count,.08,.06,.08,.10,true);
            }
        }
        if(secondary(caliber,highExplosive)&&state.pending.size()<MAX_PENDING){
            Vec3 incoming=context.getIncomingVelocity();
            if(Double.isFinite(incoming.lengthSqr())&&incoming.lengthSqr()>1e-10){
                float diameter=BvpCaliberExplosion.diameter(caliber)*.45f;
                Vec3 next=context.getHitVec().add(incoming.normalize().scale(.55));
                state.pending.add(new Pending(now+1,next,diameter));
            }
        }
    }
    @SubscribeEvent public static void tick(TickEvent.LevelTickEvent event){
        if(event.phase!=TickEvent.Phase.END||!(event.level instanceof ServerLevel level))return;
        State state=STATES.get(level);if(state==null)return;
        long now=level.getGameTime();
        for(var it=state.pending.iterator();it.hasNext();){
            Pending p=it.next();if(p.due()>now)continue;it.remove();
            // Frozen coordinates, no retained projectile and no block/entity damage.
            BvpLeanImpactEffects.spawnCaliberVisual(level,p.point(),p.diameter());
            ParticleTool.sendParticle(level,ModParticles.IMPACT_SMOKE.get(),p.point().x,p.point().y,p.point().z,
                    4,.08,.06,.08,.015,true);
        }
    }
}
