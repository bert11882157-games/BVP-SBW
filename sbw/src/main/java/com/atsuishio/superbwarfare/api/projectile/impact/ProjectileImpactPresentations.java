package com.atsuishio.superbwarfare.api.projectile.impact;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Predicate;

/** Ordered server-side registry dispatched once after an impact result is final. */
public final class ProjectileImpactPresentations {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, ProjectileImpactPresentationProvider> PROVIDERS =
            new ConcurrentSkipListMap<>();
    private static final Map<String, Predicate<net.minecraft.world.entity.Entity>> MATERIAL_AUDIO_OWNERS =
            new ConcurrentSkipListMap<>();

    private ProjectileImpactPresentations() {
    }

    public static void register(ResourceLocation id, ProjectileImpactPresentationProvider provider) {
        PROVIDERS.put(Objects.requireNonNull(id, "id").toString(),
                Objects.requireNonNull(provider, "provider"));
    }

    public static void unregister(ResourceLocation id) {
        if (id != null) {
            PROVIDERS.remove(id.toString());
            MATERIAL_AUDIO_OWNERS.remove(id.toString());
        }
    }

    /**
     * An addon that plays its own surface impact sound for some projectiles claims them here, so the base
     * vehicle-hit ping is not played on top of it.
     */
    public static void registerMaterialAudioOwner(ResourceLocation id, Predicate<net.minecraft.world.entity.Entity> owns) {
        MATERIAL_AUDIO_OWNERS.put(Objects.requireNonNull(id, "id").toString(), Objects.requireNonNull(owns, "owns"));
    }

    /** True when a registered addon plays the impact sound of [projectile]. */
    public static boolean ownsMaterialAudio(net.minecraft.world.entity.Entity projectile) {
        if (projectile == null) return false;
        for (Predicate<net.minecraft.world.entity.Entity> owner : MATERIAL_AUDIO_OWNERS.values()) {
            try {
                if (owner.test(projectile)) return true;
            } catch (RuntimeException exception) {
                LOGGER.debug("Material audio owner failed for {}", projectile, exception);
            }
        }
        return false;
    }

    static boolean dispatch(ProjectileImpactContext context, ProjectileImpactResult result) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(result, "result");
        if (context.getProjectile().level().isClientSide()) {
            return false;
        }

        for (Map.Entry<String, ProjectileImpactPresentationProvider> entry : PROVIDERS.entrySet()) {
            try {
                if (entry.getValue().present(context, result)) {
                    return true;
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("Projectile impact presentation provider {} failed for {}",
                        entry.getKey(), context.getProjectile(), exception);
            }
        }
        return false;
    }
}
