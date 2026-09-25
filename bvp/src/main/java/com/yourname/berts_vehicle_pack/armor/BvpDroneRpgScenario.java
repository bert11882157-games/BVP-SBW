package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModItems;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/** Opt-in native drone movement/collision fixture; never calls the payload hook directly. */
public final class BvpDroneRpgScenario {
    private static Run active;
    private BvpDroneRpgScenario() { }
    public static void register() { MinecraftForge.EVENT_BUS.register(BvpDroneRpgScenario.class); }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("bvp_drone_rpg_test")
                .requires(source -> source.hasPermission(2)).executes(context -> {
                    if (active != null) return 0;
                    active = new Run(context.getSource().getPlayerOrException());
                    return 1;
                }));
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (active == null || event.phase != TickEvent.Phase.END || event.getServer() != active.player.server) return;
        try { active.tick(); } catch (RuntimeException failure) {
            active.record("ERROR", "error", failure.toString());active.close("ERROR");
        }
    }
    private static final class Run {
        final ServerPlayer player;final ServerLevel level;final Vec3 origin;
        VehicleEntity target;DroneEntity drone;Vec3 targetPosition,velocity;float healthBefore;
        int ticks,checks,failures;String caseId;
        Run(ServerPlayer player) {
            this.player=player;level=player.serverLevel();origin=player.position().add(24,8,24);
            EliteDiagnostics.INSTANCE.start(player.server);record("START", "suite", "native_drone_rpg");
        }
        void tick() {
            int index=ticks/60,phase=ticks%60;
            if(index==3){close(failures==0?"PASS":"FAIL");return;}
            if(phase==0)prepare(index);
            if(target!=null&&!target.isRemoved()){target.setPos(targetPosition);target.setDeltaMovement(Vec3.ZERO);}
            // Supply a repeatable incoming flight vector; the native vehicle tick performs movement,
            // broadphase detection, hitEntityCrash, payload dispatch and drone destruction.
            if(phase<30&&drone!=null&&!drone.isRemoved())drone.setDeltaMovement(velocity);
            if(phase==45){
                check("native_collision_dispatched_once",drone.getPersistentData().getInt("BvpRpgImpactCount")==1);
                check("exact_400mm_descriptor",drone.getPersistentData().getDouble("BvpRpgPenetrationMm")==400);
                check("drone_consumed",drone.isRemoved()||drone.getHealth()<=0);
                double damage=healthBefore-target.getHealth();
                if(index==0)check("weak_armor_same_tacz_damage_90_1",Math.abs(damage-90.1)<.03);
                if(index==1)check("1000mm_plate_blocks_400mm_charge",Math.abs(damage)<.001);
                if(index==2)check("native_continuation_not_swallowed",damage>0);
                record("CASE_COMPLETE","case",caseId,"hull_before",healthBefore,"hull_after",target.getHealth());
                target.discard();drone.discard();
            }
            ticks++;
        }
        void prepare(int index) {
            caseId=new String[]{"weak_85mm","strong_1000mm","native_t90"}[index];
            var type=ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(index==2?"superbwarfare:t_90a":"berts_vehicle_pack:leo2a6"));
            target=(VehicleEntity)type.create(level);if(target==null)throw new IllegalStateException("missing target");
            targetPosition=origin.add(index*24,0,0);target.setPos(targetPosition);target.setNoGravity(true);level.addFreshEntity(target);
            Vec3 aim,normal;
            if(target instanceof ArmoredVehicleEntity armored){
                String plateId=index==0?"leo2a6_85mm_armor_26":"leo2a6_1000mm_armor_00";
                var plate=ArmorProfiles.get("leo2a6").plates.stream().filter(p->p.name.equals(plateId)).findFirst().orElseThrow();
                var coordinates=ArmorTargetAdapters.resolve(armored);
                var hint=index==0?new ArmorProfiles.Vec(-1,0,0):new ArmorProfiles.Vec(0,0,-1);
                var face=plate.volume.dominantFaceNormal(hint);
                var localNormal=face==null?hint:face;
                aim=coordinates.armorLocalPointToWorld(plate.centroid());
                normal=coordinates.armorLocalPointToWorld(plate.centroid().add(localNormal)).subtract(aim).normalize();
                record("PLATE","case",caseId,"plate",plateId,"aim",aim);
            }else{
                aim=targetPosition.add(0,1.3,0);normal=new Vec3(1,0,0);
            }
            var droneType=ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("superbwarfare:drone"));
            drone=(DroneEntity)droneType.create(level);if(drone==null)throw new IllegalStateException("missing native drone");
            drone.currentItem=new ItemStack(ModItems.RPG_ROCKET_STANDARD.get());
            drone.getEntityData().set(DroneEntity.DISPLAY_ENTITY,"superbwarfare:rpg_rocket_standard");
            drone.getEntityData().set(DroneEntity.IS_KAMIKAZE,true);
            drone.getEntityData().set(DroneEntity.CONTROLLER,player.getStringUUID());
            drone.setAmmo(1);drone.setHealth(10);drone.setNoGravity(true);
            Vec3 eye=aim.add(normal.scale(index==2?4:1.1));
            drone.setPos(eye.subtract(0,drone.getEyeHeight(),0));velocity=normal.scale(-.3);
            drone.setDeltaMovement(velocity);level.addFreshEntity(drone);healthBefore=target.getHealth();
            record("CASE_STARTED","case",caseId,"drone",drone.getUUID(),"target",target.getUUID(),"eye",eye,"velocity",velocity);
        }
        void check(String name,boolean passed){checks++;if(!passed)failures++;record("ASSERT","case",caseId,"check",name,"passed",passed);}
        void record(String event,Object... values){EliteDiagnostics.record(player,"drone_rpg_suite",event,values);}
        void close(String status){
            record("COMPLETE","status",status,"checks",checks,"failures",failures);
            if(drone!=null&&!drone.isRemoved())drone.discard();if(target!=null&&!target.isRemoved())target.discard();
            player.sendSystemMessage(Component.literal("Native drone RPG diagnostics "+status+": "+checks+" checks, "+failures+" failures."));
            EliteDiagnostics.INSTANCE.stop(player.server);active=null;
        }
    }
}
