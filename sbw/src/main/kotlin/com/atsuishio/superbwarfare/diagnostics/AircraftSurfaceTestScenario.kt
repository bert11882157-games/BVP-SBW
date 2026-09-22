package com.atsuishio.superbwarfare.diagnostics

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.aircraft.AircraftSurfaceModules
import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingSurfaceDamage
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.ShootParameters
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.item.gun.special.RepairToolItem
import com.atsuishio.superbwarfare.world.phys.EntityResult
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.nio.file.Files
import java.util.UUID

/** Guarded disposable-world integration fixture. Health changes only through real shots/tool hits. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID)
object AircraftSurfaceTestScenario {
    private val identity = UUID.nameUUIDFromBytes("OfflinePlayer:BvpDiagnostics".toByteArray(Charsets.UTF_8))
    private var active: Run? = null
    private fun allowed(p: ServerPlayer): Boolean = p.server.let { s ->
        DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios") && s.isDedicatedServer &&
            !s.usesAuthentication() && s.localIp == "127.0.0.1" && s.port == 25579 && s.playerCount == 1 &&
            p.gameProfile.name == "BvpDiagnostics" && p.uuid == identity && p.abilities.instabuild &&
            !p.isSpectator && p.level().dimension() == Level.OVERWORLD &&
            Files.isRegularFile(s.getWorldPath(LevelResource.ROOT).resolve("sbw-disposable-tests.marker"))
    }

    @SubscribeEvent fun register(e: RegisterCommandsEvent) {
        val root = Commands.literal("sbw_aircraft_surface_test").requires { it.hasPermission(2) }
        for ((name, type) in mapOf("start" to "berts_vehicle_pack:mig19", "start_native" to "superbwarfare:a_10a")) {
            root.then(Commands.literal(name).executes {
                val p=it.source.playerOrException
                if (!allowed(p) || active != null || EliteDiagnostics.isServerEnabled()) return@executes 0
                val run=Run(p,type); active=run
                try { run.prepare(); 1 } catch (failure: Exception) { run.finish("PREPARE_FAILED: ${failure.message}"); 0 }
            })
        }
        for (name in listOf("pose", "status", "pilot", "ground_pilot", "land", "repair", "field_repair", "gap", "cancel")) {
            root.then(Commands.literal(name).executes { control(it.source.playerOrException,name,null) })
        }
        for (verb in listOf("shoot", "probe", "damage")) {
            val branch=Commands.literal(verb)
            for (id in AircraftSurfaceModules.ids) branch.then(Commands.literal(id.path).executes {
                control(it.source.playerOrException,verb,id)
            })
            root.then(branch)
        }
        e.dispatcher.register(root)
    }
    private fun control(p: ServerPlayer, command: String, id: ResourceLocation?): Int {
        val run=active ?: return 0
        if (!allowed(p) || run.player !== p) return 0
        return try { run.command(command,id); 1 } catch (failure: Exception) {
            run.record("COMMAND_REJECTED","command",command,"reason",failure.message)
            p.sendSystemMessage(Component.literal("SURFACE rejected: ${failure.message}")); 0
        }
    }
    @SubscribeEvent fun tick(e: TickEvent.ServerTickEvent) {
        if(e.phase != TickEvent.Phase.END) return
        active?.let { run -> try { run.tick() } catch(failure: Exception) { run.finish("FAILED: ${failure.message}") } }
    }
    @SubscribeEvent fun stopping(e: ServerStoppingEvent) { active?.finish("SERVER_STOPPING",true) }

    private class Run(val player: ServerPlayer,val type: String) {
        private val level=player.serverLevel()
        private val oldPosition=player.position()
        private val oldYaw=player.yRot; private val oldPitch=player.xRot
        private val oldFlying=player.abilities.flying
        private val home=Vec3(4200.5,221.0,7200.5)
        private val saved=linkedMapOf<BlockPos,BlockState>()
        private val owned=mutableListOf<Entity>()
        private lateinit var target: VehicleEntity
        private lateinit var weapon: VehicleEntity
        private var ticks=0; private var settle=0
        private var damaging: ResourceLocation?=null
        private var damageTicks=0; private var repairing=false
        private var damageHealth=0f; private var shotsWithoutProgress=0
        private var actionSequence=1_000_000
        private var capture=false
        private var frozen=false
        private var finishing:String?=null
        private var cleanupTicks=0
        private val pending=linkedMapOf<UUID,Entity>()
        private val tool=ModItems.REPAIR_TOOL.get() as RepairToolItem
        private val toolData=GunData.from(ItemStack(tool))

        fun record(event:String,vararg fields:Any?) = EliteDiagnostics.record(player,"aircraft_surface_test",event,*fields)
        private fun spawn(id:String,pos:Vec3):VehicleEntity {
            val vehicle=BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation(id)).orElseThrow().create(level) as VehicleEntity
            vehicle.moveTo(pos.x,pos.y,pos.z,0f,0f); vehicle.addTag("sbw_aircraft_surface_test")
            owned += vehicle; check(level.addFreshEntity(vehicle)); EliteDiagnostics.includeServerEntity(vehicle.uuid)
            return vehicle
        }
        fun prepare() {
            player.sendSystemMessage(Component.literal(EliteDiagnostics.startForEntities(player.server,setOf(player.uuid))))
            capture=EliteDiagnostics.isServerEnabled(); check(capture)
            player.teleportTo(level,home.x+15,home.y+6,home.z,90f,15f)
            player.abilities.flying=true; player.onUpdateAbilities()
            for(x in -20..20) for(z in -20..20) {
                val pos=BlockPos.containing(home.add(x.toDouble(),-2.0,z.toDouble()))
                check(level.getBlockEntity(pos)==null) { "Refuses block entities" }
                saved[pos]=level.getBlockState(pos); level.setBlock(pos,Blocks.BEDROCK.defaultBlockState(),3)
            }
            target=spawn(type,home); weapon=spawn("superbwarfare:t_90a",home.add(16.0,0.0,16.0))
            weapon.canUpdate(false); settle=60
            record("PREPARED","vehicle",target.uuid,"type",type,"home",home)
        }
        fun command(command:String,id:ResourceLocation?) {
            if(command=="cancel") { finish("CANCELLED");return }
            check(resolveOwned()) { "Owned aircraft/weapon residency pending" }
            check(!target.isRemoved && !target.isWreck) { "Target unavailable; cancel and restart" }
            when(command) {
                "cancel" -> finish("CANCELLED")
                "status" -> status()
                "pose" -> {
                    check(settle==0 && !repairing && damaging==null)
                    frozen=true; target.canUpdate(false); target.applyVehicleFlightAttitude(25f,12f,30f)
                    target.flap2LRot=20f; target.flap2LRotO=20f; target.flap2RRot=-20f; target.flap2RRotO=-20f
                    target.flap3Rot=15f; target.flap3RotO=15f
                    target.updateOBB()
                    com.atsuishio.superbwarfare.entity.vehicle.base.AircraftSurfaceProjectileIndex.update(target)
                    record("POSE","yaw",25,"pitch",12,"roll",30,"native_elevator_left",20,"native_elevator_right",-20)
                }
                "shoot" -> { check(settle==0 && !player.isPassenger); fire(requireNotNull(id),false,false) }
                "probe" -> { check(settle==0 && !player.isPassenger); fire(requireNotNull(id),false,true) }
                "damage" -> { check(settle==0 && !player.isPassenger); damaging=requireNotNull(id); damageTicks=0; repairing=false
                    damageHealth=requireNotNull(target.getVehicleModuleState(id)).health; shotsWithoutProgress=0 }
                "gap" -> { check(settle==0 && !player.isPassenger); fire(null,true,false) }
                "pilot" -> {
                    damaging=null; repairing=false; frozen=false; target.canUpdate(true)
                    target.moveTo(home.x,home.y+80,home.z,0f,0f); target.applyVehicleFlightAttitude(0f,0f,0f)
                    target.deltaMovement=Vec3(0.0,0.0,4.0); target.setOnGround(false)
                    check(player.startRiding(target,true)); record("PILOT","vehicle",target.uuid)
                }
                "ground_pilot" -> {
                    check(target.onGround()) { "Ground pilot requires actual ground contact" }
                    damaging=null; repairing=false; frozen=false; target.canUpdate(true)
                    check(player.startRiding(target,true)); record("GROUND_PILOT","vehicle",target.uuid)
                }
                "land" -> {
                    player.stopRiding(); damaging=null; repairing=false
                    target.moveTo(home.x,home.y+1,home.z,0f,0f); target.applyVehicleFlightAttitude(0f,0f,0f)
                    target.deltaMovement=Vec3.ZERO; frozen=false; target.canUpdate(true); settle=80
                    player.teleportTo(level,home.x+15,home.y+6,home.z,90f,15f)
                }
                "repair" -> { check(target.onGround()) { "Repair requires actual ground contact; use land first" }; damaging=null; repairing=true }
                "field_repair" -> {
                    check(player.vehicle === target && target.onGround()) { "Requires grounded mounted fixture pilot" }
                    // Same server action gate used by the normal packet. This is a diagnostic input,
                    // not an HP edit or proof that an OS key pulse survived client tick polling.
                    val action=ResourceLocation("berts_vehicle_pack","field_repair")
                    val pressed=target.requestVehicleActionInput(player,target.id,action,++actionSequence,true)
                    val released=target.requestVehicleActionInput(player,target.id,action,++actionSequence,false)
                    record("FIELD_REPAIR_INPUT","pressed_accepted",pressed,"released_accepted",released)
                    check(pressed && released) { "Production action input rejected" }
                }
            }
        }
        private fun fire(id:ResourceLocation?,gap:Boolean,small:Boolean) {
            val center=if(gap) {
                (2..12).map { home.add(it.toDouble(),5.0,-it.toDouble()) }.first { p ->
                    AircraftSurfaceModules.clipProjectile(target,p.add(0.0,0.0,-6.0),p.add(0.0,0.0,6.0))==null
                }
            } else AircraftSurfaceModules.smokePosition(target,requireNotNull(id),1f) ?: error("No surface geometry $id")
            val outward=if(gap) Vec3(0.0,0.0,-1.0) else center.subtract(target.position()).normalize()
            val muzzle=center.add(outward.scale(6.0)); val direction=outward.scale(-1.0)
            val contact=target.clipProjectile(muzzle,center)
            val predicted=contact?.let { AircraftSurfaceModules.contactModule(target,muzzle,it.point(),direction) }
            record("SHOT_PREFLIGHT","surface",id,"predicted_surface",predicted,"hit",contact?.point(),"aim",center)
            val name=if(small) "MachineGun" else "Cannon"
            weapon.modifyGunData(name) { gun -> gun.resetStatus();gun.reload.setPendingProgressPercent(0);gun.ammo.set(10)
                gun.virtualAmmo.set(100);gun.selectedAmmoType.set(0);gun.projectileBeltPhase.set(0) }
            val gun=weapon.getGunData(name) ?: error("Missing $name")
            record("BEFORE_SHOT","surface",id,"gap",gap,"hull",target.health,"states",states(),"muzzle",muzzle,"aim",center)
            val shot=gun.shootWithResult(ShootParameters(weapon,player,level,muzzle,direction,gun,0.0,true,null,null))
            check(shot.isAccepted()) { "Shot rejected ${shot.reason}" }
            for(uuid in shot.spawnedProjectileIds) {
                val entity=level.getEntity(uuid) as? Projectile ?: error("Missing projectile")
                owned += entity; EliteDiagnostics.includeServerEntity(uuid)
                record("SHOT","projectile",uuid,"target",target.uuid,"surface",id,"gap",gap,"small",small)
            }
        }
        private fun states()=AircraftSurfaceModules.ids.joinToString(";") { id ->
            val s=target.getVehicleModuleState(id); "$id:${s?.health}/${s?.maxHealth}:${s?.destroyed}"
        }
        private fun status() {
            val damage=FixedWingSurfaceDamage.from(target)
            val flight=target.getVehicleFlightInstrumentSnapshot(1f)
            record("STATE","vehicle",target.uuid,"hull",target.health,"modules",states(),"grounded",target.onGround(),
                "position",target.position(),"roll",target.roll,"pitch",target.xRot,"yaw",target.yRot,
                "roll_authority",damage.rollAuthority,"pitch_authority",damage.pitchAuthority,"yaw_authority",damage.yawAuthority,
                "roll_bias",damage.rollBiasDegreesPerSecond,"flight",flight.encode())
            player.sendSystemMessage(Component.literal("SURFACE hull=${target.health} ground=${target.onGround()} ${states()}"))
        }
        private fun resolveOwned(): Boolean {
            if(!::target.isInitialized || !::weapon.isInitialized) return false
            fun current(previous:VehicleEntity,role:String):VehicleEntity? {
                val live=level.getEntity(previous.uuid) as? VehicleEntity ?: return null
                check("sbw_aircraft_surface_test" in live.tags) { "Owned $role lost fixture tag" }
                if(live !== previous) {
                    owned += live
                    record("OWNED_REBOUND","role",role,"entity",live.uuid,"position",live.position(),
                        "previous_removal",previous.removalReason,"age",live.tickCount)
                }
                return live
            }
            val currentTarget=current(target,"target") ?: return false
            val currentWeapon=current(weapon,"weapon") ?: return false
            target=currentTarget; weapon=currentWeapon
            target.canUpdate(!frozen); weapon.canUpdate(false)
            return !target.isRemoved && !weapon.isRemoved
        }
        fun tick() {
            if(finishing!=null) { cleanup(false);return }
            ticks++; if(!allowed(player) || ticks>6000) { finish("GATE_OR_TIMEOUT");return }
            if(!resolveOwned()) return
            if(settle>0 && --settle==0) { frozen=true; target.canUpdate(false); status() }
            if(ticks%20==0) status()
            damaging?.let { id ->
                val health=target.getVehicleModuleState(id)?.health ?: 0f
                if(health<damageHealth) { damageHealth=health;shotsWithoutProgress=0 }
                if(target.isWreck || AircraftSurfaceModules.damaged(target,id) || ++damageTicks>1200 || shotsWithoutProgress>=3) {
                    record("DAMAGE_STOP","surface",id,"destroyed",AircraftSurfaceModules.damaged(target,id),"ticks",damageTicks,
                        "no_progress",shotsWithoutProgress>=3)
                    damaging=null; status()
                } else if(ticks%3==0) { fire(id,false,true);shotsWithoutProgress++ }
            }
            if(repairing) {
                check(target.onGround()) { "Lost ground during tool repair" }
                tool.onRayHitEntity(player,level,toolData,EntityResult(target,target.position(),false,false),
                    player.position(),target.position().subtract(player.position()).normalize())
                if(AircraftSurfaceModules.ids.all { id -> target.getVehicleModuleState(id)?.let { !it.destroyed && it.health>=it.maxHealth } ?: true }
                    && target.health>=target.getMaxHealth()) { repairing=false;record("TOOL_REPAIR_COMPLETE","modules",states());status() }
            }
        }
        fun finish(reason:String,force:Boolean=false) {
            if(active !== this) return
            if(finishing==null) {
                finishing=reason; player.stopRiding()
                owned.forEach { pending[it.uuid]=it }
            }
            cleanup(force)
        }
        private fun cleanup(force:Boolean) {
            cleanupTicks++
            val iterator=pending.entries.iterator()
            while(iterator.hasNext()) {
                val (id,previous)=iterator.next()
                var entity=level.getEntity(id) ?: previous.takeIf { !it.isRemoved }
                if(entity==null && previous.removalReason?.shouldDestroy()!=true && force) continue
                if(entity==null && previous.removalReason?.shouldDestroy()!=true && !force) {
                    val chunk=previous.chunkPosition(); level.getChunk(chunk.x,chunk.z)
                    entity=level.getEntity(id)
                    if(entity==null && (cleanupTicks<3 || !level.areEntitiesLoaded(chunk.toLong()) ||
                        previous is VehicleEntity && com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleIndex.get(level).positions.containsKey(id))) continue
                }
                if(entity!=null && !entity.isRemoved) entity.discard()
                iterator.remove()
            }
            if(pending.isNotEmpty() && cleanupTicks<200 && !force) return
            active=null
            saved.forEach { (pos,state) -> level.setBlock(pos,state,3) }
            player.teleportTo(level,oldPosition.x,oldPosition.y,oldPosition.z,oldYaw,oldPitch)
            player.abilities.flying=oldFlying;player.onUpdateAbilities()
            record("FINISHED","reason",finishing,"owned",owned.size,"cleanup_complete",pending.isEmpty(),
                "cleanup_pending",pending.keys.joinToString(","),"cleanup_ticks",cleanupTicks)
            if(capture) player.sendSystemMessage(Component.literal(EliteDiagnostics.stop(player.server)))
        }
    }
}
