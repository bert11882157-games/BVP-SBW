package com.yourname.berts_vehicle_pack.armor;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

final class ArmorSoundService {
    static final String METAL_HIT_SOUND = "minecraft:block.anvil.place";
    static final String PENETRATION_SOUND = "berts_vehicle_pack:explosion_medium";

    private ArmorSoundService() {
    }

    static void play(Level level, Vec3 point, String soundId, float volume, float pitch) {
        SoundEvent sound = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(soundId));
        if (sound != null) {
            level.m_6263_(null, point.f_82479_, point.f_82480_, point.f_82481_, sound, SoundSource.BLOCKS, volume, pitch);
        }
    }
}
