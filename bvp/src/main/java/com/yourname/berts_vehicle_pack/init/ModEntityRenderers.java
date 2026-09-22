package com.yourname.berts_vehicle_pack.init;

import com.atsuishio.superbwarfare.client.renderer.projectile.ProjectileVisualProvider;
import com.atsuishio.superbwarfare.client.renderer.projectile.ProjectileVisualProviders;
import com.atsuishio.superbwarfare.client.renderer.entity.ResourceVehicleRenderer;
import com.atsuishio.superbwarfare.client.renderer.vehicle.VehicleRenderBackendProvider;
import com.atsuishio.superbwarfare.client.renderer.vehicle.VehicleRenderBackends;
import com.atsuishio.superbwarfare.entity.projectile.CannonShellEntity;
import com.atsuishio.superbwarfare.entity.projectile.MediumRocketEntity;
import com.atsuishio.superbwarfare.entity.projectile.SmallRocketEntity;
import com.atsuishio.superbwarfare.entity.projectile.WireGuideMissileEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.client.renderer.BaseTankRenderer;
import com.yourname.berts_vehicle_pack.client.renderer.BaseTrackedVehicleRenderer;
import com.yourname.berts_vehicle_pack.client.renderer.BaseVehicleRenderer;
import com.yourname.berts_vehicle_pack.client.renderer.BmptRenderer;
import com.yourname.berts_vehicle_pack.client.renderer.BvpSpinningProjectileRenderer;
import com.yourname.berts_vehicle_pack.client.renderer.BvpTurretWreckVisualProvider;
import com.atsuishio.superbwarfare.client.renderer.entity.TurretWreckVisualProviders;
import com.yourname.berts_vehicle_pack.effects.BvpTracerProfile;
import com.yourname.berts_vehicle_pack.client.renderer.Ka50Renderer;
import com.yourname.berts_vehicle_pack.client.renderer.Mi24VRenderer;
import com.yourname.berts_vehicle_pack.client.renderer.Mi28NRenderer;
import com.yourname.berts_vehicle_pack.client.renderer.RotorVisualController;
import com.yourname.berts_vehicle_pack.client.renderer.T72BRenderer;
import com.yourname.berts_vehicle_pack.client.renderer.T90ARenderer;
import com.yourname.berts_vehicle_pack.client.renderer.Zsu23_4Renderer;
import com.yourname.berts_vehicle_pack.entity.helicopter.BvpHelicopterEntity;
import com.example.sbwmeshloader.core.PolyMeshModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.registries.RegistryObject;

public class ModEntityRenderers {
    private static final ResourceLocation TURRET_WRECK_VISUAL_PROVIDER =
            new ResourceLocation(BertsVehiclePack.MODID, "turret_wreck_visuals");
    private static final float ROCKET_SPIN_DEGREES_PER_TICK = 540.0F;

    public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        registerProjectileVisualProviders();
        TurretWreckVisualProviders.register(TURRET_WRECK_VISUAL_PROVIDER, BvpTurretWreckVisualProvider::new);
        event.registerEntityRenderer(ModEntities.ATAKA_MISSILE.get(), context ->
                new BvpSpinningProjectileRenderer<>(context,
                        new ResourceLocation(BertsVehiclePack.MODID, "custom_geo/mi24v_atgm_projectile.geo.json"),
                        new ResourceLocation(BertsVehiclePack.MODID, "textures/entity/mi24v_atgm.png"),
                        330.0F));
        event.registerEntityRenderer(ModEntities.S8KO_ROCKET.get(), context ->
                new BvpSpinningProjectileRenderer<>(context,
                        new ResourceLocation(BertsVehiclePack.MODID, "custom_geo/mi24v_s8ko_projectile.geo.json"),
                        new ResourceLocation(BertsVehiclePack.MODID, "textures/entity/mi24v_s8ko.png"),
                        ROCKET_SPIN_DEGREES_PER_TICK));
        event.registerEntityRenderer(ModEntities.S13_ROCKET.get(), context ->
                new BvpSpinningProjectileRenderer<>(context,
                        new ResourceLocation(BertsVehiclePack.MODID, "custom_geo/mi24v_s13_projectile.geo.json"),
                        new ResourceLocation(BertsVehiclePack.MODID, "textures/entity/mi24v_s13.png"),
                        ROCKET_SPIN_DEGREES_PER_TICK));
        registerVehicle(event, ModEntities.T72B, "t72b", T72BRenderer::new);
        registerVehicle(event, ModEntities.T80B_OBR1976, "t80b_obr1976", standardTank(
                "custom_geo/t80b_obr1976.geo.json", "textures/entity/t80b_obr1976.png",
                6, 10.60098F, 9.24247F, -58.47391F, 50.8423F, 38, "T80BRenderer"));
        registerVehicle(event, ModEntities.M60A1, "m60a1", standardTank(
                "custom_geo/m60a1.geo.json", "textures/entity/m60a1.png",
                6, 11.82913F, 11.13837F, -45.84844F, 65.6262F, 65, "M60A1Renderer"));
        registerVehicle(event, ModEntities.M1_ABRAMS_ELITE, "m1_abrams_elite", standardTank(
                "custom_geo/m1_abrams_elite.geo.json", "textures/entity/m1_abrams_elite.png",
                7, 11.1399F, 10.44915F, -50.76291F, 49.50433F, 60, "M1AbramsEliteRenderer"));
        registerVehicle(event, ModEntities.M1A2_ABRAMS_SEP_V2, "m1a2_abrams_sep_v2", standardTank(
                "custom_geo/m1a2_abrams_sep_v2.geo.json", "textures/entity/m1a2_abrams_sep_v2.png",
                7, 11.1399F, 10.44915F, -50.76291F, 49.50433F, 60, "M1A2AbramsSepV2Renderer"));
        registerVehicle(event, ModEntities.T64B_OBR1976, "t64b_obr1976", standardTank(
                "custom_geo/t64b_obr1976.geo.json", "textures/entity/t64b_obr1976.png",
                6, 10.32736F, 10.03826F, -59.04456F, 46.99465F, 60, "T64BObr1976Renderer"));
        registerVehicle(event, ModEntities.M48A3_ELITE, "m48a3_elite", standardTank(
                "custom_geo/m48a3_elite.geo.json", "textures/entity/m48a3_elite.png",
                6, 11.60438F, 10.91362F, -49.89389F, 62.47974F, 65, "M48A3EliteRenderer"));
        registerVehicle(event, ModEntities.T80U_OBR1985, "t80u_obr1985", standardTank(
                "custom_geo/t80u_obr1985.geo.json", "textures/entity/t80u_obr1985.png",
                6, 12.49761F, 11.00006F, -70.23096F, 61.01056F, 38, "T80UObr1985Renderer"));
        registerVehicle(event, ModEntities.T72A, "t72a", standardTank(
                "custom_geo/t72a.geo.json", "textures/entity/t72a.png",
                6, 9.09873F, 8.40798F, -54.11257F, 46.72195F, 48, "T72ARenderer"));
        registerVehicle(event, ModEntities.T72B3, "t72b3", standardTank(
                "custom_geo/t72b3.geo.json", "textures/entity/t72b3.png",
                6, 9.09873F, 8.40798F, -52.60377F, 45.21315F, 55, "T72B3Renderer"));
        registerVehicle(event, ModEntities.T72B3_UBH_COPE, "t72b3_ubh_cope", standardTank(
                "custom_geo/t72b3_ubh_cope.geo.json", "textures/entity/t72b3_ubh_cope.png",
                6, 8.71834F, 8.02759F, -49.97358F, 42.9525F, 51, "T72B3UbhCopeRenderer"));
        registerVehicle(event, ModEntities.T90A, "t90a", T90ARenderer::new);
        registerVehicle(event, ModEntities.T90M, "t90m", profileTrackedVehicle(
                "custom_geo/t90m.geo.json", "textures/entity/t90m.png", "T90MRenderer"));
        registerVehicle(event, ModEntities.BMPT, "bmpt", BmptRenderer::new);
        registerVehicle(event, ModEntities.ZSU23_4, "zsu23_4", Zsu23_4Renderer::new);
        registerVehicle(event, ModEntities.BMP3M_ELITE, "bmp3m_elite", standardTrackedVehicle(
                "custom_geo/bmp3m_elite.geo.json", "textures/entity/bmp3m_elite.png",
                6, 9.40497F, 8.78666F, -43.33369F, 56.0832F, "Bmp3mEliteRenderer"));
        registerVehicle(event, ModEntities.M2_BRADLEY, "m2_bradley", standardTrackedVehicle(
                "custom_geo/m2_bradley.geo.json", "textures/entity/m2_bradley.png",
                6, 9.52278F, 8.50787F, -61.01795F, 35.87339F, "M2BradleyRenderer"));
        registerVehicle(event, ModEntities.MARDER_1A1, "marder_1a1", standardTrackedVehicle(
                "custom_geo/marder_1a1.geo.json", "textures/entity/marder_1a1.png",
                6, 9.49123F, 9.85433F, -49.16043F, 48.86598F, "Marder1A1Renderer"));
        registerVehicle(event, ModEntities.CV9040_NO_NET, "cv9040_no_net", standardTrackedVehicle(
                "custom_geo/cv9040_no_net.geo.json", "textures/entity/cv9040_no_net.png",
                7, 9.07228F, 8.0799F, -47.88167F, 44.36535F, "Cv9040NoNetRenderer"));
        registerVehicle(event, ModEntities.M551A1, "m551a1", standardTrackedVehicle(
                "custom_geo/m551a1.geo.json", "textures/entity/m551a1.png",
                5, 7.86062F, 8.36815F, -47.195F, 48.96166F, "M551A1Renderer"));
        registerVehicle(event, ModEntities.BMP_1AM, "bmp_1am", profileTrackedVehicle(
                "custom_geo/bmp_1am.geo.json", "textures/entity/bmp_1am.png", "Bmp1amRenderer"));
        registerVehicle(event, ModEntities.MARDER_1A2, "marder_1a2", profileTrackedVehicle(
                "custom_geo/marder_1a2.geo.json", "textures/entity/marder_1a2.png", "Marder1A2Renderer"));
        registerVehicle(event, ModEntities.M41, "m41", profileTrackedVehicle(
                "custom_geo/m41.geo.json", "textures/entity/m41.png", "M41Renderer"));
        registerVehicle(event, ModEntities.PANTHER_G, "panther_g", profileTrackedVehicle(
                "custom_geo/panther_g.geo.json", "textures/entity/panther_g.png", "PantherGRenderer"));
        registerVehicle(event, ModEntities.STUG_III, "stug_iii", profileTrackedVehicle(
                "custom_geo/stug_iii.geo.json", "textures/entity/stug_iii.png", "StugIIIRenderer"));
        registerVehicle(event, ModEntities.T62A, "t_62a", profileTrackedVehicle(
                "custom_geo/t_62a.geo.json", "textures/entity/t_62a.png", "T62ARenderer"));
        registerVehicle(event, ModEntities.TIGER_1, "tiger_1", profileTrackedVehicle(
                "custom_geo/tiger_1.geo.json", "textures/entity/tiger_1.png", "Tiger1Renderer"));
        registerVehicle(event, ModEntities.TIGER_II, "tiger_ii", profileTrackedVehicle(
                "custom_geo/tiger_ii.geo.json", "textures/entity/tiger_ii.png", "TigerIIRenderer"));
        registerVehicle(event, ModEntities.S2S25_SPRUT_SD, "2s25_sprut_sd", profileTrackedVehicle(
                "custom_geo/2s25_sprut_sd.geo.json", "textures/entity/2s25_sprut_sd.png", "S2s25SprutSdRenderer"));
        registerVehicle(event, ModEntities.KAMAZ4310, "kamaz4310", standardVehicle(
                "custom_geo/kamaz4310.geo.json", "textures/entity/kamaz4310.png", "Kamaz4310Renderer"));
        registerVehicle(event, ModEntities.BM_21_GRAD, "bm_21_grad", standardVehicle(
                "custom_geo/bm_21_grad.geo.json", "textures/entity/bm_21_grad.png", "Bm21GradRenderer"));
        registerVehicle(event, ModEntities.AMX_10RC, "amx_10rc", standardVehicle(
                "custom_geo/amx_10rc.geo.json", "textures/entity/amx_10rc.png", "Amx10rcRenderer"));
        registerVehicle(event, ModEntities.BTR60PB, "btr_60pb", standardVehicle(
                "custom_geo/btr_60pb.geo.json", "textures/entity/btr_60pb.png", "Btr60pbRenderer"));
        registerVehicle(event, ModEntities.VBCI, "vbci", standardVehicle(
                "custom_geo/vbci.geo.json", "textures/entity/vbci.png", "VbciRenderer"));
        registerVehicle(event, ModEntities.LAV25, "lav25", standardVehicle(
                "custom_geo/lav25.geo.json", "textures/entity/lav25.png", "Lav25Renderer"));
        registerVehicle(event, ModEntities.M1128, "m1128", standardVehicle(
                "custom_geo/m1128.geo.json", "textures/entity/m1128.png", "M1128Renderer"));
        registerVehicle(event, ModEntities.BTR152, "btr_152", standardVehicle(
                "custom_geo/btr_152.geo.json", "textures/entity/btr_152.png", "Btr152Renderer"));
        registerVehicle(event, ModEntities.LEO2A6, "leo2a6", standardTank(
                "custom_geo/leo2a6.geo.json", "textures/entity/leo2a6.png",
                7, 14.1688F, 13.47805F, -84.9836F, 76.91575F, 38, "Leo2A6Renderer"));
        registerVehicle(event, ModEntities.TOYOTA_JIHAD_DSHK, "toyota_jihad_dshk", standardVehicle(
                "custom_geo/toyota_jihad_dshk.geo.json", "textures/entity/toyota_jihad_dshk.png",
                "ToyotaJihadDshkRenderer"));
        registerVehicle(event, ModEntities.TOYOTA_JIHAD_SPG9, "toyota_jihad_spg9", standardVehicle(
                "custom_geo/toyota_jihad_spg9.geo.json", "textures/entity/toyota_jihad_spg9.png",
                "ToyotaJihadSpg9Renderer"));
        registerVehicle(event, ModEntities.BTR80A, "btr80a", standardTank(
                "custom_geo/btr80a.geo.json", "textures/entity/btr80a.png",
                4, 12.53998F, 10.94923F, -56.47495F, 58.43429F, "Btr80ARenderer"));
        registerVehicle(event, ModEntities.BMP2, "bmp2", standardTrackedVehicle(
                "custom_geo/bmp2.geo.json", "textures/entity/bmp2.png",
                6, 11.8637F, 12.61364F, -70.28859F, 58.91292F, "Bmp2Renderer"));
        registerVehicle(event, ModEntities.BMP2M, "bmp2m", profileTrackedVehicle(
                "custom_geo/bmp2m.geo.json", "textures/entity/bmp2m.png", "Bmp2MRenderer"));
        registerVehicle(event, ModEntities.T55A, "t55a_2_0", standardTank(
                "custom_geo/t55a_2_0.geo.json", "textures/entity/t55a_2_0.png",
                5, 11.86139F, 11.40577F, -62.00719F, 60.16311F, 70, "T55ARenderer"));
        registerVehicle(event, ModEntities.ZTZ99A, "ztz99a", standardTank(
                "custom_geo/ztz99a.geo.json", "textures/entity/ztz99a.png",
                6, 9.3232F, 8.63244F, -48.19964F, 56.63458F, 58, "Ztz99ARenderer"));
        registerVehicle(event, ModEntities.TOYOTA_JIHAD_BMP1, "toyota_jihad_bmp1", standardVehicle(
                "custom_geo/toyota_jihad_bmp1.geo.json", "textures/entity/toyota_jihad_bmp1.png",
                "ToyotaJihadBmp1Renderer"));
        registerVehicle(event, ModEntities.TOYOTA_JIHAD_S5, "toyota_jihad_s5", standardVehicle(
                "custom_geo/toyota_jihad_s5.geo.json", "textures/entity/toyota_jihad_s5.png",
                "ToyotaJihadS5Renderer"));
        registerVehicle(event, ModEntities.ZU23_2, "zu23_2", standardVehicle(
                "custom_geo/zu23_2.geo.json", "textures/entity/zu23_2.png", "Zu23_2Renderer"));
        registerVehicle(event, ModEntities.MI24V, "mi24v", Mi24VRenderer::new);
        registerVehicle(event, ModEntities.MI28N, "mi28n", Mi28NRenderer::new);
        registerVehicle(event, ModEntities.KA50, "ka50", Ka50Renderer::new);
        registerVehicle(event, ModEntities.AH6J, "ah_6j", standardHelicopter(
                "custom_geo/ah_6j.geo.json", "textures/entity/ah_6j.png", "Ah6jRenderer"));
        registerVehicle(event, ModEntities.AH1G_COBRA, "ah_1g_cobra", standardHelicopter(
                "custom_geo/ah_1g_cobra.geo.json", "textures/entity/ah_1g_cobra.png", "Ah1gCobraRenderer"));
        registerVehicle(event, ModEntities.KORD_TRIPOD, "kord_tripod", standardVehicle(
                "custom_geo/kord_tripod.geo.json", "textures/entity/kord_tripod.png", "KordTripodRenderer"));
        registerVehicle(event, ModEntities.MILAN_TRIPOD, "milan_tripod", standardVehicle(
                "custom_geo/milan_tripod.geo.json", "textures/entity/milan_tripod.png", "MilanTripodRenderer"));
        registerVehicle(event, ModEntities.BROWNING_TRIPOD, "browning_tripod", standardVehicle(
                "custom_geo/browning_tripod.geo.json", "textures/entity/browning_tripod.png", "BrowningTripodRenderer"));
        registerVehicle(event, ModEntities.TOW_TRIPOD, "tow_tripod", standardVehicle(
                "custom_geo/tow_tripod.geo.json", "textures/entity/tow_tripod.png", "TowTripodRenderer"));
        registerVehicle(event, ModEntities.MIG19, "mig19", standardVehicle(
                "custom_geo/mig19.geo.json", "textures/entity/mig19.png", "Mig19Renderer"));
    }

    private static <T extends GeoVehicleEntity> void registerVehicle(
            EntityRenderersEvent.RegisterRenderers event,
            RegistryObject<EntityType<T>> entityType,
            String vehicleId,
            VehicleRenderBackendProvider backendProvider) {
        VehicleRenderBackends.register(bvp("vehicle/" + vehicleId), backendProvider);
        event.registerEntityRenderer(entityType.get(), ResourceVehicleRenderer::new);
    }

    private static void registerProjectileVisualProviders() {
        registerTracerVisual("scaled_bullet");
        registerTracerVisual("scaled_small_cannon");
        registerCannonVisual("tank_shell_he", "he");
        registerCannonVisual("tank_shell_heatfs", "heatfs");
        registerCannonVisual("tank_shell_apfsds", "apfsds");
        registerVisual("small_rocket", context -> adapt(SmallRocketEntity.class,
                new BvpSpinningProjectileRenderer<>(context,
                        bvp("custom_geo/mi24v_s8ko_projectile.geo.json"),
                        bvp("textures/entity/mi24v_s8ko.png"), ROCKET_SPIN_DEGREES_PER_TICK)));
        registerVisual("medium_rocket", context -> adapt(MediumRocketEntity.class,
                new BvpSpinningProjectileRenderer<>(context,
                        bvp("custom_geo/mi24v_s13_projectile.geo.json"),
                        bvp("textures/entity/mi24v_s13.png"), ROCKET_SPIN_DEGREES_PER_TICK)));
        registerVisual("ataka_atgm", context -> adapt(WireGuideMissileEntity.class,
                new BvpSpinningProjectileRenderer<>(context,
                        bvp("custom_geo/mi24v_atgm_projectile.geo.json"),
                        bvp("textures/entity/mi24v_atgm.png"), 330.0F)));
        registerVisual("bastion_atgm", context -> adapt(WireGuideMissileEntity.class,
                new BvpSpinningProjectileRenderer<>(context,
                        bvp("custom_geo/bastion_atgm_projectile.geo.json"),
                        bvp("textures/entity/mi24v_atgm.png"), 330.0F)));
    }

    private static void registerCannonVisual(String path, String assetName) {
        registerVisual(path, context -> adapt(CannonShellEntity.class,
                new BvpSpinningProjectileRenderer<>(context,
                        bvp("custom_geo/tank_shell_" + assetName + "_projectile.geo.json"),
                        bvp("textures/entity/tank_shell_" + assetName + ".png"), 0.0F, -90.0F)));
    }

    private static void registerTracerVisual(String path) {
        registerVisual(path, context -> (entity, yaw, partialTick, poseStack, buffer, packedLight) ->
                BvpTracerProfile.enabled(entity));
    }

    private static void registerVisual(String path,
                                       com.atsuishio.superbwarfare.client.renderer.projectile.ProjectileVisualProviderFactory factory) {
        ProjectileVisualProviders.register(bvp(path), factory);
    }

    private static ResourceLocation bvp(String path) {
        return new ResourceLocation(BertsVehiclePack.MODID, path);
    }

    private static VehicleRenderBackendProvider standardTrackedVehicle(
            String modelPath, String texturePath, int roadWheelCount,
            float trackYCenter, float trackRadius, float trackZRear, float trackZFront,
            String debugName) {
        return context -> new StandardTrackedVehicleBackend(
                context, bvp(modelPath), bvp(texturePath), roadWheelCount,
                trackYCenter, trackRadius, trackZRear, trackZFront, debugName);
    }

    private static VehicleRenderBackendProvider standardVehicle(
            String modelPath, String texturePath, String debugName) {
        return context -> new StandardVehicleBackend(
                context, bvp(modelPath), bvp(texturePath), debugName);
    }

    private static VehicleRenderBackendProvider profileTrackedVehicle(
            String modelPath, String texturePath, String debugName) {
        return context -> new ProfileTrackedVehicleBackend(
                context, bvp(modelPath), bvp(texturePath), debugName);
    }

    private static VehicleRenderBackendProvider standardHelicopter(
            String modelPath, String texturePath, String debugName) {
        return context -> new StandardHelicopterBackend(
                context, bvp(modelPath), bvp(texturePath), debugName);
    }

    private static VehicleRenderBackendProvider standardTrackedVehicle(
            String modelPath, String texturePath, int roadWheelCount,
            float trackYCenter, float trackRadius, float trackZRear, float trackZFront,
            int trackLinkCount, String debugName) {
        return context -> new StandardTrackedVehicleBackend(
                context, bvp(modelPath), bvp(texturePath), roadWheelCount,
                trackYCenter, trackRadius, trackZRear, trackZFront, trackLinkCount, debugName);
    }

    private static VehicleRenderBackendProvider standardTank(
            String modelPath, String texturePath, int roadWheelCount,
            float trackYCenter, float trackRadius, float trackZRear, float trackZFront,
            String debugName) {
        return context -> new StandardTankBackend(context, bvp(modelPath), bvp(texturePath), roadWheelCount,
                trackYCenter, trackRadius, trackZRear, trackZFront, debugName);
    }

    private static VehicleRenderBackendProvider standardTank(
            String modelPath, String texturePath, int roadWheelCount,
            float trackYCenter, float trackRadius, float trackZRear, float trackZFront,
            int trackLinkCount, String debugName) {
        return context -> new StandardTankBackend(context, bvp(modelPath), bvp(texturePath), roadWheelCount,
                trackYCenter, trackRadius, trackZRear, trackZFront, trackLinkCount, debugName);
    }

    private static <T extends Entity> ProjectileVisualProvider adapt(
            Class<T> entityClass, BvpSpinningProjectileRenderer<T> renderer) {
        return (entity, yaw, partialTick, poseStack, buffer, packedLight) -> {
            if (!entityClass.isInstance(entity)) {
                return false;
            }
            return renderer.renderProfile(entityClass.cast(entity), yaw, partialTick, poseStack, buffer, packedLight);
        };
    }

    private static final class StandardTrackedVehicleBackend extends BaseTrackedVehicleRenderer<GeoVehicleEntity> {
        private StandardTrackedVehicleBackend(EntityRendererProvider.Context context,
                                              ResourceLocation modelLocation,
                                              ResourceLocation textureLocation, int roadWheelCount,
                                              float trackYCenter, float trackRadius, float trackZRear,
                                              float trackZFront, String debugName) {
            super(context, modelLocation, textureLocation, roadWheelCount, trackYCenter,
                    trackRadius, trackZRear, trackZFront, debugName);
        }

        private StandardTrackedVehicleBackend(EntityRendererProvider.Context context,
                                              ResourceLocation modelLocation,
                                              ResourceLocation textureLocation, int roadWheelCount,
                                              float trackYCenter, float trackRadius, float trackZRear,
                                              float trackZFront, int trackLinkCount, String debugName) {
            super(context, modelLocation, textureLocation, roadWheelCount, trackYCenter,
                    trackRadius, trackZRear, trackZFront, trackLinkCount, debugName);
        }
    }

    private static final class StandardVehicleBackend extends BaseVehicleRenderer<GeoVehicleEntity> {
        private StandardVehicleBackend(EntityRendererProvider.Context context,
                                       ResourceLocation modelLocation,
                                       ResourceLocation textureLocation,
                                       String debugName) {
            super(context, modelLocation, textureLocation, debugName);
        }
    }

    private static final class ProfileTrackedVehicleBackend extends BaseTrackedVehicleRenderer<GeoVehicleEntity> {
        private ProfileTrackedVehicleBackend(EntityRendererProvider.Context context,
                                              ResourceLocation modelLocation,
                                              ResourceLocation textureLocation,
                                              String debugName) {
            super(context, modelLocation, textureLocation, debugName);
        }
    }

    private static final class StandardHelicopterBackend extends BaseVehicleRenderer<GeoVehicleEntity> {
        private StandardHelicopterBackend(EntityRendererProvider.Context context,
                                          ResourceLocation modelLocation,
                                          ResourceLocation textureLocation,
                                          String debugName) {
            super(context, modelLocation, textureLocation, debugName);
        }

        @Override
        protected void applyModelAnimations(GeoVehicleEntity entity, float entityYaw,
                                            PolyMeshModel loadedModel, float partialTicks) {
            super.applyModelAnimations(entity, entityYaw, loadedModel, partialTicks);
            if (entity instanceof BvpHelicopterEntity helicopter) {
                RotorVisualController.apply(helicopter, loadedModel, partialTicks);
            }
        }
    }

    private static final class StandardTankBackend extends BaseTankRenderer<GeoVehicleEntity> {
        private StandardTankBackend(EntityRendererProvider.Context context, ResourceLocation modelLocation,
                                    ResourceLocation textureLocation, int roadWheelCount, float trackYCenter,
                                    float trackRadius, float trackZRear, float trackZFront, String debugName) {
            super(context, modelLocation, textureLocation, roadWheelCount, trackYCenter,
                    trackRadius, trackZRear, trackZFront, debugName);
        }

        private StandardTankBackend(EntityRendererProvider.Context context, ResourceLocation modelLocation,
                                    ResourceLocation textureLocation, int roadWheelCount, float trackYCenter,
                                    float trackRadius, float trackZRear, float trackZFront, int trackLinkCount,
                                    String debugName) {
            super(context, modelLocation, textureLocation, roadWheelCount, trackYCenter,
                    trackRadius, trackZRear, trackZFront, trackLinkCount, debugName);
        }
    }

    private ModEntityRenderers() {
    }
}
