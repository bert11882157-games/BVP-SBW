package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Private repeatable FX workload. Ammo/cooldown resets are explicit; this does not test weapon RPM. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID)
public final class BvpEffectStressScenario {
    private static Run active;
    private static boolean enabled() { return DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios"); }
    private static boolean privateOperator(ServerPlayer player) {
        var server = player.server;
        return enabled() && server.isDedicatedServer() && !server.usesAuthentication()
                && "127.0.0.1".equals(server.getLocalIp()) && server.getPort() == BvpFireTrafficControl.PORT
                && server.getPlayerCount() == 1 && player.level().dimension() == Level.OVERWORLD
                && player.isAlive() && !player.isSpectator() && player.getAbilities().instabuild
                && BvpFireTrafficControl.identity(player.getGameProfile().getName(),player.getUUID())
                && BvpFireTrafficControl.loopback(player.connection.connection.getRemoteAddress(),false);
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        if (!enabled()) return;
        event.getDispatcher().register(Commands.literal("bvp_effect_stress").requires(s -> s.hasPermission(2))
                .then(Commands.argument("atgmHz",IntegerArgumentType.integer(0,4))
                .then(Commands.argument("shellHz",IntegerArgumentType.integer(0,2))
                .then(Commands.argument("seconds",IntegerArgumentType.integer(1,60)).executes(c -> {
                    var player=c.getSource().getPlayerOrException();
                    int atgm=IntegerArgumentType.getInteger(c,"atgmHz"),shell=IntegerArgumentType.getInteger(c,"shellHz");
                    if(active!=null || !privateOperator(player) || player.getVehicle()!=null || EliteDiagnostics.isServerEnabled()
                            || atgm==3) return 0;
                    active=new Run(player,atgm,shell,IntegerArgumentType.getInteger(c,"seconds"));
                    try { active.prepare(); } catch(RuntimeException failure) { active.finish("ERROR",failure.toString()); }
                    return active==null?0:1;
                })))));
        event.getDispatcher().register(Commands.literal("bvp_effect_stress_stop").requires(s -> s.hasPermission(2))
                .executes(c -> { if(active==null)return 0;active.finish("STOPPED","Operator request");return 1; }));
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        Run run=active;if(run==null||run.server!=event.getServer())return;
        try { if(event.phase==TickEvent.Phase.START)run.hold();else run.tick(); }
        catch(RuntimeException failure) { run.finish("ERROR",failure.toString()); }
    }
    @SubscribeEvent public static void spawn(EntityJoinLevelEvent event) {
        Run run=active;if(run==null||event.getLevel()!=run.level||run.firing==null)return;
        var entity=event.getEntity();run.projectiles.put(entity.getUUID(),entity);run.kinds.put(entity.getUUID(),run.firing);
        run.record("PROJECTILE_SPAWN","kind",run.firing,"projectile",entity.getUUID(),
                "type",ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()),"position",entity.position(),"velocity",entity.getDeltaMovement());
    }
    @SubscribeEvent public static void explosion(ExplosionEvent.Detonate event) {
        Run run=active;if(run==null||event.getLevel()!=run.level)return;
        var source=event.getExplosion().getDirectSourceEntity();
        if(source!=null&&run.kinds.containsKey(source.getUUID())&&run.exploded.add(source.getUUID()))
            run.record("EXPLOSION","kind",run.kinds.get(source.getUUID()),"projectile",source.getUUID(),
                    "position",event.getExplosion().getPosition());
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        if(active!=null&&active.server==event.getServer())active.finish("STOPPED","Server stopping");
    }
    private static final class Run {
        final ServerPlayer player;final MinecraftServer server;final ServerLevel level;
        final int atgmHz,shellHz,measureTicks;final Vec3 saved;final float yaw,pitch;final boolean flying;
        final Map<BlockPos,BlockState> terrain=new LinkedHashMap<>();
        final Map<UUID,Entity> projectiles=new LinkedHashMap<>();final Map<UUID,String> kinds=new HashMap<>();
        final Set<UUID> exploded=new HashSet<>();final Map<String,Integer> accepted=new LinkedHashMap<>();
        final List<VehicleEntity> vehicles=new ArrayList<>();final List<Vec3> anchors=new ArrayList<>();
        final Map<String,String> artifacts=new LinkedHashMap<>();CompletableFuture<Map<String,String>> hashes;
        int elapsed;boolean capture,proof;String firing;Vec3 origin;
        Run(ServerPlayer p,int a,int s,int seconds) {
            player=p;server=p.server;level=p.serverLevel();atgmHz=a;shellHz=s;measureTicks=seconds*20;
            saved=p.position();yaw=p.getYRot();pitch=p.getXRot();flying=p.getAbilities().flying;
        }
        void prepare() {
            origin=new Vec3(Math.floor(saved.x),Math.min(level.getMaxBuildHeight()-30,saved.y+24),Math.floor(saved.z));
            // Fixture owns only this small temporary, non-destructible impact screen.
            for(int x=-24;x<=24;x++)for(int y=-8;y<=16;y++) {
                var pos=BlockPos.containing(origin.add(x,y,96));level.getChunk(pos.getX()>>4,pos.getZ()>>4);
                terrain.put(pos,level.getBlockState(pos));level.setBlock(pos,Blocks.BEDROCK.defaultBlockState(),3);
            }
            create("tow_tripod",origin.add(-12,0,0),0);
            create("t55a_2_0",origin.add(12,0,0),3);
            player.teleportTo(level,origin.x+30,origin.y+4,origin.z-20,14.5F,0F);
            player.getAbilities().flying=true;player.onUpdateAbilities();
            for(String id:List.of("berts_vehicle_pack","superbwarfare","ballistics")) {
                var info=ModList.get().getModFileById(id);
                if(info==null)throw new IllegalStateException("Missing loaded mod "+id);
                artifacts.put(id,info.getFile().getFilePath().toString());
            }
            hashes=CompletableFuture.supplyAsync(() -> {
                var result=new LinkedHashMap<String,String>();
                artifacts.forEach((id,path) -> {try {
                    var digest=MessageDigest.getInstance("SHA-256");
                    try(var input=new DigestInputStream(Files.newInputStream(Path.of(path)),digest)) {
                        byte[] buffer=new byte[65536];while(input.read(buffer)!=-1) { }
                    }
                    result.put(id,HexFormat.of().formatHex(digest.digest()));
                }catch(Exception failure){throw new IllegalStateException("Loaded artifact hash failed: "+id,failure);}});
                return result;
            });
            EliteDiagnostics.INSTANCE.start(server);capture=true;
            record("START","atgm_hz",atgmHz,"shell_hz",shellHz,"measure_ticks",measureTicks,
                    "warmup_ticks",600,"recovery_ticks",600,"ammo_cooldown_reset",true,"observer",player.position());
        }
        void create(String id,Vec3 position,int ammo) {
            var type=ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(BertsVehiclePack.MODID,id));
            var created=type==null?null:type.create(level);
            if(!(created instanceof VehicleEntity v))throw new IllegalStateException("Missing fixture "+id);
            v.load(new CompoundTag());v.moveTo(position.x,position.y,position.z,0F,0F);v.setNoGravity(true);
            level.getChunk((int)Math.floor(position.x)>>4,(int)Math.floor(position.z)>>4);
            if(!level.addFreshEntity(v))throw new IllegalStateException("Fixture insertion failed");
            vehicles.add(v);anchors.add(position);v.setEnergy(v.getMaxEnergy());
            for(int slot=0;slot<v.getInventory().getSlots();slot++)v.getInventory().setStackInSlot(slot,ItemStack.EMPTY);
            v.modifyGunData("Cannon",g -> g.changeAmmoConsumer(ammo,v));
        }
        void hold() {
            if(!privateOperator(player)||player.getVehicle()!=null)throw new IllegalStateException("Private fixture context changed");
            for(int i=0;i<vehicles.size();i++) {
                var v=vehicles.get(i);if(v.isRemoved()||v.isWreck())throw new IllegalStateException("Fixture lost");
                v.setPos(anchors.get(i));v.setDeltaMovement(Vec3.ZERO);v.setOnGround(false);
            }
        }
        void tick() {
            elapsed++;
            if(!proof&&hashes!=null&&hashes.isDone()) {
                hashes.join().forEach((id,hash) -> record("LOADED_ARTIFACT","mod",id,"path",artifacts.get(id),"sha256",hash));proof=true;
            }
            if(elapsed==600) {if(!proof)throw new IllegalStateException("Missing artifact proof");record("WINDOW_START");}
            int frame=elapsed-600;
            if(frame>=0&&frame<measureTicks) {
                if(atgmHz>0&&frame%(20/atgmHz)==0)fire(0,"atgm");
                if(shellHz>0&&frame%(20/shellHz)==0)fire(1,"shell");
            }
            if(frame==measureTicks)record("WINDOW_END","accepted",accepted);
            if(elapsed%200==0)record("SAMPLE","live_projectiles",projectiles.values().stream().filter(e -> !e.isRemoved()).count(),"explosions",exploded.size());
            if(frame>=measureTicks+600) {
                long live=projectiles.values().stream().filter(e -> !e.isRemoved()).count();
                int shots=accepted.values().stream().mapToInt(Integer::intValue).sum();
                finish(live==0&&exploded.size()==shots?"PASS":"FAIL","live="+live+", accepted="+shots+", impacts="+exploded.size());
            }
        }
        void fire(int index,String kind) {
            var vehicle=vehicles.get(index);int before=projectiles.size();
            vehicle.modifyGunData("Cannon",g -> {g.resetStatus();g.reload.setPendingProgressPercent(0);
                g.ammo.set(g.get(GunProp.MAGAZINE));g.virtualAmmo.set(64);g.heat.set(0);g.overHeat.set(false);});
            firing=kind;
            try {
                var result=vehicle.vehicleShootResult(null,"Cannon",null,anchors.get(index).add(0,0,96));
                record("SHOT_RESULT","kind",kind,"accepted",result.isAccepted(),"reason",result.getReason(),"spawned",projectiles.size()-before);
                if(!result.isAccepted()||projectiles.size()!=before+1)throw new IllegalStateException("Expected exactly one accepted projectile: "+kind);
                accepted.merge(kind,1,Integer::sum);
            }finally{firing=null;}
        }
        void record(String event,Object... values){EliteDiagnostics.record(player,"effect_stress",event,values);}
        void finish(String status,String reason) {
            active=null;
            try {
                record("COMPLETE","status",status,"reason",reason,"ticks",elapsed,"accepted",accepted,"explosions",exploded.size());
                for(var projectile:projectiles.values())if(!projectile.isRemoved())projectile.discard();
                for(var vehicle:vehicles)if(!vehicle.isRemoved())vehicle.discard();
                terrain.forEach((pos,state)->level.setBlock(pos,state,3));
                if(!player.isRemoved()) {
                    player.teleportTo(level,saved.x,saved.y,saved.z,yaw,pitch);player.getAbilities().flying=flying;player.onUpdateAbilities();
                    player.sendSystemMessage(Component.literal("Effect stress "+status+": "+reason));
                }
            } finally { if(capture)EliteDiagnostics.INSTANCE.stop(server); }
        }
    }
    private BvpEffectStressScenario() { }
}
