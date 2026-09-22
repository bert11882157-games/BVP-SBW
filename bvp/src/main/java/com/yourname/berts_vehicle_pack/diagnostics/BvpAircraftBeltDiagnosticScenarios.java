package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.event.GunEventHandler;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.commands.Commands;
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
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.ArrayList;
import java.util.List;

/** Bounded opt-in acceptance using production GunData and normal server ticks, never profile overrides. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID)
public final class BvpAircraftBeltDiagnosticScenarios {
    private static Run active;
    private BvpAircraftBeltDiagnosticScenarios() { }

    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")) return;
        event.getDispatcher().register(Commands.literal("bvp_aircraft_belts").requires(s -> s.hasPermission(2))
            .then(Commands.literal("start").executes(c -> {
                if (active != null) throw new IllegalStateException("Aircraft belt fixture already active");
                ServerPlayer player = c.getSource().getPlayerOrException();
                EliteDiagnostics.INSTANCE.start(player.server);
                active = new Run(player);
                try { active.prepare(); }
                catch (RuntimeException error) { active.error(error); finish("FAILED"); return 0; }
                return 1;
            })).then(Commands.literal("stop").executes(c -> { finish("STOPPED"); return 1; })));
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || active == null || active.server != event.getServer()) return;
        try {
            active.tick();
            if (active.elapsed >= 2600) finish("TIMEOUT");
            else if (active.fixtures.stream().allMatch(f -> f.phase == 5)) finish(active.failures == 0 ? "PASS" : "FAIL");
        } catch (RuntimeException error) { active.error(error); finish("FAILED"); }
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        if (active != null && active.server == event.getServer()) finish("SERVER_STOPPING");
    }
    private static void finish(String status) {
        if (active == null) return;
        active.close(status);
        active = null;
    }

    private static final class Run {
        final MinecraftServer server;
        final ServerLevel level;
        final ServerPlayer observer;
        final List<Fixture> fixtures = new ArrayList<>();
        final List<VehicleEntity> owned = new ArrayList<>();
        final List<ChunkPos> chunks = new ArrayList<>();
        int elapsed, checks, failures;
        Run(ServerPlayer observer) { this.observer=observer; server=observer.server; level=observer.serverLevel(); }
        void prepare() {
            String[][] selections={{"yak_3","Cannon"},{"yak_3","MachineGunLeft"},{"mig19","Cannon"},{"mi_24d","PassengerGun1"}};
            for (int index=0; index<selections.length; index++) {
                Vec3 position=observer.position().add((index-1.5)*18,60,40);
                ChunkPos chunk=new ChunkPos((int)Math.floor(position.x)>>4,(int)Math.floor(position.z)>>4);
                if (!level.getForcedChunks().contains(chunk.toLong())) {
                    level.setChunkForced(chunk.x,chunk.z,true); chunks.add(chunk);
                }
                level.getChunk(chunk.x,chunk.z);
                EntityType<?> type=ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(BertsVehiclePack.MODID,selections[index][0]));
                Entity entity=type==null?null:type.create(level);
                if (!(entity instanceof VehicleEntity vehicle)) throw new IllegalStateException("Missing aircraft "+selections[index][0]);
                owned.add(vehicle);
                vehicle.load(new CompoundTag());
                vehicle.moveTo(position.x,position.y,position.z,0,0);
                vehicle.setNoGravity(true);
                vehicle.addTag("bvp_aircraft_belt_fixture");
                vehicle.setEnergy(vehicle.getMaxEnergy());
                clearInventory(vehicle);
                for (String name:vehicle.getGunDataMap().keySet().toArray(String[]::new)) vehicle.modifyGunData(name,data -> {
                    data.resetStatus(); data.reload.setPendingProgressPercent(0);
                    // Other production gun channels must not auto-reload from the selected
                    // fixture's shared inventory while its reserve accounting is measured.
                    data.ammo.set(Math.max(0,data.get(GunProp.MAGAZINE)));
                    data.virtualAmmo.set(0); data.heat.set(0); data.overHeat.set(false);
                });
                if (!level.addFreshEntity(vehicle)) throw new IllegalStateException("Aircraft insertion failed");
                Fixture fixture=new Fixture(this,vehicle,selections[index][1],position);
                fixtures.add(fixture);
                fixture.prepare();
            }
            record("STARTED","maximum_ticks",2600,"fixtures",fixtures.size(),"observer_is_ammo_supplier",false);
        }
        void tick() { elapsed++; fixtures.forEach(Fixture::tick); }
        void check(VehicleEntity vehicle,String name,boolean passed) {
            checks++;
            if (!passed) { failures++; EliteDiagnostics.record(vehicle,"aircraft_belt","ASSERT_FAIL","check",name); }
        }
        void record(String event,Object...fields) { EliteDiagnostics.record(observer,"aircraft_belt",event,fields); }
        void error(RuntimeException error) { record("ERROR","exception",error.toString()); }
        void close(String status) {
            record("COMPLETE","status",status,"checks",checks,"failures",failures,"elapsed_ticks",elapsed);
            owned.forEach(Entity::discard);
            chunks.forEach(c -> level.setChunkForced(c.x,c.z,false));
            observer.sendSystemMessage(Component.literal("Aircraft belt diagnostics "+status+": "+checks+" checks, "+failures+" failures."));
            EliteDiagnostics.INSTANCE.stop(server);
        }
    }
    private static void clearInventory(VehicleEntity vehicle) {
        for(int slot=0;slot<vehicle.getInventory().getSlots();slot++) vehicle.getInventory().setStackInSlot(slot,ItemStack.EMPTY);
    }
    private static final class Fixture {
        final Run run;
        final VehicleEntity vehicle;
        final String weapon;
        final Vec3 position;
        int phase,capacity,initialReserve,acceptedProjectiles,reloadProjectiles,period;
        long requestTick;
        boolean persisted;
        Fixture(Run run,VehicleEntity vehicle,String weapon,Vec3 position) {
            this.run=run;this.vehicle=vehicle;this.weapon=weapon;this.position=position;
        }
        GunData data() { GunData data=vehicle.getGunData(weapon); if(data==null) throw new IllegalStateException("Missing "+weapon); return data; }
        int reserve() { return data().countBackupAmmo(vehicle.getAmmoSupplier()); }
        void prepare() {
            GunData data=data(); capacity=data.get(GunProp.MAGAZINE);
            if(capacity<=0||!data.get(GunProp.BELT_FED)||data.get(GunProp.NORMAL_RELOAD_TIME)!=300||data.get(GunProp.EMPTY_RELOAD_TIME)!=300)
                throw new IllegalStateException("Production aircraft belt policy missing: "+weapon);
            period=Math.max(1,(int)Math.ceil(1200.0/data.get(GunProp.RPM)));
            // Real source items exercise inventory consumption. Any space-limited remainder uses
            // the native persisted reserve, never a creative player or infinite ammo consumer.
            var consumer=data.selectedAmmoConsumer();
            ItemStack source=consumer.stack().copy();
            if(source.isEmpty()) throw new IllegalStateException("Expected finite source-ammo item");
            int load=Math.max(1,consumer.getLoadAmount());
            int units=(capacity+10+load-1)/load;
            for(int slot=0;slot<vehicle.getInventory().getSlots()&&units>0;slot++) {
                ItemStack stack=source.copy();
                stack.setCount(Math.min(units,stack.getMaxStackSize())); units-=stack.getCount();
                vehicle.getInventory().setStackInSlot(slot,stack);
            }
            final int remaining=units*load;
            vehicle.modifyGunData(weapon,d -> { d.ammo.set(capacity); d.virtualAmmo.set(remaining); });
            initialReserve=reserve();
            run.check(vehicle,"finite_survival_reserve",initialReserve>=capacity+10&&initialReserve<Integer.MAX_VALUE);
            record("READY","ammo_item",consumer.getAmmo(),"load_amount",load,"reserve",initialReserve);
        }
        void tick() {
            vehicle.setPos(position); vehicle.setDeltaMovement(Vec3.ZERO);
            long now=run.level.getGameTime();
            if(phase==0) {
                if(run.elapsed%period==0) fire(false);
                if(data().ammo.get()==0) {
                    requestTick=now; phase=1; persisted=false;
                    record("EMPTY_REQUEST","server_tick",now,"reserve",reserve());
                }
            } else if(phase==1||phase==3) {
                long updates=now-requestTick;
                if(updates<300) {
                    run.check(vehicle,"reload_active_until_update300",data().reloading());
                    fire(true);
                    run.check(vehicle,"no_projectiles_during_reload",reloadProjectiles==0);
                    if(updates==150&&!persisted) {
                        int time=data().reload.time(),ammo=data().ammo.get(),reserve=reserve();
                        int revision=data().reload.soundCycleRevision();
                        CompoundTag tag=new CompoundTag();
                        vehicle.saveWithoutId(tag); vehicle.load(tag);
                        run.check(vehicle,"entity_nbt_roundtrip_preserves_reload",data().reload.time()==time&&data().ammo.get()==ammo
                            &&reserve()==reserve&&data().reload.soundCycleRevision()==revision&&data().reloading());
                        persisted=true;
                        record("ENTITY_NBT_ROUNDTRIP","remaining_ticks",time,"revision",revision,
                            "qualification","same live entity saveWithoutId/load; no disk or process restart");
                    }
                } else {
                    run.check(vehicle,"completes_on_update300",updates==300&&!data().reloading());
                    run.check(vehicle,"refilled_exact_capacity",data().ammo.get()==capacity);
                    run.check(vehicle,"reserve_consumption",reserve()==initialReserve-capacity-(phase==3?5:0));
                    record("RELOAD_COMPLETE","kind",phase==1?"EMPTY":"NORMAL","updates",updates,
                        "loaded",data().ammo.get(),"reserve",reserve(),"reload_projectiles",reloadProjectiles);
                    if(phase==1) phase=2;
                    else {
                        fire(false);
                        clearInventory(vehicle);
                        vehicle.modifyGunData(weapon,d -> {d.ammo.set(0);d.virtualAmmo.set(0);});
                        phase=4;requestTick=now;
                    }
                }
            } else if(phase==2&&run.elapsed%period==0) {
                fire(false);
                if(data().ammo.get()==capacity-5) {
                    // Same backend as ReloadMessage, using the aircraft ammo supplier.
                    vehicle.modifyGunData(weapon,d -> GunEventHandler.INSTANCE.tryStartReload(vehicle.getAmmoSupplier(),d));
                    requestTick=now;phase=3;persisted=false;
                    record("MANUAL_REQUEST","server_tick",now,"loaded",data().ammo.get());
                }
            } else if(phase==4) {
                vehicle.modifyGunData(weapon,d -> GunEventHandler.INSTANCE.tryStartReload(vehicle.getAmmoSupplier(),d));
                run.check(vehicle,"empty_reserve_cannot_start_reload",!data().reloading()&&data().reload.time()==0&&reserve()==0);
                fire(true);
                if(now-requestTick>=20) {
                    phase=5;
                    record("FIXTURE_DONE","accepted_projectiles",acceptedProjectiles,"reserve",reserve());
                }
            }
            if(run.elapsed%20==0) record("SERVER_SAMPLE","phase",phase,"loaded",data().ammo.get(),
                "capacity",capacity,"reserve",reserve(),"remaining_ticks",data().reload.time(),
                "revision",data().reload.soundCycleRevision(),"accepted_projectiles",acceptedProjectiles);
        }
        void fire(boolean mustReject) {
            int before=data().ammo.get(),cost=data().get(GunProp.AMMO_COST_PER_SHOOT);
            var result=vehicle.vehicleShootResult(null,weapon);
            int spawned=result.getSpawnedProjectileIds().size();
            run.check(vehicle,"shot_ammo_accounting",data().ammo.get()==before-(result.isAccepted()?cost:0));
            run.check(vehicle,mustReject?"blocked_shot_rejected":"loaded_shot_accepted",mustReject?!result.isAccepted():result.isAccepted());
            if(mustReject) reloadProjectiles+=spawned; else acceptedProjectiles+=spawned;
            // Validate actual inserted projectile receipts; isolate the fixture from world impacts.
            for(var id:result.getSpawnedProjectileIds()) {
                Entity entity=run.level.getEntity(id);
                run.check(vehicle,"accepted_projectile_inserted",entity!=null);
                if(entity!=null) entity.discard();
            }
            if(run.elapsed%20==0||!result.isAccepted()&&!mustReject) record("SHOT","accepted",result.isAccepted(),
                "reason",result.getReason(),"projectiles",spawned,"during_reload",data().reloading(),"loaded",data().ammo.get());
        }
        void record(String event,Object...fields) {
            Object[] all=new Object[fields.length+2];all[0]="weapon";all[1]=weapon;System.arraycopy(fields,0,all,2,fields.length);
            EliteDiagnostics.record(vehicle,"aircraft_belt",event,all);
        }
    }
}
