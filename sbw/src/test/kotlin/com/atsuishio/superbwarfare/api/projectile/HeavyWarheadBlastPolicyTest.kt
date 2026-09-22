package com.atsuishio.superbwarfare.api.projectile
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.acos
class HeavyWarheadBlastPolicyTest {
 @Test fun `radii and target classes preserve requested health fractions`() {
  val policy=HeavyWarheadBlastPolicy(4.0,8.0,.5f,2f/3)
  for(d in listOf(0.0,3.99,4.0))for(t in listOf(VehicleType.TANK,VehicleType.APC)) {
   assertTrue(policy.lethal(d,t));assertEquals(250f,policy.damage(d,t,false,250f,250f))
  }
  for(d in listOf(4.001,6.0,8.0)) {
   assertEquals(125f,policy.damage(d,VehicleType.TANK,false,250f,250f))
   assertEquals(100f,policy.damage(d,VehicleType.APC,false,150f,150f),.0001f)
   assertEquals(100f,policy.damage(d,VehicleType.TANK,true,150f,150f),.0001f)
   for(t in listOf(VehicleType.AIRPLANE,VehicleType.HELICOPTER,VehicleType.DRONE))assertTrue(policy.lethal(d,t))
  }
  for(d in listOf(8.001,Double.NaN,Double.POSITIVE_INFINITY,-1.0))assertEquals(0f,policy.damage(d,VehicleType.TANK,false,250f,250f))
 }
 @Test fun `unspecified vehicle type retains explicit armor classification and radius boundaries`() {
  val policy=HeavyWarheadBlastPolicy(4.0,8.0,.5f,2f/3)
  assertTrue(policy.lethal(4.0,null))
  assertEquals(250f,policy.damage(4.0,null,false,250f,250f))
  assertFalse(policy.lethal(4.001,null))
  assertEquals(125f,policy.damage(8.0,null,false,250f,250f))
  assertEquals(100f,policy.damage(8.0,null,true,150f,150f),.0001f)
  assertFalse(policy.lethal(8.001,null))
  assertEquals(0f,policy.damage(8.001,null,true,150f,150f))
 }
 @Test fun `body and seeker angular authority are each tripled at actual flight speeds`() {
  val old=GuidedManeuverPolicy(5.0,30.0,8.0);val next=GuidedManeuverPolicy(15.0,30.0,24.0,3.0)
  val desired=Vec3(1.0,0.0,0.0)
  fun angle(v:Vec3)=acos(v.normalize().z)
  for(speed in listOf(0.5,2.0,5.0,10.0)) {
   val velocity=Vec3(0.0,0.0,speed)
   val a=GuidedMissileGuidance.steerManeuver(velocity,Vec3.ZERO,desired,180.0,old)
   val b=GuidedMissileGuidance.steerManeuver(velocity,Vec3.ZERO,desired,180.0,next)
   assertEquals(3.0,angle(b)/angle(a),1e-7)
   assertEquals(speed,b.length(),1e-9)
   val inherited=Vec3(0.4,-0.2,1.0)
   val inheritedResult=GuidedMissileGuidance.steerManeuver(velocity.add(inherited),inherited,desired,180.0,next).subtract(inherited)
   assertEquals(angle(b),angle(inheritedResult),1e-9)
   assertTrue(Math.toDegrees(angle(b))<=27.000001)
   // Existing public steering remains capped at180deg/s even with an oversized requested rate.
   assertEquals(9.0,Math.toDegrees(angle(GuidedMissileGuidance.steer(velocity,Vec3.ZERO,desired,540.0))),1e-8)
  }
  assertThrows(IllegalArgumentException::class.java) { GuidedManeuverPolicy(15.0,30.0,24.0,3.001) }
  assertThrows(IllegalArgumentException::class.java) { GuidedManeuverPolicy(15.0,30.0,24.0,Double.NaN) }
  val unit=Vec3(0.0,0.0,1.0)
  assertEquals(3.0,angle(GuidedMissileGuidance.steer(unit,Vec3.ZERO,desired,next.seekerRateDegrees))/angle(GuidedMissileGuidance.steer(unit,Vec3.ZERO,desired,old.seekerRateDegrees)),1e-7)
  assertEquals(32f,WarheadFragmentPolicy(192,16.0,8f,4).damageFor(192))
 }
}
