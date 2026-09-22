package com.atsuishio.superbwarfare.api.projectile.impact;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListMap;

/** Ordered server-side registry dispatched once after an impact result is final. */
public final class ProjectileImpactPresentations {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, ProjectileImpactPresentationProvider> PROVIDERS =
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
        }
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
