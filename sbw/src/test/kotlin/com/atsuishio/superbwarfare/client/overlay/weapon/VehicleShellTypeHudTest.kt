package com.atsuishio.superbwarfare.client.overlay.weapon

import com.atsuishio.superbwarfare.api.projectile.ProjectileHullDamageClass

/** No-world checks for the production type mapping and bounded separator placement. */
object VehicleShellTypeHudTest {
    @JvmStatic
    fun main(args: Array<String>) {
        var checks = 0
        for (type in ProjectileHullDamageClass.entries) {
            val key = VehicleShellTypeHud.typeKey(type)
            check(key == if (type == ProjectileHullDamageClass.DEFAULT) null
                else "hud.superbwarfare.shell_type.${type.name.lowercase()}")
            checks++
        }
        check(VehicleShellTypeHud.typeKey(null) == null)
        checks++
        for (height in listOf(180, 240, 360, 540, 720, 1080)) {
            val line = height - 56
            val top = VehicleShellTypeHud.labelTop(line, height * 2 / 3, height, height, 9)
            check(top == line + 4)
            check(top + 9 <= height - 24)
            checks += 2
        }
        check(VehicleShellTypeHud.labelTop(124, 129, 180, 180, 9) == null)
        check(VehicleShellTypeHud.labelTop(124, 120, 136, 180, 9) == null)
        check(VehicleShellTypeHud.labelTop(150, 120, 180, 180, 9) == null)
        check(VehicleShellTypeHud.labelTop(124, 120, 180, 180, 0) == null)
        checks += 4
        println("VehicleShellTypeHud: $checks production helper checks passed")
    }
}
