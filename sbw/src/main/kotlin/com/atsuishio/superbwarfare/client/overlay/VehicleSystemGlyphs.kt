package com.atsuishio.superbwarfare.client.overlay

import com.atsuishio.superbwarfare.client.weapon.VehicleWeaponHudKind
import com.atsuishio.superbwarfare.api.vehicle.presentation.VehicleModuleHudKind
import net.minecraft.client.gui.GuiGraphics

/** Original pixel silhouettes, laid out against the preserved ground-HUD reference. */
internal object VehicleSystemGlyphs {
    private fun pixels(vararg rows: String) = rows.toList()
    val hull = pixels("..######..", ".########.", "##########", "##########", "##########", "##########", "##########", ".########.", "..######..")
    val track = pixels("###", "#.#", "###", "#.#", "###", "#.#", "###", "#.#", "###", "#.#", "###", "#.#", "###")
    val engine = pixels(".#.#.#.", "#######", "#.#.#.#", "#######", "#.#.#.#", "#######")
    val barrel = pixels("..##..", "..##..", "..##..", "..##..", "..##..", ".####.", "######", "######", ".####.")
    private val compactTrack = pixels("###", "#.#", "###", "#.#", "###", "#.#", "###")
    private val wheel = pixels(".#.", "#.#", ".#.")
    private val compactEngine = pixels("#.#", "###", "#.#")
    private val compactWeapon = pixels(".#.", ".#.", "###")
    private val ammunition = pixels("#.#", "###", "###")
    private val module = pixels("###", "#.#", "###")

    fun module(kind: VehicleModuleHudKind) = when (kind) {
        VehicleModuleHudKind.ENGINE -> compactEngine
        VehicleModuleHudKind.TRACK -> compactTrack
        VehicleModuleHudKind.WHEEL -> wheel
        VehicleModuleHudKind.WEAPON -> compactWeapon
        VehicleModuleHudKind.AMMO -> ammunition
        VehicleModuleHudKind.MODULE -> module
    }

    /** Solid health-colored footprints remain legible at small GUI scales. */
    fun drawModule(g: GuiGraphics, rows: List<String>, x: Int, y: Int, fill: Int, outline: Int) {
        g.fill(x, y, x + rows[0].length, y + rows.size, fill)
    }

    /** Endpoints use the hull projection; no fixed seven-pixel length or surrounding border. */
    fun drawTrack(g: GuiGraphics, x: Int, top: Int, bottom: Int, fill: Int, outline: Int) {
        if (bottom <= top || bottom - top > 128) return
        g.fill(x, top, x + 3, bottom, fill)
    }
    private val tankCannon = pixels("................", "....##..........", "...#####........", "################", "################", "...#####........", "....##..........", "................")
    private val autocannon = pixels("................", "....#####.......", ".###############", "################", "...######.......", ".....###........", ".....###........", "................")
    private val hmg = pixels("................", ".....#..........", ".###############", "################", "..##.###........", ".....##.........", "....#..#........", "...#....#.......")
    private val lmg = pixels("................", "................", "....############", "###########.....", "###..##...#.....", ".....##..#.#....", "........#...#...", "................")
    private val atgm = pixels("................", "..#############.", ".###############", "..#############.", "......###.......", "......###.......", ".....#...#......", "....#.....#.....")
    private val grenadeLauncher = pixels("................", "................", "....##########..", "...###########..", "...##.###.......", ".....###........", "....#...#.......", "...#.....#......")
    private val unknown = pixels("................", "......####......", ".....#....#.....", ".........#......", ".......##.......", "................", ".......##.......", "................")

    fun weapon(kind: VehicleWeaponHudKind) = when(kind) {
        VehicleWeaponHudKind.TANK_CANNON -> tankCannon
        VehicleWeaponHudKind.AUTOCANNON -> autocannon
        VehicleWeaponHudKind.HMG -> hmg
        VehicleWeaponHudKind.LMG -> lmg
        VehicleWeaponHudKind.ATGM -> atgm
        VehicleWeaponHudKind.GRENADE_LAUNCHER -> grenadeLauncher
        VehicleWeaponHudKind.UNKNOWN -> unknown
    }

    fun draw(g: GuiGraphics, rows: List<String>, x: Int, y: Int, pixel: Int, fill: Int, outline: Int = fill) {
        if (fill != outline) for ((r,row) in rows.withIndex()) for ((c,p) in row.withIndex()) if(p=='#')
            g.fill(x+c*pixel-1,y+r*pixel-1,x+(c+1)*pixel+1,y+(r+1)*pixel+1,outline)
        for ((r,row) in rows.withIndex()) for ((c,p) in row.withIndex()) if(p=='#')
            g.fill(x+c*pixel,y+r*pixel,x+(c+1)*pixel,y+(r+1)*pixel,fill)
    }
}
