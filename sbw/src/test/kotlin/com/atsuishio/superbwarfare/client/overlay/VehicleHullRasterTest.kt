package com.atsuishio.superbwarfare.client.overlay

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VehicleHullRasterTest {
    private fun model(bones:String) = JsonParser.parseString("""{"minecraft:geometry":[{"bones":[$bones]}]}""").asJsonObject

    @Test fun moduleProjectionMatchesOffCenterAsymmetricHull() {
        val raster = VehicleHullRaster.create(model("""{"name":"hull","cubes":[{"origin":[4,0,-8],"size":[8,2,20]}]}"""))!!
        assertEquals(0.0, raster.hudX(8.0), 1e-9)
        assertEquals(0.0, raster.hudY(2.0), 1e-9)
        // Bedrock reflects X, so a module at authored +X belongs on the left side of the silhouette.
        assertTrue(raster.hudX(11.0) < 0)
        assertTrue(raster.hudY(-7.0) < 0)
        val pixelX = ((raster.hudX(11.0) / 36 + 0.5) * raster.size).toInt()
        val pixelY = ((raster.hudY(-7.0) / 36 + 0.5) * raster.size).toInt()
        assertNotEquals(0, raster.alpha[pixelY * raster.size + pixelX].toInt())
    }

    @Test fun usesActualPolygonOutlineAndExcludesTurret() {
        val hull="""{"name":"hull","poly_mesh":{"positions":[[-1,0,-2],[1,0,-2],[0,0,2]],"polys":[[[0],[1],[2]]]}}"""
        val base=VehicleHullRaster.create(model(hull))!!
        val withTurret=VehicleHullRaster.create(model(hull+""",{"name":"turret","parent":"hull","cubes":[{"origin":[-50,0,-50],"size":[100,10,100]}]}"""))!!
        assertArrayEquals(base.alpha,withTurret.alpha)
        assertEquals(1,base.polygonCount)
        assertTrue(base.alpha[20*192+96].toInt()!=0)
        assertEquals(0,base.alpha[170*192+65].toInt())
        assertTrue(base.alpha[170*192+96].toInt()!=0)
    }

    @Test fun cubeGeometryKeepsAspectAndHonorsRotation() {
        fun cube(rotation:Int)=VehicleHullRaster.create(model("""{"name":"body","rotation":[0,$rotation,0],"cubes":[{"origin":[-1,0,-3],"size":[2,1,6]}]}"""))!!
        val forward=cube(0);val turned=cube(90)
        assertTrue(forward.alpha[10*192+96].toInt()!=0)
        assertEquals(0,turned.alpha[10*192+96].toInt())
        assertTrue(turned.alpha[96*192+10].toInt()!=0)
        assertEquals(0,forward.alpha[96*192+10].toInt())
    }
}
