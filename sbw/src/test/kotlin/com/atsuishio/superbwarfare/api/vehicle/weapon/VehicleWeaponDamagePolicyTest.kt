package com.atsuishio.superbwarfare.api.vehicle.weapon

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VehicleWeaponDamagePolicyTest {
    @Test fun `vehicle half-inch factor takes precedence without stacking with autocannon factor`() {
        assertEquals(.33,VehicleWeaponDamagePolicy.factor(true,"bullet",12.7,.5))
        assertEquals(.33,VehicleWeaponDamagePolicy.factor(true,"autocannon_shell",12.7,.5))
        for(caliber in listOf(20.0,23.0,25.0,30.0)) assertEquals(.5,VehicleWeaponDamagePolicy.factor(true,"autocannon_shell",caliber,.33))
        assertEquals(13.2,40*VehicleWeaponDamagePolicy.factor(true,"bullet",12.7),.000001)
    }
    @Test fun `infantry large caliber and rocket missile profiles remain unchanged`() {
        for(mounted in listOf(false,true)) for(type in listOf("rocket","atgm","bomb"))
            assertEquals(1.0,VehicleWeaponDamagePolicy.factor(mounted,type,12.7,.33))
        for(caliber in listOf(7.62,12.7,20.0,30.0,37.0))
            assertEquals(1.0,VehicleWeaponDamagePolicy.factor(false,"autocannon_shell",caliber,.5))
        assertEquals(1.0,VehicleWeaponDamagePolicy.factor(true,"autocannon_shell",37.0,.5))
        assertEquals(1.0,VehicleWeaponDamagePolicy.factor(true,"bullet",14.5,.33))
        assertEquals(1.0,VehicleWeaponDamagePolicy.factor(true,"autocannon_shell",Double.NaN,.5))
    }
    @Test fun `native explicit fallback requires an actual vehicle installation and accepted factor`() {
        assertEquals(.5,VehicleWeaponDamagePolicy.factor(true,null,null,.5))
        assertEquals(.33,VehicleWeaponDamagePolicy.factor(true,null,null,.33))
        assertEquals(1.0,VehicleWeaponDamagePolicy.factor(false,null,null,.33))
        assertEquals(1.0,VehicleWeaponDamagePolicy.factor(true,null,null,.165))
    }
    @Test fun `eligible HE direct plus falloff blast receives one exclusive factor per damage component`() {
        for ((munition,caliber,expected) in listOf(Triple("autocannon_shell",30.0,.5),Triple("bullet",12.7,.33),
            Triple("autocannon_shell",37.0,1.0),Triple("rocket",12.7,1.0),Triple("atgm",30.0,1.0),Triple("bomb",30.0,1.0))) {
            val captured=VehicleWeaponDamagePolicy.factor(true,munition,caliber,.5)
            val direct=VehicleWeaponDamagePolicy.scaleDamage(40f,captured)
            val blast=VehicleWeaponDamagePolicy.scaleDamage(80f*.25f,captured)
            assertEquals(60*expected,(direct+blast).toDouble(),1e-5)
            assertEquals(20*expected,blast.toDouble(),1e-5)
        }
        val infantry=VehicleWeaponDamagePolicy.factor(false,"autocannon_shell",30.0,.5)
        assertEquals(80f,VehicleWeaponDamagePolicy.scaleDamage(80f,infantry))
    }
}
