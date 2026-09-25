package com.atsuishio.superbwarfare.config.server

import com.atsuishio.superbwarfare.config.buildServerConfig

object ExplosionConfig {

    @JvmField
    val EXPLOSION_PENETRATION_RATIO = buildServerConfig {
        push("explosion")

        comment("The percentage of explosion damage you take behind cover")
        comment("躲在掩体后时受到的爆炸伤害比例")
        defineInRange("explosion_penetration_ratio", 15, 0, 100)
    }

    @JvmField
    val EXPLOSION_DESTROY = buildServerConfig {
        comment("Set true to allow Explosion to destroy blocks")
        comment("是否开启爆炸破坏方块")
        define("explosion_destroy", true)
    }

    @JvmField
    val MUNITION_BLOCK_DAMAGE = buildServerConfig {
        comment("Master switch for block damage from munitions: explosions, craters, penetrating bombs and rockets,")
        comment("bullets breaking glass, and vehicle wreck blasts. While false no munition changes a block, whatever")
        comment("explosion_destroy, extra_explosion_effect or allow_projectile_destroy_blocks say")
        comment("弹药破坏方块总开关：为false时任何弹药（爆炸、弹坑、穿透、子弹击碎玻璃等）都不会破坏方块")
        define("munition_block_damage", false)
    }

    /** Explosions may remove blocks only when both the master switch and `explosion_destroy` allow it. */
    @JvmStatic
    fun explosionsBreakBlocks(): Boolean = MUNITION_BLOCK_DAMAGE.get() && EXPLOSION_DESTROY.get()

    /** Pre-explosion block removal (craters, C4 breaching, rocket impact holes) also needs `extra_explosion_effect`. */
    @JvmStatic
    fun extraBlockEffects(): Boolean = explosionsBreakBlocks() && EXTRA_EXPLOSION_EFFECT.get()

    @JvmField
    val EXTRA_EXPLOSION_EFFECT = buildServerConfig {
        comment("Set true to enable extra explosion effect. For example, C4 and RPG will destroy blocks before explosion")
        comment("是否开启额外破坏效果，例如C4和RPG弹头的破坏方块效果")
        define("extra_explosion_effect", true)
    }

    @JvmField
    val FRIENDLY_MINES = buildServerConfig {
        comment("Set true to allow mines to ignore friendly entities")
        comment("地雷等爆炸物是否会无视友方")
        define("friendly_mines", true)
    }

    @JvmField
    val RGO_GRENADE_EXPLOSION_DAMAGE = buildServerConfig {
        push("RGO Grenade")

        comment("The explosion damage of RGO grenade")
        comment("RGO手榴弹的爆炸伤害")
        defineInRange("rgo_grenade_explosion_damage", 90, 1, 10000000)
    }

    @JvmField
    val RGO_GRENADE_TNT_EQUIVALENT_KG = buildServerConfig {
        comment("TNT equivalent of RGO grenade in kg. -1 uses the data table blast/tnt_defaults.json entry superbwarfare:rgo_grenade; 0 keeps the legacy blast")
        comment("RGO grenade的TNT当量(千克)。-1使用数据表默认值；0保留旧版爆炸")
        defineInRange("rgo_grenade_tnt_equivalent_kg", -1.0, -1.0, 100000.0)
    }

    @JvmField
    val RGO_GRENADE_EXPLOSION_RADIUS = buildServerConfig {
        comment("The explosion radius of RGO grenade")
        comment("RGO手榴弹的爆炸半径")
        defineInRange("rgo_grenade_explosion_radius", 5, 1, 50).also { pop() }
    }

    @JvmField
    val M67_GRENADE_EXPLOSION_DAMAGE = buildServerConfig {
        push("M67 Grenade")

        comment("The explosion damage of M67 grenade")
        comment("M67手榴弹的爆炸伤害")
        defineInRange("m67_grenade_explosion_damage", 120, 1, 10000000)
    }

    @JvmField
    val M67_GRENADE_TNT_EQUIVALENT_KG = buildServerConfig {
        comment("TNT equivalent of M67 grenade in kg. -1 uses the data table blast/tnt_defaults.json entry superbwarfare:hand_grenade; 0 keeps the legacy blast")
        comment("M67 grenade的TNT当量(千克)。-1使用数据表默认值；0保留旧版爆炸")
        defineInRange("m67_grenade_tnt_equivalent_kg", -1.0, -1.0, 100000.0)
    }

    @JvmField
    val M67_GRENADE_EXPLOSION_RADIUS = buildServerConfig {
        comment("The explosion radius of M67 grenade")
        comment("M67手榴弹的爆炸半径")
        defineInRange("m67_grenade_explosion_radius", 6, 1, 50).also { pop() }
    }

    @JvmField
    val C4_EXPLOSION_COUNTDOWN = buildServerConfig {
        push("C4")

        comment("The explosion countdown of C4")
        comment("定时C4炸弹的爆炸倒计时")
        defineInRange("c4_explosion_countdown", 514, 1, Int.MAX_VALUE)
    }

    @JvmField
    val C4_EXPLOSION_DAMAGE = buildServerConfig {
        comment("The explosion damage of C4")
        comment("C4炸弹的爆炸伤害")
        defineInRange("c4_explosion_damage", 300, 1, Int.MAX_VALUE)
    }

    @JvmField
    val C4_TNT_EQUIVALENT_KG = buildServerConfig {
        comment("TNT equivalent of C4 in kg. -1 uses the data table blast/tnt_defaults.json entry superbwarfare:c4; 0 keeps the legacy blast")
        comment("C4的TNT当量(千克)。-1使用数据表默认值；0保留旧版爆炸")
        defineInRange("c4_tnt_equivalent_kg", -1.0, -1.0, 100000.0)
    }

    @JvmField
    val C4_EXPLOSION_RADIUS = buildServerConfig {
        comment("The explosion radius of C4")
        comment("C4炸弹的爆炸半径")
        defineInRange("c4_explosion_radius", 10, 1, Int.MAX_VALUE).also { pop() }
    }

    @JvmField
    val CLAYMORE_EXPLOSION_DAMAGE = buildServerConfig {
        push("Claymore")

        comment("The explosion damage of Claymore")
        comment("阔剑地雷的爆炸伤害")
        defineInRange("claymore_explosion_damage", 140, 1, Int.MAX_VALUE)
    }

    @JvmField
    val CLAYMORE_TNT_EQUIVALENT_KG = buildServerConfig {
        comment("TNT equivalent of Claymore in kg. -1 uses the data table blast/tnt_defaults.json entry superbwarfare:claymore; 0 keeps the legacy blast")
        comment("Claymore的TNT当量(千克)。-1使用数据表默认值；0保留旧版爆炸")
        defineInRange("claymore_tnt_equivalent_kg", -1.0, -1.0, 100000.0)
    }

    @JvmField
    val CLAYMORE_EXPLOSION_RADIUS = buildServerConfig {
        comment("The explosion radius of Claymore")
        comment("阔剑地雷的爆炸半径")
        defineInRange("claymore_explosion_radius", 4, 1, Int.MAX_VALUE).also { pop() }
    }

    @JvmField
    val BLU_43_EXPLOSION_DAMAGE = buildServerConfig {
        push("Blu 43")

        comment("The explosion damage of Blu 43")
        comment("蝴蝶雷的爆炸伤害")
        defineInRange("blu_43_explosion_damage", 10, 1, Int.MAX_VALUE)
    }

    @JvmField
    val BLU_43_TNT_EQUIVALENT_KG = buildServerConfig {
        comment("TNT equivalent of Blu 43 in kg. -1 uses the data table blast/tnt_defaults.json entry superbwarfare:blu_43; 0 keeps the legacy blast")
        comment("Blu 43的TNT当量(千克)。-1使用数据表默认值；0保留旧版爆炸")
        defineInRange("blu_43_tnt_equivalent_kg", -1.0, -1.0, 100000.0)
    }

    @JvmField
    val BLU_43_EXPLOSION_RADIUS = buildServerConfig {
        comment("The explosion radius of Blu 43")
        comment("蝴蝶雷的爆炸半径")
        defineInRange("blu_43_explosion_radius", 2, 1, Int.MAX_VALUE).also { pop() }
    }

    @JvmField
    val EDD_EXPLOSION_DAMAGE = buildServerConfig {
        push("EDD")

        comment("The explosion damage of EDD")
        comment("防止攻入装置的爆炸伤害")
        defineInRange("edd_explosion_damage", 60, 1, Int.MAX_VALUE)
    }

    @JvmField
    val EDD_EXPLOSION_RADIUS = buildServerConfig {
        comment("The explosion radius of EDD")
        comment("防止攻入装置的爆炸半径")
        defineInRange("edd_explosion_radius", 3, 1, Int.MAX_VALUE)
    }

    @JvmField
    val EDD_TNT_EQUIVALENT_KG = buildServerConfig {
        comment("TNT equivalent of EDD in kg. -1 uses the data table blast/tnt_defaults.json entry superbwarfare:edd; 0 keeps the legacy blast")
        comment("EDD的TNT当量(千克)。-1使用数据表默认值；0保留旧版爆炸")
        defineInRange("edd_tnt_equivalent_kg", -1.0, -1.0, 100000.0)
    }

    @JvmField
    val EDD_TRACE_RANGE = buildServerConfig {
        comment("The trace range of EDD")
        comment("防止攻入装置的触发距离")
        defineInRange("edd_trace_range", 2, 1, Int.MAX_VALUE).also { pop() }
    }

    @JvmField
    val TM_62_EXPLOSION_DAMAGE = buildServerConfig {
        push("Tm 62")

        comment("The explosion damage of Tm 62")
        comment("TM62反坦克地雷的爆炸伤害")
        defineInRange("tm_62_explosion_damage", 450, 1, Int.MAX_VALUE)
    }

    @JvmField
    val TM_62_TNT_EQUIVALENT_KG = buildServerConfig {
        comment("TNT equivalent of Tm 62 in kg. -1 uses the data table blast/tnt_defaults.json entry superbwarfare:tm_62; 0 keeps the legacy blast")
        comment("Tm 62的TNT当量(千克)。-1使用数据表默认值；0保留旧版爆炸")
        defineInRange("tm_62_tnt_equivalent_kg", -1.0, -1.0, 100000.0)
    }

    @JvmField
    val TM_62_EXPLOSION_RADIUS = buildServerConfig {
        comment("The explosion radius of Tm 62")
        comment("TM62反坦克地雷的爆炸半径")
        defineInRange("tm_62_explosion_radius", 13, 1, Int.MAX_VALUE).also { pop() }
    }

    @JvmField
    val LUNGE_MINE_EXPLOSION_DAMAGE = buildServerConfig {
        push("Lunge Mine")

        comment("The explosion damage of Lunge Mine")
        comment("突刺爆雷的爆炸伤害")
        defineInRange("lunge_mine_explosion_damage", 60, 1, Int.MAX_VALUE)
    }

    @JvmField
    val LUNGE_MINE_ATTACK_DAMAGE = buildServerConfig {
        comment("The attack damage of Lunge Mine")
        comment("突刺爆雷的直击伤害")
        defineInRange("lunge_mine_attack_damage", 600, 1, Int.MAX_VALUE)
    }

    @JvmField
    val LUNGE_MINE_TNT_EQUIVALENT_KG = buildServerConfig {
        comment("TNT equivalent of Lunge Mine in kg. -1 uses the data table blast/tnt_defaults.json entry superbwarfare:lunge_mine; 0 keeps the legacy blast")
        comment("Lunge Mine的TNT当量(千克)。-1使用数据表默认值；0保留旧版爆炸")
        defineInRange("lunge_mine_tnt_equivalent_kg", -1.0, -1.0, 100000.0)
    }

    @JvmField
    val LUNGE_MINE_EXPLOSION_RADIUS = buildServerConfig {
        comment("The explosion radius of Lunge Mine")
        comment("突刺爆雷的爆炸半径")
        defineInRange("lunge_mine_explosion_radius", 4, 1, Int.MAX_VALUE).also { pop() }
    }

    @JvmField
    val PTKM_1R_EXPLOSION_DAMAGE = buildServerConfig {
        push("Ptkm 1r")

        comment("The explosion damage of Ptkm 1r")
        comment("PTKM1R地雷的爆炸伤害")
        defineInRange("ptkm_1r_explosion_damage", 100, 1, Int.MAX_VALUE)
    }

    @JvmField
    val PTKM_1R_EXPLOSION_RADIUS = buildServerConfig {
        comment("The explosion radius of Ptkm 1r")
        comment("PTKM1R地雷的爆炸半径")
        defineInRange("ptkm_1r_explosion_radius", 6, 1, Int.MAX_VALUE)
    }

    @JvmField
    val PTKM_1R_TNT_EQUIVALENT_KG = buildServerConfig {
        comment("TNT equivalent of Ptkm 1r in kg. -1 uses the data table blast/tnt_defaults.json entry superbwarfare:ptkm_1r; 0 keeps the legacy blast")
        comment("Ptkm 1r的TNT当量(千克)。-1使用数据表默认值；0保留旧版爆炸")
        defineInRange("ptkm_1r_tnt_equivalent_kg", -1.0, -1.0, 100000.0)
    }

    @JvmField
    val PTKM_1R_PROJECTILE_HIT_DAMAGE = buildServerConfig {
        comment("The hit damage of projectile launched by Ptkm 1r")
        comment("PTKM1R地雷发射的投射物的直击伤害")
        defineInRange("ptkm_1r_projectile_hit_damage", 500, 1, Int.MAX_VALUE)
    }

    @JvmField
    val PTKM_1R_PROJECTILE_EXPLOSION_DAMAGE = buildServerConfig {
        comment("The explosion damage of projectile launched by Ptkm 1r")
        comment("PTKM1R地雷发射的投射物的爆炸伤害")
        defineInRange("ptkm_1r_projectile_explosion_damage", 80, 1, Int.MAX_VALUE)
    }

    @JvmField
    val PTKM_1R_PROJECTILE_TNT_EQUIVALENT_KG = buildServerConfig {
        comment("TNT equivalent of the Ptkm 1r projectile in kg. -1 uses the data table blast/tnt_defaults.json entry superbwarfare:ptkm_projectile; 0 keeps the legacy blast")
        comment("the Ptkm 1r projectile的TNT当量(千克)。-1使用数据表默认值；0保留旧版爆炸")
        defineInRange("ptkm_1r_projectile_tnt_equivalent_kg", -1.0, -1.0, 100000.0)
    }

    @JvmField
    val PTKM_1R_PROJECTILE_EXPLOSION_RADIUS = buildServerConfig {
        comment("The explosion radius of projectile launched by Ptkm 1r")
        comment("PTKM1R地雷发射的投射物的爆炸半径")
        defineInRange("ptkm_1r_projectile_explosion_radius", 7, 1, Int.MAX_VALUE).also { pop(2) }
    }
}
