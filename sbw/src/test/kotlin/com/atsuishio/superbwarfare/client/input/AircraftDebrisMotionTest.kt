package com.atsuishio.superbwarfare.client.input

import com.atsuishio.superbwarfare.client.renderer.AircraftDebrisMotion
import com.atsuishio.superbwarfare.client.renderer.AircraftDebrisContact
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftDebrisMotionTest {
    @Test fun shallowFloorPenetrationDoesNotBecomeAFalseSideWall() {
        val floor = Shapes.box(-8.0, -1.0, -8.0, 8.0, 0.0, 8.0)
        val from = Vec3(0.0, -.02, 0.0)
        val to = from.add(.8, -.03, 0.0)
        val raw = floor.clip(from, to, BlockPos.ZERO)!!
        assertTrue(raw.isInside)
        assertEquals(Direction.WEST, raw.direction, "Vanilla inside ray reports travel-opposing normal")
        val contact = AircraftDebrisContact.sweep(from, to) { a, b -> floor.clip(a,b,BlockPos.ZERO) }!!
        assertEquals(Vec3(0.0,1.0,0.0), contact.normal)
        assertEquals(0.0, contact.position.y, 1e-9)
        val part=AircraftDebrisMotion(from,Vec3(.8,-.03,0.0),Quaternionf(),Vec3.ZERO,0.0)
        part.tick { a,b -> AircraftDebrisContact.relaxedSweep(a,b) { c,d -> floor.clip(c,d,BlockPos.ZERO) } }
        assertTrue(part.velocity.x > .7, "Floor recovery preserves tangential inertia")
        assertTrue(part.position.x > .5, "Fragment continues across the surface")
        val wall = Shapes.box(.5, -8.0, -8.0, 1.5, 8.0, 8.0)
        val wallFrom = Vec3(.52, 0.0, 0.0)
        val wallContact = AircraftDebrisContact.sweep(wallFrom, wallFrom.add(.8,-.03,0.0)) {
            a,b -> wall.clip(a,b,BlockPos.ZERO)
        }!!
        assertEquals(Vec3(-1.0,0.0,0.0), wallContact.normal,
            "Recovery must not turn a genuine wall penetration into a floor")
    }

    private fun ground(from: Vec3, to: Vec3): AircraftDebrisMotion.Contact? =
        AircraftDebrisContact.relaxedSweep(from,to) { a,b ->
            Shapes.box(-100.0,-1.0,-100.0,100.0,0.0,100.0).clip(a,b,BlockPos.ZERO)
        }

    @Test fun fragmentsDeflectThenScrapeWithInertiaAndExpireTenSecondsAfterContact() {
        val part=AircraftDebrisMotion(Vec3(0.0,2.0,0.0),Vec3(.8,-.5,0.0),Quaternionf(),Vec3.ZERO,0.0,9.80665/400.0,2)
        while(!part.grounded && part.age<100) part.tick(::ground)
        assertEquals(0,part.bounces)
        assertTrue(part.grounded)
        val impactX=part.position.x
        val impactSpeed=part.velocity.x
        assertTrue(impactSpeed>.5,"tangential momentum survives initial contact")
        repeat(15) { part.tick(::ground); assertTrue(part.position.y >= -.04 && part.position.y <= .01) }
        assertTrue(part.position.x>impactX+1,"wreck grinds along the surface")
        assertTrue(part.velocity.x in 0.0..impactSpeed)
        assertTrue(part.velocity.x > impactSpeed * .5, "Repeated support contacts must not multiply impact friction every tick")
        while(part.groundedTicks<199)part.tick(::ground)
        assertFalse(part.expired)
        part.tick(::ground);assertTrue(part.expired)
    }

    @Test fun wallCollisionCannotFreezeDebrisInMidair() {
        val part=AircraftDebrisMotion(Vec3(0.0,20.0,0.0),Vec3(1.0,0.0,.5),Quaternionf(),Vec3(.02,.01,0.0),0.0)
        part.tick { from,to -> AircraftDebrisMotion.Contact(from.lerp(to,.5),Vec3(-1.0,0.0,0.0)) }
        val y=part.position.y;val z=part.position.z
        assertFalse(part.grounded)
        repeat(10){part.tick { _,_->null }}
        assertTrue(part.position.y<y-1)
        assertTrue(part.position.z>z+1)
    }

    @Test fun slidingOffALedgeResumesGravityAndWingExpiresTenSecondsAfterImpact() {
        val part=AircraftDebrisMotion(Vec3(0.0,.1,0.0),Vec3(.7,-.2,0.0),Quaternionf(),Vec3.ZERO,0.0)
        part.tick(::ground)
        assertTrue(part.impacted);assertFalse(part.expired)
        val y=part.position.y
        repeat(10){part.tick { _,_->null }}
        assertFalse(part.grounded);assertTrue(part.position.y<y-1)
        repeat(189){part.tick { _,_->null }}
        assertFalse(part.expired)
        part.tick { _,_->null };assertTrue(part.expired)
    }
}
