package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.api.projectile.ProjectileCollisionTarget
import com.atsuishio.superbwarfare.tools.OBB
import com.atsuishio.superbwarfare.world.phys.ProjectileHitSelection
import com.atsuishio.superbwarfare.api.vehicle.module.VehicleModuleDefinition
import com.atsuishio.superbwarfare.data.vehicle.DefaultVehicleData
import com.atsuishio.superbwarfare.data.vehicle.subdata.AircraftSurfaceModuleInfo
import com.atsuishio.superbwarfare.data.vehicle.subdata.AircraftSurfaceTransform
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4d
import org.joml.Quaterniond
import org.joml.Vector3d

/** Independent generic modules backed by existing authoritative persistence and synchronization. */
object AircraftSurfaceModules {
    @JvmField val WING_LEFT = ResourceLocation("superbwarfare", "wing_left")
    @JvmField val WING_RIGHT = ResourceLocation("superbwarfare", "wing_right")
    @JvmField val ELEVATOR_LEFT = ResourceLocation("superbwarfare", "elevator_left")
    @JvmField val ELEVATOR_RIGHT = ResourceLocation("superbwarfare", "elevator_right")
    @JvmField val RUDDER = ResourceLocation("superbwarfare", "rudder")
    @JvmField val ids = listOf(WING_LEFT, WING_RIGHT, ELEVATOR_LEFT, ELEVATOR_RIGHT, RUDDER)
    private val fractions = ids.associate { it.toString() to if (it == WING_LEFT || it == WING_RIGHT) .20 else .15 }

    @JvmStatic fun ids(): List<ResourceLocation> = ids

    @JvmStatic fun state(vehicle: VehicleEntity, id: ResourceLocation) = vehicle.getVehicleModuleState(id)
    @JvmStatic fun damaged(vehicle: VehicleEntity, id: ResourceLocation) = state(vehicle, id)?.destroyed == true
    @JvmStatic fun smokePosition(vehicle: VehicleEntity, id: ResourceLocation, partialTick: Float): Vec3? {
        val entry = vehicle.computed().aircraftSurfaceModules.firstOrNull { it.id == id.toString() } ?: return null
        val box = entry.hitboxes.maxByOrNull { (it.max.x-it.min.x)*(it.max.y-it.min.y)*(it.max.z-it.min.z) } ?: return null
        val center = box.min.add(box.max).scale(.5)
        val matrix = Matrix4d(vehicle.getVehicleTransform(partialTick)).mul(boneMatrices(vehicle,partialTick)(box.bone))
        val point = matrix.transformPosition(Vector3d(center.x,center.y,center.z))
        return Vec3(point.x,point.y,point.z)
    }

    /** Exposed wing surface: a plume born at the wing-root volume center is hidden by the mesh. */
    fun smokeEmissionPosition(vehicle: VehicleEntity, id: ResourceLocation, partialTick: Float): Vec3? {
        if (id != WING_LEFT && id != WING_RIGHT) return null
        val entry = vehicle.computed().aircraftSurfaceModules.firstOrNull { it.id == id.toString() } ?: return null
        val box = entry.hitboxes.maxByOrNull { kotlin.math.abs((it.min.x + it.max.x) * .5) } ?: return null
        val local = Vector3d((box.min.x + box.max.x) * .5, box.max.y + .15, (box.min.z + box.max.z) * .5)
        val point = Matrix4d(vehicle.getVehicleTransform(partialTick)).mul(boneMatrices(vehicle,partialTick)(box.bone))
            .transformPosition(local)
        return Vec3(point.x,point.y,point.z)
    }
    /** Wreck flames originate above the inboard wing skin, independently of damage-smoke outlets. */
    fun wreckFirePosition(vehicle: VehicleEntity, id: ResourceLocation, partialTick: Float): Vec3? {
        if (id != WING_LEFT && id != WING_RIGHT) return null
        val entry = vehicle.computed().aircraftSurfaceModules.firstOrNull { it.id == id.toString() } ?: return null
        val box = entry.hitboxes.minByOrNull { kotlin.math.abs((it.min.x + it.max.x) * .5) } ?: return null
        val local = Vector3d((box.min.x + box.max.x) * .5, box.max.y + .15, (box.min.z + box.max.z) * .5)
        val point = Matrix4d(vehicle.getVehicleTransform(partialTick)).mul(boneMatrices(vehicle,partialTick)(box.bone))
            .transformPosition(local)
        return Vec3(point.x,point.y,point.z)
    }

    @JvmStatic fun definition(vehicle: VehicleEntity, id: ResourceLocation): VehicleModuleDefinition? {
        if (!AircraftProjectileHitPolicy.appliesTo(vehicle.vehicleType)) return null
        val entry = vehicle.computed().aircraftSurfaceModules.firstOrNull { it.id == id.toString() } ?: return null
        return VehicleModuleDefinition(id, (vehicle.getMaxHealth() * entry.maxHealthFraction).toFloat())
    }

    @JvmStatic fun validate(data: DefaultVehicleData) {
        require(data.aircraftSurfaceModules.size <= 5)
        require(data.aircraftSurfaceModules.map { it.id }.distinct().size == data.aircraftSurfaceModules.size)
        require(data.aircraftSurfaceTransforms.size <= 64)
        for ((bone, pose) in data.aircraftSurfaceTransforms) {
            require(pose.nativeChannel == null || pose.nativeChannel in setOf("ELEVATOR_LEFT", "ELEVATOR_RIGHT", "RUDDER"))
            require(bone.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")) && bone != "hull")
            require(finite(pose.pivot) && finite(pose.axis) && pose.axis.lengthSqr() > .99 && pose.axis.lengthSqr() < 1.01)
            require(finite(pose.controlWeights) && listOf(pose.controlWeights.x,pose.controlWeights.y,pose.controlWeights.z).all { kotlin.math.abs(it) <= 1 })
            require(pose.maxDeflectionDegrees.isFinite() && pose.maxDeflectionDegrees in 0.0..90.0)
            require(pose.angleSign == 1.0 || pose.angleSign == -1.0)
            require(pose.speedSchedule.isEmpty() || (pose.speedSchedule.size in 2..16 &&
                pose.speedSchedule.all { it.size == 2 && it.all(Double::isFinite) && it[0] >= 0 && it[1] in 0.0..1.0 } &&
                pose.speedSchedule.zipWithNext().all { (a,b) -> b[0] > a[0] }))
            var parent = pose.parent; val seen = mutableSetOf(bone)
            while (parent != "hull") {
                require(seen.add(parent) && seen.size <= 8)
                parent = requireNotNull(data.aircraftSurfaceTransforms[parent]).parent
            }
        }
        for (entry in data.aircraftSurfaceModules) {
            require(fractions[entry.id] == entry.maxHealthFraction)
            require(entry.hitboxes.size in 1..256)
            for (box in entry.hitboxes) {
                require(box.bone == "hull" || box.bone in data.aircraftSurfaceTransforms)
                require(finite(box.min) && finite(box.max) && box.min.x < box.max.x && box.min.y < box.max.y && box.min.z < box.max.z)
            }
        }
    }

    private fun finite(p: Vec3) = p.x.isFinite() && p.y.isFinite() && p.z.isFinite()

    /** Explicit tool repair only; passive hull healing never restores control surfaces. */
    @JvmStatic fun repairOnGround(vehicle: VehicleEntity, amount: Float) {
        if (vehicle.level().isClientSide || !vehicle.onGround() || vehicle.isWreck || !amount.isFinite() || amount <= 0) return
        for (id in ids) {
            val state = state(vehicle,id) ?: continue
            vehicle.setVehicleModuleHealth(id, minOf(state.maxHealth.toDouble(),state.health.toDouble()+amount))
        }
    }

    private val discoveryRadii = java.util.WeakHashMap<DefaultVehicleData,Double>()
    /** Conservative all-pose projectile discovery only; never used for terrain/entity collision. */
    @JvmStatic fun discoveryBounds(vehicle: VehicleEntity): AABB? {
        val data=vehicle.computed(); if(data.aircraftSurfaceModules.isEmpty()) return null
        val radius=synchronized(discoveryRadii) { discoveryRadii.getOrPut(data) {
            val surfaces = data.aircraftSurfaceModules.flatMap { it.hitboxes }.maxOf { box ->
                var r=kotlin.math.sqrt(maxOf(box.min.x*box.min.x,box.max.x*box.max.x)+
                    maxOf(box.min.y*box.min.y,box.max.y*box.max.y)+maxOf(box.min.z*box.min.z,box.max.z*box.max.z))
                var bone=box.bone
                while(bone!="hull") { val pose=requireNotNull(data.aircraftSurfaceTransforms[bone]);r+=2*pose.pivot.length();bone=pose.parent }
                r
            }
            // Projectile hull OBBs can extend beyond both terrain geometry and the outer surfaces.
            maxOf(surfaces, data.obb.maxOfOrNull { it.position.length() + it.size.length() } ?: 0.0)
        } }
        val center=vehicle.getVehicleTransform(1f).transformPosition(Vector3d())
        return AABB(center.x-radius,center.y-radius,center.z-radius,center.x+radius,center.y+radius,center.z+radius)
    }

    /** The detailed projectile API admits the closest authored hull/gear or articulated surface. */
    @JvmStatic fun clipProjectile(vehicle: VehicleEntity, start: Vec3, end: Vec3): ProjectileCollisionTarget.Hit? {
        // Terrain-contact volumes are intentionally coarse and can cover empty space above wings.
        // Ballistics must keep the authored projectile hull, then add the fitted surface modules.
        val hull=ProjectileHitSelection.nearestObb(vehicle.getOBBs(),start,end,0.0)
        return clipGeometry(vehicle.computed().aircraftSurfaceModules,Matrix4d(vehicle.getVehicleTransform(1f)),
            boneMatrices(vehicle,1f),hull,start,end)
    }

    internal fun clipGeometry(modules:List<AircraftSurfaceModuleInfo>, frame:Matrix4d,
                              poses:(String)->Matrix4d, hull:ProjectileCollisionTarget.Hit?,
                              start:Vec3,end:Vec3):ProjectileCollisionTarget.Hit? {
        val inverse=Matrix4d(frame).invert()
        fun local(p:Vec3):Vec3 { val v=inverse.transformPosition(Vector3d(p.x,p.y,p.z));return Vec3(v.x,v.y,v.z) }
        val contact=nearestContact(modules,local(start),local(end)) { Matrix4d(poses(it)).invert() }
            ?: return hull
        // Rigid transforms preserve segment fractions and distances.
        val world=start.lerp(end,contact.fraction)
        return if(hull!=null && start.distanceToSqr(hull.point())<=start.distanceToSqr(world)) hull
            else ProjectileCollisionTarget.Hit(world,OBB.Part.BODY)
    }

    internal data class SurfaceContact(val id:ResourceLocation,val fraction:Double)

    /** Public pure narrow-phase: one nearest module, regardless of overlapping boxes. */
    fun nearest(modules: List<AircraftSurfaceModuleInfo>, start: Vec3, end: Vec3,
                inversePose: (String) -> Matrix4d = { Matrix4d() }): ResourceLocation? {
        return nearestContact(modules,start,end,inversePose)?.id
    }

    internal fun nearestContact(modules: List<AircraftSurfaceModuleInfo>, start: Vec3, end: Vec3,
                inversePose: (String) -> Matrix4d = { Matrix4d() }): SurfaceContact? {
        if (!finite(start) || !finite(end)) return null
        var nearest: ResourceLocation? = null; var distance = Double.POSITIVE_INFINITY
        val segments = hashMapOf<String,Pair<Vec3,Vec3>>()
        for (entry in modules) for (box in entry.hitboxes) {
            val (from,to) = segments.getOrPut(box.bone) {
                val inverse = inversePose(box.bone)
                fun local(p: Vec3): Vec3 { val v = inverse.transformPosition(Vector3d(p.x,p.y,p.z)); return Vec3(v.x,v.y,v.z) }
                local(start) to local(end)
            }
            val bounds = AABB(box.min, box.max)
            val hit = if (bounds.contains(from)) from else bounds.clip(from,to).orElse(null) ?: continue
            val d = from.distanceToSqr(hit)
            if (d < distance) { distance = d; nearest = ResourceLocation(entry.id) }
        }
        return nearest?.let { SurfaceContact(it,if(start.distanceToSqr(end)>1e-16) kotlin.math.sqrt(distance/start.distanceToSqr(end)) else 0.0) }
    }

    internal fun pose(p: AircraftSurfaceTransform, controls: Vec3, speed: Double): Matrix4d {
        val input = if (p.speedSchedule.isEmpty()) p.controlWeights.dot(controls).coerceIn(-1.0,1.0)
            else sample(p.speedSchedule,speed) * p.angleSign
        val angle = Math.toRadians(input * p.maxDeflectionDegrees)
        return Matrix4d().translate(p.pivot.x,p.pivot.y,p.pivot.z).rotate(angle,p.axis.x,p.axis.y,p.axis.z)
            .translate(-p.pivot.x,-p.pivot.y,-p.pivot.z)
    }
    private fun sample(points: List<List<Double>>, speed: Double): Double {
        if (speed <= points.first()[0]) return points.first()[1]
        for ((a,b) in points.zipWithNext()) if (speed <= b[0]) return a[1]+(b[1]-a[1])*(speed-a[0])/(b[0]-a[0])
        return points.last()[1]
    }

    private fun boneMatrices(vehicle: VehicleEntity, partialTick: Float): (String) -> Matrix4d {
        val data = vehicle.computed()
        val flight=vehicle.getVehicleFlightPresentationSnapshot(partialTick)
        val c=flight.controlSurfaces
        val controls=Vec3(c?.elevator?.toDouble() ?: 0.0,c?.aileron?.toDouble() ?: 0.0,c?.rudder?.toDouble() ?: 0.0)
        val matrices=hashMapOf("hull" to Matrix4d())
        fun matrix(bone: String): Matrix4d = matrices.getOrPut(bone) {
            val p=requireNotNull(data.aircraftSurfaceTransforms[bone])
            val nativeAngle = when(p.nativeChannel) {
                "ELEVATOR_LEFT" -> net.minecraft.util.Mth.lerp(partialTick,vehicle.flap2LRotO,vehicle.flap2LRot).toDouble()
                "ELEVATOR_RIGHT" -> net.minecraft.util.Mth.lerp(partialTick,vehicle.flap2RRotO,vehicle.flap2RRot).toDouble()
                "RUDDER" -> net.minecraft.util.Mth.lerp(partialTick,vehicle.flap3RotO,vehicle.flap3Rot).toDouble().coerceIn(-20.0,20.0)
                else -> null
            }
            val delta = if(nativeAngle == null) pose(p,controls,flight.motion.length()) else
                Matrix4d().translate(p.pivot.x,p.pivot.y,p.pivot.z).rotate(Math.toRadians(nativeAngle),p.axis.x,p.axis.y,p.axis.z)
                    .translate(-p.pivot.x,-p.pivot.y,-p.pivot.z)
            Matrix4d(matrix(p.parent)).mul(delta)
        }
        return ::matrix
    }

    /** Called only after accepted hull damage, on the same contact receipt. */
    @JvmStatic fun applyAcceptedHit(vehicle: VehicleEntity, start: Vec3, hit: Vec3, incoming: Vec3, damage: Float) {
        if (vehicle.level().isClientSide || !damage.isFinite() || damage <= 0 || !finite(incoming)) return
        val id = contactModule(vehicle,start,hit,incoming)
        val before = id?.let { state(vehicle,it)?.health }
        if (id != null) vehicle.damageVehicleModule(id,damage.toDouble())
        if (com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics.isEnabled(vehicle.level())) {
            com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics.record(vehicle,"aircraft_surface","CONTACT",
                "surface",id,"start",start,"hit",hit,"damage",damage,
                "health_before",before,"health_after",id?.let { state(vehicle,it)?.health })
        }
    }

    /** Read-only presentation of the same articulated volumes used by projectile collision. */
    @JvmStatic fun debugHitboxes(vehicle: VehicleEntity, partialTick: Float): List<OBB> {
        val modules = vehicle.computed().aircraftSurfaceModules
        if (modules.isEmpty()) return emptyList()
        val frame = Matrix4d(vehicle.getVehicleTransform(partialTick))
        val poses = boneMatrices(vehicle, partialTick)
        return modules.flatMap { it.hitboxes }.map { box ->
            val transform = Matrix4d(frame).mul(poses(box.bone))
            val center = box.min.add(box.max).scale(.5)
            val half = box.max.subtract(box.min).scale(.5)
            OBB(transform.transformPosition(Vector3d(center.x, center.y, center.z)),
                Vector3d(half.x, half.y, half.z), transform.getNormalizedRotation(Quaterniond()), OBB.Part.BODY)
        }
    }

    /** Resolve only the accepted contact segment; a surface behind a nearer hull is not a hit. */
    fun contactModule(vehicle: VehicleEntity, start: Vec3, hit: Vec3, incoming: Vec3): ResourceLocation? {
        if (!finite(start) || !finite(hit) || !finite(incoming)) return null
        val data = vehicle.computed(); if (data.aircraftSurfaceModules.isEmpty()) return null
        val worldInverse = Matrix4d(vehicle.getVehicleTransform(1f)).invert()
        fun local(p: Vec3): Vec3 { val v=worldInverse.transformPosition(Vector3d(p.x,p.y,p.z)); return Vec3(v.x,v.y,v.z) }
        // A tiny contact tolerance covers numerical disagreement; never trace through the whole
        // hull to a distant surface behind a different accepted collision.
        val from=local(start); val to=local(hit.add(incoming.normalize().scale(.05)))
        val matrix=boneMatrices(vehicle,1f)
        val inverse=hashMapOf<String,Matrix4d>()
        return nearest(data.aircraftSurfaceModules,from,to) { bone -> inverse.getOrPut(bone) { matrix(bone).invert(Matrix4d()) } }
    }
}
