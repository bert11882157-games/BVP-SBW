package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.data.vehicle.subdata.AircraftSurfaceBox
import com.atsuishio.superbwarfare.data.vehicle.subdata.AircraftSurfaceModuleInfo
import com.atsuishio.superbwarfare.data.vehicle.subdata.AircraftSurfaceTransform
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4d
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftSurfaceModulesTest {
    @Test fun `MiG19 wing root is not hidden by its coarse terrain contact volume`() {
        val source = requireNotNull(javaClass.getResourceAsStream("/aircraft_mig19_surface_regression.json"))
            .bufferedReader().use { it.readText().removePrefix("\uFEFF") }
        val json = kotlinx.serialization.json.Json
        val data = json.parseToJsonElement(source) as kotlinx.serialization.json.JsonObject
        val modules = json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(
            AircraftSurfaceModuleInfo.serializer()), data.getValue("AircraftSurfaceModules"))
        val authored = json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(
            com.atsuishio.superbwarfare.data.vehicle.subdata.OBBInfo.serializer()), data.getValue("OBB"))
        val wing = modules.first { it.id == AircraftSurfaceModules.WING_LEFT.toString() }
        val box = wing.hitboxes.maxBy { (it.max.x-it.min.x)*(it.max.y-it.min.y)*(it.max.z-it.min.z) }
        val center = box.min.add(box.max).scale(.5)
        val direction = center.normalize().scale(-1.0)
        val start = center.add(direction.scale(-6.0))
        val terrain = (data.getValue("AircraftTerrainContact") as kotlinx.serialization.json.JsonObject)
            .getValue("Fuselage") as kotlinx.serialization.json.JsonObject
        fun vector(key: String): Vec3 {
            val coordinates = terrain.getValue(key) as kotlinx.serialization.json.JsonArray
            return Vec3(coordinates[0].toString().toDouble(),coordinates[1].toString().toDouble(),coordinates[2].toString().toDouble())
        }
        val phantom = net.minecraft.world.phys.AABB(vector("Min"),vector("Max")).clip(start,center).orElseThrow()
        assertNull(AircraftSurfaceModules.nearest(modules,start,phantom.add(direction.scale(.05))))
        val boxes = authored.map { info -> info.getOBB().also { it.center.set(info.position.x,info.position.y,info.position.z) } }
        val hull = com.atsuishio.superbwarfare.world.phys.ProjectileHitSelection.nearestObb(boxes,start,center,0.0)
        val hit = AircraftSurfaceModules.clipGeometry(modules,Matrix4d(),{Matrix4d()},hull,start,center)
        assertNotNull(hit)
        assertEquals(AircraftSurfaceModules.WING_LEFT, AircraftSurfaceModules.nearest(modules,
            start,hit!!.point().add(direction.scale(.05))))
    }
    private fun module(id:String,x:Double,z:Double,bone:String="hull")=AircraftSurfaceModuleInfo().apply {
        this.id="superbwarfare:$id";maxHealthFraction=if(id.startsWith("wing")) .2 else .15
        hitboxes=listOf(AircraftSurfaceBox().apply { this.bone=bone;min=Vec3(x,0.0,z);max=Vec3(x+1,1.0,z+1) })
    }
    @Test fun `side-specific geometry selects one module and misses unrelated hull space`() {
        val modules=listOf(module("wing_left",-4.0,0.0),module("wing_right",3.0,0.0),
            module("elevator_left",-2.0,-5.0),module("elevator_right",1.0,-5.0))
        assertEquals(AircraftSurfaceModules.WING_LEFT,AircraftSurfaceModules.nearest(modules,Vec3(-3.5,.5,3.0),Vec3(-3.5,.5,-1.0)))
        assertEquals(AircraftSurfaceModules.ELEVATOR_RIGHT,AircraftSurfaceModules.nearest(modules,Vec3(1.5,.5,-3.0),Vec3(1.5,.5,-6.0)))
        assertNull(AircraftSurfaceModules.nearest(modules,Vec3(0.0,.5,3.0),Vec3(0.0,.5,-6.0)))
    }
    @Test fun `overlapping boxes do not multiply modules and each bone resolves once`() {
        val first=module("wing_left",-3.0,1.0,"wing")
        first.hitboxes=first.hitboxes+first.hitboxes
        var transforms=0
        val hit=AircraftSurfaceModules.nearest(listOf(first,module("elevator_left",-3.0,-3.0,"wing")),
            Vec3(-2.5,.5,4.0),Vec3(-2.5,.5,-5.0)) { transforms++;Matrix4d() }
        assertEquals(AircraftSurfaceModules.WING_LEFT,hit);assertEquals(1,transforms)
    }
    @Test fun `bind pivot articulation is inverted exactly once for the collision segment`() {
        val p=AircraftSurfaceTransform().apply { pivot=Vec3(4.0,0.0,0.0);axis=Vec3(0.0,0.0,1.0);controlWeights=Vec3(1.0,0.0,0.0);maxDeflectionDegrees=90.0 }
        val pose=AircraftSurfaceModules.pose(p,Vec3(1.0,0.0,0.0),0.0)
        val point=pose.transformPosition(Vector3d(5.0,0.0,0.0))
        assertEquals(4.0,point.x,1e-8);assertEquals(1.0,point.y,1e-8)
        val m=module("wing_right",4.0,0.0,"wing")
        assertEquals(AircraftSurfaceModules.WING_RIGHT,AircraftSurfaceModules.nearest(listOf(m),Vec3(3.5,.5,2.0),Vec3(3.5,.5,-1.0)){Matrix4d(pose).invert()})
    }
    @Test fun `sweep schedule consumes blocks per tick and clamps outside authored interval`() {
        val p=AircraftSurfaceTransform().apply { axis=Vec3(0.0,1.0,0.0);maxDeflectionDegrees=60.0;speedSchedule=listOf(listOf(0.0,0.0),listOf(10.0,1.0)) }
        val mid=AircraftSurfaceModules.pose(p,Vec3.ZERO,5.0).transformPosition(Vector3d(1.0,0.0,0.0))
        assertEquals(kotlin.math.cos(Math.PI/6),mid.x,1e-8)
        val high=AircraftSurfaceModules.pose(p,Vec3.ZERO,100.0).transformPosition(Vector3d(1.0,0.0,0.0))
        assertEquals(.5,high.x,1e-8)
    }
    @Test fun `production union discovers outer wing preserves gap and nearer physical hull`() {
        val modules=listOf(module("wing_left",-20.0,0.0))
        val frame=Matrix4d().translate(100.0,40.0,200.0)
        fun query(x:Double,hull:com.atsuishio.superbwarfare.api.projectile.ProjectileCollisionTarget.Hit?=null)=
            AircraftSurfaceModules.clipGeometry(modules,frame,{Matrix4d()},hull,
                Vec3(x,40.5,205.0),Vec3(x,40.5,195.0))
        val outer=query(80.5)
        assertNotNull(outer);assertEquals(201.0,outer!!.point().z,1e-8)
        assertNull(query(90.0))
        val hull=com.atsuishio.superbwarfare.api.projectile.ProjectileCollisionTarget.Hit(
            Vec3(80.5,40.5,203.0),com.atsuishio.superbwarfare.tools.OBB.Part.BODY)
        assertSame(hull,query(80.5,hull))
    }
    @Test fun `projectile index discovers remote outer surface without changing physical bounds`() {
        val index=com.atsuishio.superbwarfare.entity.vehicle.base.PhysicalBoundsIndex<String>()
        val physical=net.minecraft.world.phys.AABB(-1.0,0.0,-5.0,1.0,2.0,5.0)
        val outerQuery=net.minecraft.world.phys.AABB(-20.0,0.0,0.0,-19.0,1.0,1.0)
        index.update("aircraft",physical.inflate(22.0))
        assertFalse(physical.intersects(outerQuery))
        assertEquals(listOf("aircraft"),index.query(outerQuery))
        index.remove("aircraft");assertTrue(index.query(outerQuery).isEmpty())
    }
}
