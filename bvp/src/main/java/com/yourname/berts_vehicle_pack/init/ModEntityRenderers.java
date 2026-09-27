package com.yourname.berts_vehicle_pack.init;

import com.yourname.berts_vehicle_pack.client.renderer.FittedGroundVehicleRenderer;

import com.atsuishio.superbwarfare.client.renderer.projectile.ProjectileVisualProvider;
import com.atsuishio.superbwarfare.client.renderer.projectile.ProjectileVisualProviders;
import com.atsuishio.superbwarfare.client.renderer.entity.ResourceVehicleRenderer;
import com.atsuishio.superbwarfare.client.renderer.vehicle.VehicleRenderBackendProvider;
import com.atsuishio.superbwarfare.client.renderer.vehicle.VehicleRenderBackends;
import com.atsuishio.superbwarfare.entity.projectile.CannonShellEntity;
import com.atsuishio.superbwarfare.entity.projectile.AerialBombEntity;
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
        registerVehicle(event, ModEntities.T62A, "t_62a", profileTrackedVehicle(
                "custom_geo/t_62a.geo.json", "textures/entity/t_62a.png", "T62ARenderer"));
        registerVehicle(event, ModEntities.BTR60PB, "btr_60pb", standardVehicle(
                "custom_geo/btr_60pb.geo.json", "textures/entity/btr_60pb.png", "Btr60pbRenderer"));
        registerVehicle(event, ModEntities.VBCI, "vbci", standardVehicle(
                "custom_geo/vbci.geo.json", "textures/entity/vbci.png", "VbciRenderer"));
        registerVehicle(event, ModEntities.LAV25, "lav25", standardVehicle(
                "custom_geo/lav25.geo.json", "textures/entity/lav25.png", "Lav25Renderer"));
        registerVehicle(event, ModEntities.M1128, "m1128", standardVehicle(
                "custom_geo/m1128.geo.json", "textures/entity/m1128.png", "M1128Renderer"));
        registerVehicle(event, ModEntities.LEO2A6, "leo2a6", standardTank(
                "custom_geo/leo2a6.geo.json", "textures/entity/leo2a6.png",
                7, 14.1688F, 13.47805F, -84.9836F, 76.91575F, 38, "Leo2A6Renderer"));
        registerVehicle(event, ModEntities.TOYOTA_JIHAD_DSHK, "toyota_jihad_dshk", standardVehicle(
                "custom_geo/toyota_jihad_dshk.geo.json", "textures/entity/toyota_jihad_dshk.png",
                "ToyotaJihadDshkRenderer"));
        registerVehicle(event, ModEntities.TOYOTA_JIHAD_SPG9, "toyota_jihad_spg9", standardVehicle(
                "custom_geo/toyota_jihad_spg9.geo.json", "textures/entity/toyota_jihad_spg9.png",
                "ToyotaJihadSpg9Renderer"));
        registerVehicle(event, ModEntities.UAZ_469_SPG9, "uaz_469_spg9", standardVehicle(
                "custom_geo/uaz_469_spg9.geo.json", "textures/entity/uaz_469_spg9.png",
                "Uaz469Spg9Renderer"));
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
        registerVehicle(event, ModEntities.AGS_30, "ags_30", standardVehicle(
                "custom_geo/ags_30.geo.json", "textures/entity/ags_30.png", "Ags30Renderer"));
        registerVehicle(event, ModEntities.SPG9_TRIPOD, "spg9_tripod", standardVehicle(
                "custom_geo/spg9_tripod.geo.json", "textures/entity/spg9_tripod.png", "Spg9TripodRenderer"));
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
        registerVehicle(event, ModEntities.YAK_3, "yak_3", standardVehicle(
                "custom_geo/yak_3.geo.json", "textures/entity/yak_3.png",
                "yak_3"));
        registerVehicle(event, ModEntities.YAK_9U, "yak_9u", standardVehicle(
                "custom_geo/yak_9u.geo.json", "textures/entity/yak_9u.png",
                "yak_9u"));
        registerVehicle(event, ModEntities.J_26, "j_26", standardVehicle(
                "custom_geo/j_26.geo.json", "textures/entity/j_26.png",
                "j_26"));
        registerVehicle(event, ModEntities.SUPERMARINE_SPITFIRE_GRIFFON, "supermarine_spitfire_griffon", standardVehicle(
                "custom_geo/supermarine_spitfire_griffon.geo.json", "textures/entity/supermarine_spitfire_griffon.png",
                "supermarine_spitfire_griffon"));
        registerVehicle(event, ModEntities.MIG_9, "mig_9", standardVehicle(
                "custom_geo/mig_9.geo.json", "textures/entity/mig_9.png",
                "mig_9"));
        registerVehicle(event, ModEntities.JU_87_B2, "ju_87_b2", standardVehicle(
                "custom_geo/ju_87_b2.geo.json", "textures/entity/ju_87_b2.png", "ju_87_b2"));
        registerVehicle(event, ModEntities.HO_229, "ho_229", standardVehicle(
                "custom_geo/ho_229.geo.json", "textures/entity/ho_229.png", "ho_229"));
        registerVehicle(event, ModEntities.MIG_15BIS, "mig_15bis", standardVehicle(
                "custom_geo/mig_15bis.geo.json", "textures/entity/mig_15bis.png",
                "mig_15bis"));
        registerVehicle(event, ModEntities.MIG_21BIS, "mig_21bis", standardVehicle(
                "custom_geo/mig_21bis.geo.json", "textures/entity/mig_21bis.png",
                "mig_21bis"));
        registerVehicle(event, ModEntities.Q_5, "q_5", standardVehicle(
                "custom_geo/q_5.geo.json", "textures/entity/q_5.png",
                "q_5"));
        registerVehicle(event, ModEntities.SU_25, "su_25", standardVehicle(
                "custom_geo/su_25.geo.json", "textures/entity/su_25.png",
                "su_25"));
        registerVehicle(event, ModEntities.SU_27, "su_27", standardVehicle(
                "custom_geo/su_27.geo.json", "textures/entity/su_27.png",
                "su_27"));
        registerVehicle(event, ModEntities.SU_30, "su_30", standardVehicle(
                "custom_geo/su_30.geo.json", "textures/entity/su_30.png",
                "su_30"));
        registerVehicle(event, ModEntities.J_15D, "j_15d", standardVehicle(
                "custom_geo/j_15d.geo.json", "textures/entity/j_15d.png",
                "j_15d"));
        registerVehicle(event, ModEntities.MIG_29, "mig_29", standardVehicle(
                "custom_geo/mig_29.geo.json", "textures/entity/mig_29.png",
                "mig_29"));
        registerVehicle(event, ModEntities.MIG_19S, "mig_19s", standardVehicle(
                "custom_geo/mig_19s.geo.json", "textures/entity/mig_19s.png",
                "mig_19s"));
        registerVehicle(event, ModEntities.SAAB_J_21A_1, "saab_j_21a_1", standardVehicle(
                "custom_geo/saab_j_21a_1.geo.json", "textures/entity/saab_j_21a_1.png",
                "saab_j_21a_1"));
        registerVehicle(event, ModEntities.F_84F, "f_84f", standardVehicle(
                "custom_geo/f_84f.geo.json", "textures/entity/f_84f.png",
                "f_84f"));
        registerVehicle(event, ModEntities.F_86K, "f_86k", standardVehicle(
                "custom_geo/f_86k.geo.json", "textures/entity/f_86k.png",
                "f_86k"));
        registerVehicle(event, ModEntities.F_104G, "f_104g", standardVehicle(
                "custom_geo/f_104g.geo.json", "textures/entity/f_104g.png",
                "f_104g"));
        registerVehicle(event, ModEntities.FIAT_G_91, "fiat_g_91", standardVehicle(
                "custom_geo/fiat_g_91.geo.json", "textures/entity/fiat_g_91.png",
                "fiat_g_91"));
        registerVehicle(event, ModEntities.YAK_15P, "yak_15p", standardVehicle(
                "custom_geo/yak_15p.geo.json", "textures/entity/yak_15p.png",
                "yak_15p"));
        registerVehicle(event, ModEntities.SU_9, "su_9", standardVehicle(
                "custom_geo/su_9.geo.json", "textures/entity/su_9.png",
                "su_9"));
        registerVehicle(event, ModEntities.SAAB_29_TUNNAN, "saab_29_tunnan", standardVehicle(
                "custom_geo/saab_29_tunnan.geo.json", "textures/entity/saab_29_tunnan.png",
                "saab_29_tunnan"));
        registerVehicle(event, ModEntities.SAAB_32_LANSEN, "saab_32_lansen", standardVehicle(
                "custom_geo/saab_32_lansen.geo.json", "textures/entity/saab_32_lansen.png",
                "saab_32_lansen"));
        registerVehicle(event, ModEntities.MIG_23MLD, "mig_23mld", standardVehicle(
                "custom_geo/mig_23mld.geo.json", "textures/entity/mig_23mld.png",
                "mig_23mld"));
        registerVehicle(event, ModEntities.SU_17, "su_17", standardVehicle(
                "custom_geo/su_17.geo.json", "textures/entity/su_17.png",
                "su_17"));
        registerVehicle(event, ModEntities.SAAB_35_DRAKEN, "saab_35_draken", standardVehicle(
                "custom_geo/saab_35_draken.geo.json", "textures/entity/saab_35_draken.png",
                "saab_35_draken"));
        registerVehicle(event, ModEntities.SAAB_37_VIGGEN, "saab_37_viggen", standardVehicle(
                "custom_geo/saab_37_viggen.geo.json", "textures/entity/saab_37_viggen.png",
                "saab_37_viggen"));
        registerVehicle(event, ModEntities.SAAB_JAS_39_GRIPEN, "saab_jas_39_gripen", standardVehicle(
                "custom_geo/saab_jas_39_gripen.geo.json", "textures/entity/saab_jas_39_gripen.png",
                "saab_jas_39_gripen"));
        registerVehicle(event, ModEntities.PANAVIA_TORNADO_IDS_MARINEFLIEGER, "panavia_tornado_ids_marineflieger", standardVehicle(
                "custom_geo/panavia_tornado_ids_marineflieger.geo.json", "textures/entity/panavia_tornado_ids_marineflieger.png",
                "panavia_tornado_ids_marineflieger"));
        registerVehicle(event, ModEntities.EUROFIGHTER_TYPHOON, "eurofighter_typhoon", standardVehicle(
                "custom_geo/eurofighter_typhoon.geo.json", "textures/entity/eurofighter_typhoon.png",
                "eurofighter_typhoon"));
        registerVehicle(event, ModEntities.J_10A, "j_10a", standardVehicle(
                "custom_geo/j_10a.geo.json", "textures/entity/j_10a.png",
                "j_10a"));
        registerVehicle(event, ModEntities.AN_12B, "an_12b", standardVehicle(
                "custom_geo/an_12b.geo.json", "textures/entity/an_12b.png",
                "an_12b"));
        registerVehicle(event, ModEntities.C_130H, "c_130h", standardVehicle(
                "custom_geo/c_130h.geo.json", "textures/entity/c_130h.png",
                "c_130h"));
        registerVehicle(event, ModEntities.IL_76M, "il_76m", standardVehicle(
                "custom_geo/il_76m.geo.json", "textures/entity/il_76m.png",
                "il_76m"));
        registerVehicle(event, ModEntities.M_50A, "m_50a", standardVehicle(
                "custom_geo/m_50a.geo.json", "textures/entity/m_50a.png",
                "m_50a"));
        registerVehicle(event, ModEntities.TU_22M, "tu_22m", standardVehicle(
                "custom_geo/tu_22m.geo.json", "textures/entity/tu_22m.png",
                "tu_22m"));
        registerVehicle(event, ModEntities.TU_95MS, "tu_95ms", standardVehicle(
                "custom_geo/tu_95ms.geo.json", "textures/entity/tu_95ms.png",
                "tu_95ms"));
        registerVehicle(event, ModEntities.F_111F, "f_111f", standardVehicle(
                "custom_geo/f_111f.geo.json", "textures/entity/f_111f.png",
                "f_111f"));
        registerVehicle(event, ModEntities.SU_24, "su_24", standardVehicle(
                "custom_geo/su_24.geo.json", "textures/entity/su_24.png",
                "su_24"));
        registerVehicle(event, ModEntities.F_16C, "f_16c", standardVehicle(
                "custom_geo/f_16c.geo.json", "textures/entity/f_16c.png",
                "f_16c"));
        registerVehicle(event, ModEntities.F_16B, "f_16b", standardVehicle(
                "custom_geo/f_16b.geo.json", "textures/entity/f_16b.png",
                "f_16b"));
        registerVehicle(event, ModEntities.F_15C, "f_15c", standardVehicle(
                "custom_geo/f_15c.geo.json", "textures/entity/f_15c.png",
                "f_15c"));
        registerVehicle(event, ModEntities.F_15E, "f_15e", standardVehicle(
                "custom_geo/f_15e.geo.json", "textures/entity/f_15e.png",
                "f_15e"));
        registerVehicle(event, ModEntities.F9F_2, "f9f_2", standardVehicle(
                "custom_geo/f9f_2.geo.json", "textures/entity/f9f_2.png",
                "f9f_2"));
        registerVehicle(event, ModEntities.F_5A, "f_5a", standardVehicle(
                "custom_geo/f_5a.geo.json", "textures/entity/f_5a.png",
                "f_5a"));
        registerVehicle(event, ModEntities.F_4C, "f_4c", standardVehicle(
                "custom_geo/f_4c.geo.json", "textures/entity/f_4c.png",
                "f_4c"));
        registerVehicle(event, ModEntities.B_47E, "b_47e", standardVehicle(
                "custom_geo/b_47e.geo.json", "textures/entity/b_47e.png",
                "b_47e"));
        registerVehicle(event, ModEntities.A_7D, "a_7d", standardVehicle(
                "custom_geo/a_7d.geo.json", "textures/entity/a_7d.png",
                "a_7d"));
        registerVehicle(event, ModEntities.F_100C, "f_100c", standardVehicle(
                "custom_geo/f_100c.geo.json", "textures/entity/f_100c.png",
                "f_100c"));
        registerVehicle(event, ModEntities.SU_57, "su_57", standardVehicle(
                "custom_geo/su_57.geo.json", "textures/entity/su_57.png",
                "su_57"));
        registerVehicle(event, ModEntities.METEOR_F_8, "meteor_f_8", standardVehicle(
                "custom_geo/meteor_f_8.geo.json", "textures/entity/meteor_f_8.png", "meteor_f_8"));
        registerVehicle(event, ModEntities.SABRE_MK_6, "sabre_mk_6", standardVehicle(
                "custom_geo/sabre_mk_6.geo.json", "textures/entity/sabre_mk_6.png", "sabre_mk_6"));
        registerVehicle(event, ModEntities.MD_450_OURAGAN, "md_450_ouragan", standardVehicle(
                "custom_geo/md_450_ouragan.geo.json", "textures/entity/md_450_ouragan.png", "md_450_ouragan"));
        registerVehicle(event, ModEntities.MIRAGE_5, "mirage_5", standardVehicle(
                "custom_geo/mirage_5.geo.json", "textures/entity/mirage_5.png", "mirage_5"));
        registerVehicle(event, ModEntities.RAFALE, "rafale", standardVehicle(
                "custom_geo/rafale.geo.json", "textures/entity/rafale.png", "rafale"));
        registerVehicle(event, ModEntities.SUPER_MYSTERE, "super_mystere", standardVehicle(
                "custom_geo/super_mystere.geo.json", "textures/entity/super_mystere.png", "super_mystere"));
        registerVehicle(event, ModEntities.J_11A, "j_11a", standardVehicle(
                "custom_geo/j_11a.geo.json", "textures/entity/j_11a.png", "j_11a"));
        registerVehicle(event, ModEntities.J_2, "j_2", standardVehicle(
                "custom_geo/j_2.geo.json", "textures/entity/j_2.png", "j_2"));
        registerVehicle(event, ModEntities.J_5, "j_5", standardVehicle(
                "custom_geo/j_5.geo.json", "textures/entity/j_5.png", "j_5"));
        registerVehicle(event, ModEntities.IL_10, "il_10", standardVehicle(
                "custom_geo/il_10.geo.json", "textures/entity/il_10.png", "il_10"));
        registerVehicle(event, ModEntities.P_51D, "p_51d", standardVehicle(
                "custom_geo/p_51d.geo.json", "textures/entity/p_51d.png", "p_51d"));
        registerVehicle(event, ModEntities.F_8H, "f_8h", standardVehicle(
                "custom_geo/f_8h.geo.json", "textures/entity/f_8h.png", "f_8h"));
        registerVehicle(event, ModEntities.F8F_1, "f8f_1", standardVehicle(
                "custom_geo/f8f_1.geo.json", "textures/entity/f8f_1.png", "f8f_1"));
        registerVehicle(event, ModEntities.FA_18E, "fa_18e", standardVehicle(
                "custom_geo/fa_18e.geo.json", "textures/entity/fa_18e.png", "fa_18e"));
        registerVehicle(event, ModEntities.A_10, "a_10", standardVehicle(
                "custom_geo/a_10.geo.json", "textures/entity/a_10.png", "a_10"));
        registerVehicle(event, ModEntities.F_14A, "f_14a", standardVehicle(
                "custom_geo/f_14a.geo.json", "textures/entity/f_14a.png", "f_14a"));
        registerVehicle(event, ModEntities.F_14D, "f_14d", standardVehicle(
                "custom_geo/f_14d.geo.json", "textures/entity/f_14d.png", "f_14d"));
        registerVehicle(event, ModEntities.CH_46E, "ch_46e", standardVehicle(
                "custom_geo/ch_46e.geo.json", "textures/entity/ch_46e.png", "ch_46e"));
        registerVehicle(event, ModEntities.EUROCOPTER_TIGER, "eurocopter_tiger", standardVehicle(
                "custom_geo/eurocopter_tiger.geo.json", "textures/entity/eurocopter_tiger.png", "eurocopter_tiger"));
        registerVehicle(event, ModEntities.AH_64D, "ah_64d", standardVehicle(
                "custom_geo/ah_64d.geo.json", "textures/entity/ah_64d.png", "ah_64d"));
        registerVehicle(event, ModEntities.F3H, "f3h", standardVehicle(
                "custom_geo/f3h.geo.json", "textures/entity/f3h.png", "f3h"));
        registerVehicle(event, ModEntities.F2H_2, "f2h_2", standardVehicle(
                "custom_geo/f2h_2.geo.json", "textures/entity/f2h_2.png", "f2h_2"));
        registerVehicle(event, ModEntities.SU_35, "su_35", standardVehicle(
                "custom_geo/su_35.geo.json", "textures/entity/su_35.png", "su_35"));
        registerVehicle(event, ModEntities.SU_39, "su_39", standardVehicle(
                "custom_geo/su_39.geo.json", "textures/entity/su_39.png", "su_39"));
        registerVehicle(event, ModEntities.MIRAGE_F1, "mirage_f1", standardVehicle(
                "custom_geo/mirage_f1.geo.json", "textures/entity/mirage_f1.png", "mirage_f1"));
        registerVehicle(event, ModEntities.B_1B, "b_1b", standardVehicle(
                "custom_geo/b_1b.geo.json", "textures/entity/b_1b.png", "b_1b"));
        registerVehicle(event, ModEntities.MI_24A, "mi_24a", standardVehicle(
                "custom_geo/mi_24a.geo.json", "textures/entity/mi_24a.png", "mi_24a"));
        registerVehicle(event, ModEntities.MI_24D, "mi_24d", standardVehicle(
                "custom_geo/mi_24d.geo.json", "textures/entity/mi_24d.png", "mi_24d"));
        registerVehicle(event, ModEntities.MI_26, "mi_26", standardVehicle(
                "custom_geo/mi_26.geo.json", "textures/entity/mi_26.png", "mi_26"));
        registerVehicle(event, ModEntities.AH_1F, "ah_1f", standardVehicle(
                "custom_geo/ah_1f.geo.json", "textures/entity/ah_1f.png", "ah_1f"));
        registerVehicle(event, ModEntities.CHALLENGER_2, "challenger_2", standardVehicle(
                "custom_geo/challenger_2.geo.json", "textures/entity/challenger_2.png", "challenger_2"));
        registerVehicle(event, ModEntities.LECLERC_S1, "leclerc_s1", standardVehicle(
                "custom_geo/leclerc_s1.geo.json", "textures/entity/leclerc_s1.png", "leclerc_s1"));
        registerVehicle(event, ModEntities.NINE_P_148, "9p148", context ->
                new FittedGroundVehicleRenderer(context, bvp("custom_geo/9p148.geo.json"),
                        bvp("textures/entity/9p148.png"), "9p148"));
        registerVehicle(event, ModEntities.GEPARD, "gepard", context ->
                new FittedGroundVehicleRenderer(context, bvp("custom_geo/gepard.geo.json"),
                        bvp("textures/entity/gepard.png"), "gepard"));
        registerVehicle(event, ModEntities.ZSL_92, "zsl_92", context ->
                new FittedGroundVehicleRenderer(context, bvp("custom_geo/zsl_92.geo.json"),
                        bvp("textures/entity/zsl_92.png"), "zsl_92"));
        registerVehicle(event, ModEntities.GAZ_3937_VODNIK_AA, "gaz_3937_vodnik_aa", context ->
                new FittedGroundVehicleRenderer(context, bvp("custom_geo/gaz_3937_vodnik_aa.geo.json"),
                        bvp("textures/entity/gaz_3937_vodnik_aa.png"), "gaz_3937_vodnik_aa"));
        registerVehicle(event, ModEntities.BMD_1, "bmd_1", context ->
                new FittedGroundVehicleRenderer(context, bvp("custom_geo/bmd_1.geo.json"),
                        bvp("textures/entity/bmd_1.png"), "bmd_1"));
        registerVehicle(event, ModEntities.BTR_ZD, "btr_zd", context ->
                new FittedGroundVehicleRenderer(context, bvp("custom_geo/btr_zd.geo.json"),
                        bvp("textures/entity/btr_zd.png"), "btr_zd"));
        registerVehicle(event, ModEntities.BTR_90, "btr_90", context ->
                new FittedGroundVehicleRenderer(context, bvp("custom_geo/btr_90.geo.json"),
                        bvp("textures/entity/btr_90.png"), "btr_90"));
        registerVehicle(event, ModEntities.LEOPARD_2A4, "leopard_2a4", context ->
                new FittedGroundVehicleRenderer(context, bvp("custom_geo/leopard_2a4.geo.json"),
                        bvp("textures/entity/leopard_2a4.png"), "leopard_2a4"));
        registerVehicle(event, ModEntities.NINE_P_149_SHTURM, "9p149_shturm", context ->
                new FittedGroundVehicleRenderer(context, bvp("custom_geo/9p149_shturm.geo.json"),
                        bvp("textures/entity/9p149_shturm.png"), "9p149_shturm"));
        registerVehicle(event, ModEntities.NINE_K_22_TUNGUSKA, "9k22_tunguska", context ->
                new FittedGroundVehicleRenderer(context, bvp("custom_geo/9k22_tunguska.geo.json"),
                        bvp("textures/entity/9k22_tunguska.png"), "9k22_tunguska"));
        registerVehicle(event, ModEntities.TYPE_90, "type_90", context ->
                new FittedGroundVehicleRenderer(context, bvp("custom_geo/type_90.geo.json"),
                        bvp("textures/entity/type_90.png"), "type_90"));
        registerVehicle(event, ModEntities.M1A1_ABRAMS, "m1a1_abrams", context ->
                new FittedGroundVehicleRenderer(context, bvp("custom_geo/m1a1_abrams.geo.json"),
                        bvp("textures/entity/m1a1_abrams.png"), "m1a1_abrams"));
        registerVehicle(event, ModEntities.K2A1_BLACK_PANTHER, "k2a1_black_panther", context ->
                new FittedGroundVehicleRenderer(context, bvp("custom_geo/k2a1_black_panther.geo.json"),
                        bvp("textures/entity/k2a1_black_panther.png"), "k2a1_black_panther"));
        registerVehicle(event, ModEntities.M109A7_PALADIN, "m109a7_paladin", context ->
                new FittedGroundVehicleRenderer(context, bvp("custom_geo/m109a7_paladin.geo.json"),
                        bvp("textures/entity/m109a7_paladin.png"), "m109a7_paladin"));
        registerVehicle(event, ModEntities.MARDER_1A5, "marder_1a5", context ->
                new FittedGroundVehicleRenderer(context, bvp("custom_geo/marder_1a5.geo.json"),
                        bvp("textures/entity/marder_1a5.png"), "marder_1a5"));
        registerVehicle(event, ModEntities.ZBD_09, "zbd_09", context ->
                new FittedGroundVehicleRenderer(context, bvp("custom_geo/zbd_09.geo.json"),
                        bvp("textures/entity/zbd_09.png"), "zbd_09"));
        registerVehicle(event, ModEntities.ZTL_09, "ztl_09", context ->
                new FittedGroundVehicleRenderer(context, bvp("custom_geo/ztl_09.geo.json"),
                        bvp("textures/entity/ztl_09.png"), "ztl_09"));
        registerVehicle(event, ModEntities.PZH_2000, "pzh_2000", context ->
                new FittedGroundVehicleRenderer(context, bvp("custom_geo/pzh_2000.geo.json"),
                        bvp("textures/entity/pzh_2000.png"), "pzh_2000"));
        registerVehicle(event, ModEntities.QN_506MODEL, "qn_506model", context ->
                new FittedGroundVehicleRenderer(context, bvp("custom_geo/qn_506model.geo.json"),
                        bvp("textures/entity/qn_506model.png"), "qn_506model"));
        registerVehicle(event, ModEntities.VT_4A1, "vt_4a1", context ->
                new FittedGroundVehicleRenderer(context, bvp("custom_geo/vt_4a1.geo.json"),
                        bvp("textures/entity/vt_4a1.png"), "vt_4a1"));
        registerVehicle(event, ModEntities.TUNGUSKA, "tunguska", context ->
                new FittedGroundVehicleRenderer(context, bvp("custom_geo/tunguska.geo.json"),
                        bvp("textures/entity/tunguska.png"), "tunguska"));
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
        registerVisual("authored_missile", context -> adapt(WireGuideMissileEntity.class,
                new BvpSpinningProjectileRenderer<>(context, null, null, 0F)));
        registerVisual("authored_bomb", context -> adapt(AerialBombEntity.class,
                new BvpSpinningProjectileRenderer<>(context, null, null, 0F)));
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
                        bvp("custom_geo/mi24v_atgm_projectile.geo.json"),
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
