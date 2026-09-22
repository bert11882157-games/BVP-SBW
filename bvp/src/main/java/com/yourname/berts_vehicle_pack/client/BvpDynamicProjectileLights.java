package com.yourname.berts_vehicle_pack.client;

import com.atsuishio.superbwarfare.api.effect.DynamicLightSource;
import com.atsuishio.superbwarfare.api.projectile.ProfiledProjectile;
import com.atsuishio.superbwarfare.api.projectile.ProjectileCombatDescriptor;
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.atsuishio.superbwarfare.api.projectile.ResolvedProjectileProfile;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.effects.BvpTracerProfile;
import com.yourname.berts_vehicle_pack.init.ModEntities;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

final class BvpDynamicProjectileLights {
    private static boolean registrationTried;

    private BvpDynamicProjectileLights() {
    }

    static void ensureRegistered() {
        if (registrationTried) {
            return;
        }
        registrationTried = true;

        List<EntityType<?>> types = dynamicLightTypes();
        if (types.isEmpty()) {
            return;
        }
        if (registerWith("org.thinkingstudio.ryoamiclights.api.DynamicLightHandler",
                "org.thinkingstudio.ryoamiclights.api.DynamicLightHandlers", types)) {
            return;
        }
        registerWith("dev.lambdaurora.lambdynlights.api.DynamicLightHandler",
                "dev.lambdaurora.lambdynlights.api.DynamicLightHandlers", types);
    }

    private static List<EntityType<?>> dynamicLightTypes() {
        List<EntityType<?>> types = new ArrayList<>();
        addIfPresent(types, com.atsuishio.superbwarfare.init.ModEntities.PROJECTILE.get());
        addIfPresent(types, com.atsuishio.superbwarfare.init.ModEntities.CANNON_SHELL.get());
        addIfPresent(types, com.atsuishio.superbwarfare.init.ModEntities.SMALL_CANNON_SHELL.get());
        addIfPresent(types, com.atsuishio.superbwarfare.init.ModEntities.SMALL_ROCKET.get());
        addIfPresent(types, com.atsuishio.superbwarfare.init.ModEntities.MEDIUM_ROCKET.get());
        addIfPresent(types, com.atsuishio.superbwarfare.init.ModEntities.WIRE_GUIDE_MISSILE.get());
        addIfPresent(types, com.atsuishio.superbwarfare.init.ModEntities.TRANSIENT_LIGHT.get());
        addIfPresent(types, ModEntities.S8KO_ROCKET.get());
        addIfPresent(types, ModEntities.S13_ROCKET.get());
        addIfPresent(types, ModEntities.ATAKA_MISSILE.get());
        return types;
    }

    private static void addIfPresent(List<EntityType<?>> types, EntityType<?> type) {
        if (type != null && !types.contains(type)) {
            types.add(type);
        }
    }

    private static boolean registerWith(String handlerName, String handlersName, List<EntityType<?>> types) {
        try {
            Class<?> handlerClass = Class.forName(handlerName);
            Class<?> handlersClass = Class.forName(handlersName);
            Object handler = Proxy.newProxyInstance(handlerClass.getClassLoader(), new Class<?>[]{handlerClass},
                    BvpDynamicProjectileLights::invoke);
            Method register = handlersClass.getMethod("registerDynamicLightHandler", EntityType.class, handlerClass);
            for (EntityType<?> type : types) {
                register.invoke(null, type, handler);
            }
            return true;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    private static Object invoke(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "getLuminance" -> luminance(args != null && args.length > 0 ? args[0] : null);
            case "isWaterSensitive" -> false;
            case "toString" -> "BVP projectile dynamic light handler";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> args != null && args.length > 0 && args[0] == proxy;
            default -> null;
        };
    }

    private static int luminance(Object candidate) {
        if (candidate instanceof DynamicLightSource source) {
            return source.getDynamicLightLuminance();
        }
        if (candidate instanceof Entity entity && candidate instanceof ProfiledProjectile projectile) {
            net.minecraft.resources.ResourceLocation profileId = projectile.getProjectileProfileId();
            if (profileId == null || !BertsVehiclePack.MODID.equals(profileId.m_135827_())) {
                return 0;
            }
            ResolvedProjectileProfile profile = ProjectileProfiles.resolve(entity);
            if (profile == null) {
                return 0;
            }
            BvpTracerProfile tracer = BvpTracerProfile.forEntity(entity);
            if (tracer != null && !tracer.shouldRender(entity)) {
                return 0;
            }
            ProjectileCombatDescriptor combat = profile.getCombat();
            if (combat != null && combat.getMunitionType() != null
                    && BertsVehiclePack.MODID.equals(combat.getMunitionType().m_135827_())
                    && "bullet".equals(combat.getMunitionType().m_135815_())) {
                return entity.f_19797_ < 16 ? Math.min(profile.getLuminance(), 8) : 0;
            }
            return profile.getLuminance();
        }
        return 0;
    }
}
