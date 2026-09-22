package com.yourname.berts_vehicle_pack;

import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.NominalBlockCollisionModel;
import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.NominalProjectileModels;
import com.atsuishio.superbwarfare.api.vehicle.lifecycle.VehicleItemLifecycleProviders;
import com.yourname.berts_vehicle_pack.armor.ArmorImpactHandler;
import com.yourname.berts_vehicle_pack.armor.BvpImpactPresentationProvider;
import com.yourname.berts_vehicle_pack.armor.BvpTaczImpactBridge;
import com.yourname.berts_vehicle_pack.armor.TurretWreckImpactHandler;
import com.yourname.berts_vehicle_pack.client.BvpClientEvents;
import com.yourname.berts_vehicle_pack.effects.BvpProjectileTrailHooks;
import com.yourname.berts_vehicle_pack.effects.BvpFiredVisuals;
import com.yourname.berts_vehicle_pack.entity.armored.damage.BvpFieldRepairAction;
import com.yourname.berts_vehicle_pack.entity.armored.damage.BvpVehicleModules;
import com.yourname.berts_vehicle_pack.entity.helicopter.BvpAutocannonSchedules;
import com.yourname.berts_vehicle_pack.init.ModEntities;
import com.yourname.berts_vehicle_pack.init.ModEntityRenderers;
import com.yourname.berts_vehicle_pack.init.ModItems;
import com.yourname.berts_vehicle_pack.init.ModParticles;
import com.yourname.berts_vehicle_pack.init.ModSounds;
import com.yourname.berts_vehicle_pack.init.ModTabs;
import com.yourname.berts_vehicle_pack.item.BvpVehicleItem;
import com.yourname.berts_vehicle_pack.item.BvpVehicleItemLifecycleProvider;
import com.yourname.berts_vehicle_pack.network.BvpNetwork;
import com.yourname.berts_vehicle_pack.projectile.BvpProjectilePolicies;
import com.yourname.berts_vehicle_pack.client.BvpClientParticles;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(BertsVehiclePack.MODID)
public class BertsVehiclePack {
    public static final String MODID = "berts_vehicle_pack";

    public BertsVehiclePack() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModItems.ITEMS.register(modEventBus);
        ModEntities.register(modEventBus);
        ModSounds.SOUNDS.register(modEventBus);
        ModParticles.PARTICLE_TYPES.register(modEventBus);
        ModTabs.TABS.register(modEventBus);
        VehicleItemLifecycleProviders.register(
                BvpVehicleItem.LIFECYCLE_PROVIDER_ID,
                new BvpVehicleItemLifecycleProvider());
        BvpNetwork.register();
        BvpProjectilePolicies.register();
        BvpFiredVisuals.register();
        BvpVehicleModules.register();
        // Register the repair action used by key input, HUD snapshots, and saved vehicle state.
        BvpFieldRepairAction.register();
        BvpAutocannonSchedules.register();
        registerNominalProjectileModels();
        BvpProjectileTrailHooks.registerExplosionFxHandler();
        ArmorImpactHandler.register();
        BvpImpactPresentationProvider.register();
        if (ModList.get().isLoaded("tacz")) BvpTaczImpactBridge.register();
        TurretWreckImpactHandler.register();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            modEventBus.addListener(ModEntityRenderers::registerEntityRenderers);
            modEventBus.addListener(BvpClientParticles::registerParticleProviders);
            modEventBus.addListener(BvpClientEvents::registerKeyMappings);
            modEventBus.addListener(BvpClientEvents::registerReloadListeners);
            MinecraftForge.EVENT_BUS.register(BvpClientEvents.INSTANCE);
        }
    }

    private static void registerNominalProjectileModels() {
        NominalProjectileModels.registerFastThrowableLinearGravity(
                new ResourceLocation(MODID, "s8ko_rocket"), NominalBlockCollisionModel.STANDARD_PROJECTILE);
        NominalProjectileModels.registerFastThrowableLinearGravity(
                new ResourceLocation(MODID, "s13_rocket"), NominalBlockCollisionModel.STANDARD_PROJECTILE);
    }
}
