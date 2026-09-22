package com.yourname.berts_vehicle_pack.init;

import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, BertsVehiclePack.MODID);

    public static final RegistryObject<SoundEvent> MI24V_YAKB_FIRE = register("mi24v_yakb_fire");
    public static final RegistryObject<SoundEvent> MI24V_YAKB_SILENT = register("mi24v_yakb_silent");
    public static final RegistryObject<SoundEvent> MI24V_ATGM_FIRE = register("mi24v_atgm_fire");
    public static final RegistryObject<SoundEvent> MI24V_S8_FIRE = register("mi24v_s8_fire");
    public static final RegistryObject<SoundEvent> MI24V_S13_FIRE = register("mi24v_s13_fire");
    public static final RegistryObject<SoundEvent> MI28N_2A42_FIRE = register("mi28n_2a42_fire");
    public static final RegistryObject<SoundEvent> BMPT_2A42_FIRE = register("bmpt_2a42_fire");
    public static final RegistryObject<SoundEvent> BMP2_2A42_FIRE = register("bmp2_2a42_fire");
    public static final RegistryObject<SoundEvent> BMP2_ENGINE_IDLE = register("bmp2_engine_idle");
    public static final RegistryObject<SoundEvent> BMP2_ENGINE_DRIVE = register("bmp2_engine_drive");
    public static final RegistryObject<SoundEvent> M1_ABRAMS_ELITE_ENGINE_IDLE =
            register("m1_abrams_elite_engine_idle");
    public static final RegistryObject<SoundEvent> M1_ABRAMS_ELITE_ENGINE_DRIVE =
            register("m1_abrams_elite_engine_drive");
    public static final RegistryObject<SoundEvent> ATGM_TUBE_LAUNCH = register("atgm_tube_launch");
    public static final RegistryObject<SoundEvent> ATGM_GUN_LAUNCH = register("atgm_gun_launch");
    public static final RegistryObject<SoundEvent> RICOCHET_NONPENETRATION = register("ricochet_nonpenetration");
    public static final RegistryObject<SoundEvent> IMPACT_STONE = register("impact_stone");
    public static final RegistryObject<SoundEvent> IMPACT_DIRT = register("impact_dirt");
    public static final RegistryObject<SoundEvent> IMPACT_METAL = register("impact_metal");
    public static final RegistryObject<SoundEvent> EXPLOSION_MEDIUM = register("explosion_medium");
    public static final RegistryObject<SoundEvent> SHOOT_TNK_125MM = register("shoot_tnk_125mm");
    public static final RegistryObject<SoundEvent> SOVIET_125MM_AUTOLOADER = register("soviet_125mm_autoloader");
    public static final RegistryObject<SoundEvent> TANK_ENGINE_SILENT = register("tank_engine_silent");
    public static final RegistryObject<SoundEvent> T72B_IDLE = register("t72b_idle");
    public static final RegistryObject<SoundEvent> T72B_ENGINE = register("t72b_engine");
    public static final RegistryObject<SoundEvent> T90A_IDLE = register("t90a_idle");
    public static final RegistryObject<SoundEvent> T90A_ENGINE_RUN = register("t90a_engine_run");

    private ModSounds() {
    }

    private static RegistryObject<SoundEvent> register(String id) {
        return SOUNDS.register(id, () -> SoundEvent.m_262824_(new ResourceLocation(BertsVehiclePack.MODID, id)));
    }
}
