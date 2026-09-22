package com.atsuishio.superbwarfare.entity.vehicle.utils

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerrainSupportPlaneTest {
    @Test fun groundBiasKeepsOffsetPivotContactsOnTheSupportSurface() {
        val contacts=listOf(-1.3 to 0.47,1.3 to 0.47,-1.3 to 4.54,1.3 to 4.54)
            .map { (x,z) -> Vec3(x,0.027,z) to if(z>2.0) 1.0 else 0.0 }
        val pitch=-Math.toDegrees(kotlin.math.atan(1.0/(4.54-0.47)))
        val bias=TerrainSupportPlane.groundBias(contacts,pitch,0.0,2.2)
        assertTrue(bias < -0.1 && bias > -0.3)
        val matrix=org.joml.Matrix4d().translate(0.0,2.2,0.0).rotateX(Math.toRadians(pitch))
            .translate(0.0,-2.2,0.0).translate(0.0,bias,0.0)
        val gaps=contacts.map { (p,h) -> matrix.transformPosition(org.joml.Vector3d(p.x,p.y,p.z)).y-h }
        assertTrue(gaps.all { it>=-1e-9 && it<0.04 },gaps.toString())
        assertEquals(0.0,gaps.min(),1e-9)
    }
    @Test fun flatSurfaceDoesNotTiltARearOriginTruck() {
        val contacts=listOf(Vec3(-1.3,0.027,0.47),Vec3(1.3,0.027,0.47),Vec3(-1.3,0.027,4.54),Vec3(1.3,0.027,4.54))
        val fitted=TerrainSupportPlane.fit(contacts)!!
        assertEquals(0.0,fitted.x,1e-9);assertEquals(0.0,fitted.z,1e-9)
        assertEquals(fitted,TerrainSupportPlane.fit(contacts.reversed()))
    }
    @Test fun slopesAreIndependentOfOriginAndContactOrdering() {
        val points=listOf(-1.3 to 0.47,1.3 to 0.47,-1.3 to 4.54,1.3 to 4.54)
            .map { (x,z) -> Vec3(x,0.2*x+0.3*z+0.7,z) }
        for (shift in listOf(Vec3.ZERO,Vec3(24.0,40.0,-16.0))) {
            val fit=TerrainSupportPlane.fit(points.reversed().map { it.add(shift) })!!
            assertEquals(0.2,fit.x,1e-9);assertEquals(0.3,fit.z,1e-9)
        }
        assertNull(TerrainSupportPlane.fit(points.take(2)))
        assertNull(TerrainSupportPlane.fit(listOf(Vec3.ZERO,Vec3(0.0,1.0,1.0),Vec3(0.0,2.0,2.0))))
    }
}
