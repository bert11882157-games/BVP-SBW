package com.atsuishio.superbwarfare.api.effect;

import com.atsuishio.superbwarfare.entity.effect.TransientLightEntity;
import com.atsuishio.superbwarfare.init.ModEntities;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Server-authoritative entry point for short-lived, non-block light sources. */
public final class TransientLights {
    private TransientLights() {
    }

    public static boolean spawn(Level level, Vec3 position, int luminance, int lifetimeTicks) {
        if (level == null || level.isClientSide() || position == null || luminance <= 0) {
            return false;
        }

        TransientLightEntity light = new TransientLightEntity(ModEntities.TRANSIENT_LIGHT.get(), level);
        light.setPos(position.x, position.y, position.z);
        light.configure(luminance, lifetimeTicks);
        return level.addFreshEntity(light);
    }

    public static boolean spawn(Level level, Vec3 position, TransientLightSpec spec) {
        return spec != null && spawn(level, position, spec.luminance(), spec.lifetimeTicks());
    }
}
