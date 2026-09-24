package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonParser
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftMountSweepTest {
    private val mount = JsonParser.parseString("""{"Position":[4,1,0],"SweepFrames":[{
        "Pivot":[1,1,0],"Axis":[0,1,0],"MaxDegrees":90,"Points":[[0,0],[1,0],[3,1]]}]}""").asJsonObject
    @Test fun wingAttachmentSweepsButRackStaysLongitudinal() {
        val sweep = AircraftMountSweep.decode(mount,1).single()
        assertEquals(Vec3(4.0,1.0,0.0),sweep.position(Vec3(4.0,1.0,0.0),1.0))
        val end = sweep.position(Vec3(4.0,1.0,0.0),3.0)
        assertEquals(1.0,end.x,1e-9); assertEquals(-3.0,end.z,1e-9)
        assertEquals(0.5,sweep.fraction(2.0),1e-9)
        val rackOffset = Vec3(0.2,-0.25,0.5)
        val rack = AircraftMountSweep.position(mount,0,Vec3(4.0,1.0,0.0).add(rackOffset),3.0)
        assertTrue(rack.subtract(end).distanceTo(rackOffset)<1e-9)
        assertEquals(1.0,sweep.fraction(100.0)); assertEquals(0.0,sweep.fraction(Double.NaN))
    }
    @Test fun malformedOrInternalSweepCannotBeAccepted() {
        assertThrows(IllegalArgumentException::class.java) { AircraftMountSweep.decode(mount,2) }
        assertThrows(IllegalArgumentException::class.java) {
            AircraftMountSweep.decode(mount.deepCopy().apply { addProperty("Internal",true) },1)
        }
        val bad = mount.deepCopy()
        bad.getAsJsonArray("SweepFrames")[0].asJsonObject.add("Points",JsonParser.parseString("[[0,0],[2,1],[1,0.5]]"))
        assertThrows(IllegalArgumentException::class.java) { AircraftMountSweep.decode(bad,1) }
    }
}
