package com.yourname.berts_vehicle_pack.init;

import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.entity.Amx10rcEntity;
import com.yourname.berts_vehicle_pack.entity.Bm21GradEntity;
import com.yourname.berts_vehicle_pack.entity.Bmp2Entity;
import com.yourname.berts_vehicle_pack.entity.Bmp2MEntity;
import com.yourname.berts_vehicle_pack.entity.Bmp1amEntity;
import com.yourname.berts_vehicle_pack.entity.Bmp3mEliteEntity;
import com.yourname.berts_vehicle_pack.entity.BmptEntity;
import com.yourname.berts_vehicle_pack.entity.BrowningTripodEntity;
import com.yourname.berts_vehicle_pack.entity.Btr152Entity;
import com.yourname.berts_vehicle_pack.entity.Btr60pbEntity;
import com.yourname.berts_vehicle_pack.entity.Btr80AEntity;
import com.yourname.berts_vehicle_pack.entity.Cv9040NoNetEntity;
import com.yourname.berts_vehicle_pack.entity.Ka50Entity;
import com.yourname.berts_vehicle_pack.entity.Kamaz4310Entity;
import com.yourname.berts_vehicle_pack.entity.KordTripodEntity;
import com.yourname.berts_vehicle_pack.entity.Spg9TripodEntity;
import com.yourname.berts_vehicle_pack.entity.Leo2A6Entity;
import com.yourname.berts_vehicle_pack.entity.Lav25Entity;
import com.yourname.berts_vehicle_pack.entity.M1128Entity;
import com.yourname.berts_vehicle_pack.entity.M41Entity;
import com.yourname.berts_vehicle_pack.entity.M1AbramsEliteEntity;
import com.yourname.berts_vehicle_pack.entity.M1A2AbramsSepV2Entity;
import com.yourname.berts_vehicle_pack.entity.M2BradleyEntity;
import com.yourname.berts_vehicle_pack.entity.M48A3EliteEntity;
import com.yourname.berts_vehicle_pack.entity.M551A1Entity;
import com.yourname.berts_vehicle_pack.entity.M60A1Entity;
import com.yourname.berts_vehicle_pack.entity.Marder1A1Entity;
import com.yourname.berts_vehicle_pack.entity.Marder1A2Entity;
import com.yourname.berts_vehicle_pack.entity.Mi24VEntity;
import com.yourname.berts_vehicle_pack.entity.Mi28NEntity;
import com.yourname.berts_vehicle_pack.entity.Mig19Entity;
import com.yourname.berts_vehicle_pack.entity.MilanTripodEntity;
import com.yourname.berts_vehicle_pack.entity.PantherGEntity;
import com.yourname.berts_vehicle_pack.entity.S2s25SprutSdEntity;
import com.yourname.berts_vehicle_pack.entity.StugIIIEntity;
import com.yourname.berts_vehicle_pack.entity.T55AEntity;
import com.yourname.berts_vehicle_pack.entity.T62AEntity;
import com.yourname.berts_vehicle_pack.entity.T64BObr1976Entity;
import com.yourname.berts_vehicle_pack.entity.T72AEntity;
import com.yourname.berts_vehicle_pack.entity.T72BEntity;
import com.yourname.berts_vehicle_pack.entity.T72B3Entity;
import com.yourname.berts_vehicle_pack.entity.T72B3UbhCopeEntity;
import com.yourname.berts_vehicle_pack.entity.T80BEntity;
import com.yourname.berts_vehicle_pack.entity.T80UObr1985Entity;
import com.yourname.berts_vehicle_pack.entity.T90AEntity;
import com.yourname.berts_vehicle_pack.entity.T90MEntity;
import com.yourname.berts_vehicle_pack.entity.Tiger1Entity;
import com.yourname.berts_vehicle_pack.entity.TigerIIEntity;
import com.yourname.berts_vehicle_pack.entity.ToyotaJihadBmp1Entity;
import com.yourname.berts_vehicle_pack.entity.ToyotaJihadDshkEntity;
import com.yourname.berts_vehicle_pack.entity.ToyotaJihadSpg9Entity;
import com.yourname.berts_vehicle_pack.entity.ToyotaJihadS5Entity;
import com.yourname.berts_vehicle_pack.entity.TowTripodEntity;
import com.yourname.berts_vehicle_pack.entity.VbciEntity;
import com.yourname.berts_vehicle_pack.entity.Zu23_2Entity;
import com.yourname.berts_vehicle_pack.entity.Zsu23_4Entity;
import com.yourname.berts_vehicle_pack.entity.Ztz99AEntity;
import com.yourname.berts_vehicle_pack.entity.FittedGroundVehicleEntity;
import com.yourname.berts_vehicle_pack.entity.aircraft.AuthoredFixedWingAircraft;
import com.yourname.berts_vehicle_pack.entity.helicopter.AuthoredHelicopter;
import com.yourname.berts_vehicle_pack.entity.helicopter.Ah1gCobraEntity;
import com.yourname.berts_vehicle_pack.entity.helicopter.Ah6jEntity;
import com.yourname.berts_vehicle_pack.entity.projectile.BvpAtakaMissileEntity;
import com.yourname.berts_vehicle_pack.entity.projectile.BvpS13RocketEntity;
import com.yourname.berts_vehicle_pack.entity.projectile.BvpS8KoRocketEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEntities {
    private static final int FAST_PROJECTILE_TRACKING_RANGE = 64;
    private static final int FAST_PROJECTILE_UPDATE_INTERVAL = 1;

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, BertsVehiclePack.MODID);

    public static final RegistryObject<EntityType<BvpS8KoRocketEntity>> S8KO_ROCKET =
            ENTITIES.register("s8ko_rocket", () -> fastProjectile(BvpS8KoRocketEntity::new)
                    .m_20699_(0.45f, 0.45f)
                    .m_20712_("s8ko_rocket"));

    public static final RegistryObject<EntityType<BvpS13RocketEntity>> S13_ROCKET =
            ENTITIES.register("s13_rocket", () -> fastProjectile(BvpS13RocketEntity::new)
                    .m_20699_(0.58f, 0.58f)
                    .m_20712_("s13_rocket"));

    public static final RegistryObject<EntityType<BvpAtakaMissileEntity>> ATAKA_MISSILE =
            ENTITIES.register("ataka_missile", () -> fastProjectile(BvpAtakaMissileEntity::new)
                    .m_20719_()
                    .m_20699_(0.5f, 0.5f)
                    .m_20712_("ataka_missile"));

    public static final RegistryObject<EntityType<T72BEntity>> T72B =
            ENTITIES.register("t72b", () -> vehicle(T72BEntity::new, 3.7f, 2.2f, "t72b"));
    public static final RegistryObject<EntityType<T80BEntity>> T80B_OBR1976 =
            ENTITIES.register("t80b_obr1976", () -> vehicle(T80BEntity::new, 3.7f, 2.2f, "t80b_obr1976"));
    public static final RegistryObject<EntityType<T72AEntity>> T72A =
            ENTITIES.register("t72a", () -> vehicle(T72AEntity::new, 3.7f, 2.2f, "t72a"));
    public static final RegistryObject<EntityType<T90AEntity>> T90A =
            ENTITIES.register("t90a", () -> vehicle(T90AEntity::new, 3.0f, 1.9f, "t90a"));
    public static final RegistryObject<EntityType<T90MEntity>> T90M =
            ENTITIES.register("t90m", () -> vehicle(T90MEntity::new, 3.0f, 1.9f, "t90m"));
    public static final RegistryObject<EntityType<BmptEntity>> BMPT =
            ENTITIES.register("bmpt", () -> vehicle(BmptEntity::new, 3.0f, 1.9f, "bmpt"));
    public static final RegistryObject<EntityType<Zsu23_4Entity>> ZSU23_4 =
            ENTITIES.register("zsu23_4", () -> vehicle(Zsu23_4Entity::new, 3.3f, 2.4f, "zsu23_4"));
    public static final RegistryObject<EntityType<ToyotaJihadDshkEntity>> TOYOTA_JIHAD_DSHK =
            ENTITIES.register("toyota_jihad_dshk", () -> vehicle(ToyotaJihadDshkEntity::new, 2.4f, 1.8f, "toyota_jihad_dshk"));
    public static final RegistryObject<EntityType<ToyotaJihadSpg9Entity>> TOYOTA_JIHAD_SPG9 =
            ENTITIES.register("toyota_jihad_spg9", () -> vehicle(ToyotaJihadSpg9Entity::new, 2.4f, 1.8f, "toyota_jihad_spg9"));
    public static final RegistryObject<EntityType<Btr80AEntity>> BTR80A =
            ENTITIES.register("btr80a", () -> vehicle(Btr80AEntity::new, 3.7f, 2.2f, "btr80a"));
    public static final RegistryObject<EntityType<Bmp2Entity>> BMP2 =
            ENTITIES.register("bmp2", () -> vehicle(Bmp2Entity::new, 3.7f, 2.2f, "bmp2"));
    public static final RegistryObject<EntityType<Bmp2MEntity>> BMP2M =
            ENTITIES.register("bmp2m", () -> vehicle(Bmp2MEntity::new, 3.15f, 2.45f, "bmp2m"));
    public static final RegistryObject<EntityType<Mi24VEntity>> MI24V =
            ENTITIES.register("mi24v", () -> vehicle(Mi24VEntity::new, 2.6f, 2.15f, "mi24v"));
    public static final RegistryObject<EntityType<Mi28NEntity>> MI28N =
            ENTITIES.register("mi28n", () -> vehicle(Mi28NEntity::new, 2.6f, 2.15f, "mi28n"));
    public static final RegistryObject<EntityType<Ka50Entity>> KA50 =
            ENTITIES.register("ka50", () -> vehicle(Ka50Entity::new, 2.6f, 2.15f, "ka50"));
    public static final RegistryObject<EntityType<M60A1Entity>> M60A1 =
            ENTITIES.register("m60a1", () -> vehicle(M60A1Entity::new, 3.7f, 2.2f, "m60a1"));
    public static final RegistryObject<EntityType<M48A3EliteEntity>> M48A3_ELITE =
            ENTITIES.register("m48a3_elite", () -> vehicle(M48A3EliteEntity::new, 3.7f, 2.2f, "m48a3_elite"));
    public static final RegistryObject<EntityType<T55AEntity>> T55A =
            ENTITIES.register("t55a_2_0", () -> vehicle(T55AEntity::new, 3.7f, 2.2f, "t55a_2_0"));
    public static final RegistryObject<EntityType<T64BObr1976Entity>> T64B_OBR1976 =
            ENTITIES.register("t64b_obr1976", () -> vehicle(T64BObr1976Entity::new, 3.7f, 2.2f, "t64b_obr1976"));
    public static final RegistryObject<EntityType<T72B3Entity>> T72B3 =
            ENTITIES.register("t72b3", () -> vehicle(T72B3Entity::new, 3.7f, 2.2f, "t72b3"));
    public static final RegistryObject<EntityType<T72B3UbhCopeEntity>> T72B3_UBH_COPE =
            ENTITIES.register("t72b3_ubh_cope", () -> vehicle(T72B3UbhCopeEntity::new, 3.7f, 2.2f, "t72b3_ubh_cope"));
    public static final RegistryObject<EntityType<M1AbramsEliteEntity>> M1_ABRAMS_ELITE =
            ENTITIES.register("m1_abrams_elite", () -> vehicle(M1AbramsEliteEntity::new, 3.7f, 2.2f, "m1_abrams_elite"));
    public static final RegistryObject<EntityType<M1A2AbramsSepV2Entity>> M1A2_ABRAMS_SEP_V2 =
            ENTITIES.register("m1a2_abrams_sep_v2", () -> vehicle(M1A2AbramsSepV2Entity::new, 3.7f, 2.2f, "m1a2_abrams_sep_v2"));
    public static final RegistryObject<EntityType<Ztz99AEntity>> ZTZ99A =
            ENTITIES.register("ztz99a", () -> vehicle(Ztz99AEntity::new, 3.7f, 2.2f, "ztz99a"));
    public static final RegistryObject<EntityType<Bmp3mEliteEntity>> BMP3M_ELITE =
            ENTITIES.register("bmp3m_elite", () -> vehicle(Bmp3mEliteEntity::new, 3.2f, 2.4f, "bmp3m_elite"));
    public static final RegistryObject<EntityType<M2BradleyEntity>> M2_BRADLEY =
            ENTITIES.register("m2_bradley", () -> vehicle(M2BradleyEntity::new, 3.2004f, 2.9718f, "m2_bradley"));
    public static final RegistryObject<EntityType<Marder1A1Entity>> MARDER_1A1 =
            ENTITIES.register("marder_1a1", () -> vehicle(Marder1A1Entity::new, 3.24f, 2.98f, "marder_1a1"));
    public static final RegistryObject<EntityType<Cv9040NoNetEntity>> CV9040_NO_NET =
            ENTITIES.register("cv9040_no_net", () -> vehicle(Cv9040NoNetEntity::new, 3.2f, 2.8f, "cv9040_no_net"));
    public static final RegistryObject<EntityType<Leo2A6Entity>> LEO2A6 =
            ENTITIES.register("leo2a6", () -> vehicle(Leo2A6Entity::new, 3.7f, 2.2f, "leo2a6"));
    public static final RegistryObject<EntityType<T80UObr1985Entity>> T80U_OBR1985 =
            ENTITIES.register("t80u_obr1985", () -> vehicle(T80UObr1985Entity::new, 3.7f, 2.2f, "t80u_obr1985"));
    public static final RegistryObject<EntityType<M551A1Entity>> M551A1 =
            ENTITIES.register("m551a1", () -> vehicle(M551A1Entity::new, 2.794f, 2.9464f, "m551a1"));
    public static final RegistryObject<EntityType<ToyotaJihadBmp1Entity>> TOYOTA_JIHAD_BMP1 =
            ENTITIES.register("toyota_jihad_bmp1", () -> vehicle(ToyotaJihadBmp1Entity::new, 2.4f, 1.8f, "toyota_jihad_bmp1"));
    public static final RegistryObject<EntityType<ToyotaJihadS5Entity>> TOYOTA_JIHAD_S5 =
            ENTITIES.register("toyota_jihad_s5", () -> vehicle(ToyotaJihadS5Entity::new, 2.4f, 1.8f, "toyota_jihad_s5"));
    public static final RegistryObject<EntityType<Zu23_2Entity>> ZU23_2 =
            ENTITIES.register("zu23_2", () -> vehicle(Zu23_2Entity::new, 3.0f, 2.2f, "zu23_2"));

    // Conservative registration bounds; these dimensions do not select per-vehicle physics.
    public static final RegistryObject<EntityType<Bmp1amEntity>> BMP_1AM =
            ENTITIES.register("bmp_1am", () -> vehicle(Bmp1amEntity::new, 3.7f, 2.2f, "bmp_1am"));
    public static final RegistryObject<EntityType<Marder1A2Entity>> MARDER_1A2 =
            ENTITIES.register("marder_1a2", () -> vehicle(Marder1A2Entity::new, 3.7f, 2.2f, "marder_1a2"));
    public static final RegistryObject<EntityType<M41Entity>> M41 =
            ENTITIES.register("m41", () -> vehicle(M41Entity::new, 3.7f, 2.2f, "m41"));
    public static final RegistryObject<EntityType<PantherGEntity>> PANTHER_G =
            ENTITIES.register("panther_g", () -> vehicle(PantherGEntity::new, 3.7f, 2.2f, "panther_g"));
    public static final RegistryObject<EntityType<StugIIIEntity>> STUG_III =
            ENTITIES.register("stug_iii", () -> vehicle(StugIIIEntity::new, 3.7f, 2.2f, "stug_iii"));
    public static final RegistryObject<EntityType<T62AEntity>> T62A =
            ENTITIES.register("t_62a", () -> vehicle(T62AEntity::new, 3.7f, 2.2f, "t_62a"));
    public static final RegistryObject<EntityType<Tiger1Entity>> TIGER_1 =
            ENTITIES.register("tiger_1", () -> vehicle(Tiger1Entity::new, 3.7f, 2.2f, "tiger_1"));
    public static final RegistryObject<EntityType<TigerIIEntity>> TIGER_II =
            ENTITIES.register("tiger_ii", () -> vehicle(TigerIIEntity::new, 3.7f, 2.2f, "tiger_ii"));
    public static final RegistryObject<EntityType<S2s25SprutSdEntity>> S2S25_SPRUT_SD =
            ENTITIES.register("2s25_sprut_sd", () -> vehicle(S2s25SprutSdEntity::new, 3.7f, 2.2f, "2s25_sprut_sd"));
    public static final RegistryObject<EntityType<Kamaz4310Entity>> KAMAZ4310 =
            ENTITIES.register("kamaz4310", () -> vehicle(Kamaz4310Entity::new, 3.7f, 2.2f, "kamaz4310"));
    public static final RegistryObject<EntityType<Bm21GradEntity>> BM_21_GRAD =
            ENTITIES.register("bm_21_grad", () -> vehicle(Bm21GradEntity::new, 3.7f, 2.2f, "bm_21_grad"));
    public static final RegistryObject<EntityType<Amx10rcEntity>> AMX_10RC =
            ENTITIES.register("amx_10rc", () -> vehicle(Amx10rcEntity::new, 3.7f, 2.2f, "amx_10rc"));
    public static final RegistryObject<EntityType<Btr60pbEntity>> BTR60PB =
            ENTITIES.register("btr_60pb", () -> vehicle(Btr60pbEntity::new, 3.7f, 2.2f, "btr_60pb"));
    public static final RegistryObject<EntityType<VbciEntity>> VBCI =
            ENTITIES.register("vbci", () -> vehicle(VbciEntity::new, 3.7f, 2.2f, "vbci"));
    public static final RegistryObject<EntityType<Lav25Entity>> LAV25 =
            ENTITIES.register("lav25", () -> vehicle(Lav25Entity::new, 2.5f, 2.2f, "lav25"));
    public static final RegistryObject<EntityType<M1128Entity>> M1128 =
            ENTITIES.register("m1128", () -> vehicle(M1128Entity::new, 3.0f, 2.2f, "m1128"));
    public static final RegistryObject<EntityType<Btr152Entity>> BTR152 =
            ENTITIES.register("btr_152", () -> vehicle(Btr152Entity::new, 3.7f, 2.2f, "btr_152"));
    public static final RegistryObject<EntityType<Ah6jEntity>> AH6J =
            ENTITIES.register("ah_6j", () -> vehicle(Ah6jEntity::new, 2.6f, 2.15f, "ah_6j"));
    public static final RegistryObject<EntityType<Ah1gCobraEntity>> AH1G_COBRA =
            ENTITIES.register("ah_1g_cobra", () -> vehicle(Ah1gCobraEntity::new, 2.6f, 2.15f, "ah_1g_cobra"));

    // Fixed mounted platforms and the fixed-wing MiG-19S.
    public static final RegistryObject<EntityType<Spg9TripodEntity>> SPG9_TRIPOD =
            ENTITIES.register("spg9_tripod", () -> vehicle(Spg9TripodEntity::new, 2.4f, 1.5f, "spg9_tripod"));
    public static final RegistryObject<EntityType<KordTripodEntity>> KORD_TRIPOD =
            ENTITIES.register("kord_tripod", () -> vehicle(KordTripodEntity::new, 2.2f, 1.8f, "kord_tripod"));
    public static final RegistryObject<EntityType<MilanTripodEntity>> MILAN_TRIPOD =
            ENTITIES.register("milan_tripod", () -> vehicle(MilanTripodEntity::new, 2.2f, 1.8f, "milan_tripod"));
    public static final RegistryObject<EntityType<BrowningTripodEntity>> BROWNING_TRIPOD =
            ENTITIES.register("browning_tripod", () -> vehicle(BrowningTripodEntity::new, 1.5f, 1.5f, "browning_tripod"));
    public static final RegistryObject<EntityType<TowTripodEntity>> TOW_TRIPOD =
            ENTITIES.register("tow_tripod", () -> vehicle(TowTripodEntity::new, 2.4f, 2.0f, "tow_tripod"));
    public static final RegistryObject<EntityType<Mig19Entity>> MIG19 =
            ENTITIES.register("mig19", () -> vehicle(Mig19Entity::new, 11.0f, 4.0f, "mig19"));

    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> YAK_3 =
            ENTITIES.register("yak_3", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "yak_3"),
                    11.1923f, 3.4614f, "yak_3"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> YAK_9U =
            ENTITIES.register("yak_9u", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "yak_9u"),
                    9.7514f, 2.7462f, "yak_9u"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> J_26 =
            ENTITIES.register("j_26", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "j_26"),
                    11.2876f, 4.0831f, "j_26"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> SAAB_105 =
            ENTITIES.register("saab_105", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "saab_105"),
                    10.8671f, 2.7193f, "saab_105"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> SUPERMARINE_SPITFIRE_GRIFFON =
            ENTITIES.register("supermarine_spitfire_griffon", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "supermarine_spitfire_griffon"),
                    13.1629f, 3.2093f, "supermarine_spitfire_griffon"));

    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> MIG_9 =
            ENTITIES.register("mig_9", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "mig_9"),
                    10f, 3.1839f, "mig_9"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> DO_335_B2 =
            ENTITIES.register("do_335_b2", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "do_335_b2"),
                    13.80000f, 4.96978f, "do_335_b2"));

    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> HS_129_B3 =
            ENTITIES.register("hs_129_b3", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "hs_129_b3"),
                    14.20000f, 4.61725f, "hs_129_b3"));

    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> P_39 =
            ENTITIES.register("p_39", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "p_39"),
                    10.40000f, 3.54043f, "p_39"));

    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> JU_87_B2 =
            ENTITIES.register("ju_87_b2", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "ju_87_b2"),
                    13.80000f, 3.91773f, "ju_87_b2"));

    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> HO_229 =
            ENTITIES.register("ho_229", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "ho_229"),
                    16.80000f, 2.47177f, "ho_229"));

    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> ME_163 =
            ENTITIES.register("me_163", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "me_163"),
                    9.3f, 2.697f, "me_163"));

    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> MIG_15BIS =
            ENTITIES.register("mig_15bis", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "mig_15bis"),
                    10.0965f, 3.651f, "mig_15bis"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> MIG_21BIS =
            ENTITIES.register("mig_21bis", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "mig_21bis"),
                    15.3861f, 4.0841f, "mig_21bis"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> SU_25 =
            ENTITIES.register("su_25", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "su_25"),
                    15.4569f, 5.4847f, "su_25"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> SU_27 =
            ENTITIES.register("su_27", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "su_27"),
                    21.326f, 5.7045f, "su_27"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> MIG_29 =
            ENTITIES.register("mig_29", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "mig_29"),
                    17.7435f, 4.8266f, "mig_29"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> MIG_19S =
            ENTITIES.register("mig_19s", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "mig_19s"),
                    12.51f, 3.825f, "mig_19s"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> SAAB_J_21A_1 =
            ENTITIES.register("saab_j_21a_1", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "saab_j_21a_1"),
                    11.6f, 3.1922f, "saab_j_21a_1"));

    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> F_84F =
            ENTITIES.register("f_84f", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "f_84f"),
                    13.2815f, 4.3052f, "f_84f"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> F_86K =
            ENTITIES.register("f_86k", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "f_86k"),
                    12.7902f, 4.4617f, "f_86k"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> F_104G =
            ENTITIES.register("f_104g", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "f_104g"),
                    17.0036f, 4.1497f, "f_104g"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> FIAT_G_91 =
            ENTITIES.register("fiat_g_91", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "fiat_g_91"),
                    10.4052f, 3.9917f, "fiat_g_91"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> YAK_15P =
            ENTITIES.register("yak_15p", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "yak_15p"),
                    9.6985f, 2.9685f, "yak_15p"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> SU_9 =
            ENTITIES.register("su_9", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "su_9"),
                    18.8556f, 4.8619f, "su_9"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> SAAB_29_TUNNAN =
            ENTITIES.register("saab_29_tunnan", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "saab_29_tunnan"),
                    11.0f, 3.7418f, "saab_29_tunnan"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> SAAB_32_LANSEN =
            ENTITIES.register("saab_32_lansen", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "saab_32_lansen"),
                    14.8958f, 4.6493f, "saab_32_lansen"));

    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> SU_17 =
            ENTITIES.register("su_17", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "su_17"),
                    19.1437f, 5.1262f, "su_17"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> MIG_23MLD =
            ENTITIES.register("mig_23mld", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "mig_23mld"),
                    16.7599f, 5.1183f, "mig_23mld"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> SAAB_35_DRAKEN =
            ENTITIES.register("saab_35_draken", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "saab_35_draken"),
                    16.45f, 4.6548f, "saab_35_draken"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> SAAB_37_VIGGEN =
            ENTITIES.register("saab_37_viggen", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "saab_37_viggen"),
                    16.5025f, 5.6298f, "saab_37_viggen"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> SAAB_JAS_39_GRIPEN =
            ENTITIES.register("saab_jas_39_gripen", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "saab_jas_39_gripen"),
                    14.6533f, 4.62f, "saab_jas_39_gripen"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> PANAVIA_TORNADO_IDS_MARINEFLIEGER =
            ENTITIES.register("panavia_tornado_ids_marineflieger", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "panavia_tornado_ids_marineflieger"),
                    17.7812f, 5.8669f, "panavia_tornado_ids_marineflieger"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> EUROFIGHTER_TYPHOON =
            ENTITIES.register("eurofighter_typhoon", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "eurofighter_typhoon"),
                    16.0178f, 5.2714f, "eurofighter_typhoon"));

    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> AN_12B =
            ENTITIES.register("an_12b", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "an_12b"),
                    38.02f, 10.4401f, "an_12b"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> IL_76M =
            ENTITIES.register("il_76m", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "il_76m"),
                    50.5f, 14.9017f, "il_76m"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> M_50A =
            ENTITIES.register("m_50a", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "m_50a"),
                    56.6965f, 10.0892f, "m_50a"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> TU_22M =
            ENTITIES.register("tu_22m", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "tu_22m"),
                    42.775f, 12.043f, "tu_22m"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> TU_95MS =
            ENTITIES.register("tu_95ms", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "tu_95ms"),
                    53.1425f, 12.6601f, "tu_95ms"));

    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> F_111F =
            ENTITIES.register("f_111f", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "f_111f"),
                    19.20000000f, 5.17305699f, "f_111f"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> F_16C =
            ENTITIES.register("f_16c", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "f_16c"),
                    10.68631579f, 4.74947368f, "f_16c"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> F_15C =
            ENTITIES.register("f_15c", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "f_15c"),
                    13.10000000f, 5.48259259f, "f_15c"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> F9F_2 =
            ENTITIES.register("f9f_2", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "f9f_2"),
                    11.60000000f, 3.84193700f, "f9f_2"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> F_5A =
            ENTITIES.register("f_5a", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "f_5a"),
                    8.60308642f, 4.32530864f, "f_5a"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> F_4C =
            ENTITIES.register("f_4c", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "f_4c"),
                    11.70000000f, 4.95000000f, "f_4c"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> B_47E =
            ENTITIES.register("b_47e", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "b_47e"),
                    35.35680000f, 8.48962712f, "b_47e"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> A_7D =
            ENTITIES.register("a_7d", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "a_7d"),
                    11.80000000f, 5.80000000f, "a_7d"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> F_100C =
            ENTITIES.register("f_100c", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "f_100c"),
                    11.81100000f, 5.16184444f, "f_100c"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> SU_57 =
            ENTITIES.register("su_57", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "su_57"),
                    14.15000000f, 4.75000000f, "su_57"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> F_22A =
            ENTITIES.register("f_22a", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "f_22a"),
                    13.60000000f, 5.05000000f, "f_22a"));

    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> METEOR_F_8 =
            ENTITIES.register("meteor_f_8", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "meteor_f_8"),
                    13.42220000f, 3.86670000f, "meteor_f_8"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> SABRE_MK_6 =
            ENTITIES.register("sabre_mk_6", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "sabre_mk_6"),
                    11.39910000f, 4.46050000f, "sabre_mk_6"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> MD_450_OURAGAN =
            ENTITIES.register("md_450_ouragan", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "md_450_ouragan"),
                    12.38420000f, 3.83320000f, "md_450_ouragan"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> MIRAGE_5 =
            ENTITIES.register("mirage_5", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "mirage_5"),
                    15.49890000f, 4.64070000f, "mirage_5"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> RAFALE =
            ENTITIES.register("rafale", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "rafale"),
                    13.88430000f, 4.91740000f, "rafale"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> SUPER_MYSTERE =
            ENTITIES.register("super_mystere", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "super_mystere"),
                    14.21120000f, 4.57110000f, "super_mystere"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> J_11A =
            ENTITIES.register("j_11a", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "j_11a"),
                    21.95000000f, 6.30000000f, "j_11a"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> J_2 =
            ENTITIES.register("j_2", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "j_2"),
                    10.10000000f, 3.65220000f, "j_2"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> J_5 =
            ENTITIES.register("j_5", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "j_5"),
                    11.16730000f, 3.72240000f, "j_5"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> IL_10 =
            ENTITIES.register("il_10", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "il_10"),
                    14.40960000f, 3.71710000f, "il_10"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> P_51D =
            ENTITIES.register("p_51d", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "p_51d"),
                    11.30000000f, 4.08620000f, "p_51d"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> F_8H =
            ENTITIES.register("f_8h", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "f_8h"),
                    16.39820000f, 5.70350000f, "f_8h"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> F8F_1 =
            ENTITIES.register("f8f_1", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "f8f_1"),
                    10.80000000f, 4.12360000f, "f8f_1"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> FA_18E =
            ENTITIES.register("fa_18e", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "fa_18e"),
                    13.83017241f, 4.89051724f, "fa_18e"));

    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> F_16A =
            ENTITIES.register("f_16a", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "f_16a"),
                    9.40000000f, 4.51200000f, "f_16a"));
    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> A_10 =
            ENTITIES.register("a_10", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "a_10"),
                    17.42000000f, 4.58939300f, "a_10"));

    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> F_14A =
            ENTITIES.register("f_14a", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "f_14a"),
                    19.00000000f, 5.32485300f, "f_14a"));

    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> F_14D =
            ENTITIES.register("f_14d", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "f_14d"),
                    19.00000000f, 5.32485300f, "f_14d"));

    public static final RegistryObject<EntityType<AuthoredHelicopter>> CH_46E =
            ENTITIES.register("ch_46e", () -> vehicle(
                    (type, level) -> new AuthoredHelicopter(type, level, "ch_46e"),
                    15.24000000f, 5.58915600f, "ch_46e"));

    public static final RegistryObject<EntityType<AuthoredHelicopter>> EUROCOPTER_TIGER =
            ENTITIES.register("eurocopter_tiger", () -> vehicle(
                    (type, level) -> new AuthoredHelicopter(type, level, "eurocopter_tiger"),
                    13.00000000f, 7.29432700f, "eurocopter_tiger"));

    public static final RegistryObject<EntityType<AuthoredHelicopter>> AH_64D =
            ENTITIES.register("ah_64d", () -> vehicle(
                    (type, level) -> new AuthoredHelicopter(type, level, "ah_64d"),
                    14.63000000f, 6.27000000f, "ah_64d"));

    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> F3H =
            ENTITIES.register("f3h", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "f3h"),
                    10.80000000f, 5.65000000f, "f3h"));

    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> F2H_2 =
            ENTITIES.register("f2h_2", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "f2h_2"),
                    12.60000000f, 4.34428800f, "f2h_2"));

    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> SU_35 =
            ENTITIES.register("su_35", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "su_35"),
                    14.70000000f, 5.86196300f, "su_35"));

    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> SU_39 =
            ENTITIES.register("su_39", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "su_39"),
                    14.40000000f, 6.35000000f, "su_39"));

    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> MIRAGE_F1 =
            ENTITIES.register("mirage_f1", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "mirage_f1"),
                    8.40000000f, 4.24397400f, "mirage_f1"));

    public static final RegistryObject<EntityType<AuthoredFixedWingAircraft>> B_1B =
            ENTITIES.register("b_1b", () -> vehicle(
                    (type, level) -> new AuthoredFixedWingAircraft(type, level, "b_1b"),
                    41.80000000f, 10.35697300f, "b_1b"));

    public static final RegistryObject<EntityType<FittedGroundVehicleEntity>> CHALLENGER_2 =
            ENTITIES.register("challenger_2", () -> vehicle(
                    (type, level) -> new FittedGroundVehicleEntity(type, level, "challenger_2"),
                    12.04753f, 3.49779f, "challenger_2"));
    public static final RegistryObject<EntityType<FittedGroundVehicleEntity>> LECLERC_S1 =
            ENTITIES.register("leclerc_s1", () -> vehicle(
                    (type, level) -> new FittedGroundVehicleEntity(type, level, "leclerc_s1"),
                    11.05607f, 3.61981f, "leclerc_s1"));
    public static final RegistryObject<EntityType<FittedGroundVehicleEntity>> M103 =
            ENTITIES.register("m103", () -> vehicle(
                    (type, level) -> new FittedGroundVehicleEntity(type, level, "m103"),
                    11.30400f, 5.09716f, "m103"));
    public static final RegistryObject<EntityType<FittedGroundVehicleEntity>> MBT_70 =
            ENTITIES.register("mbt_70", () -> vehicle(
                    (type, level) -> new FittedGroundVehicleEntity(type, level, "mbt_70"),
                    9.15977f, 3.53857f, "mbt_70"));
    public static final RegistryObject<EntityType<FittedGroundVehicleEntity>> AML_90 =
            ENTITIES.register("aml_90", () -> vehicle(
                    (type, level) -> new FittedGroundVehicleEntity(type, level, "aml_90"),
                    2.03492700f, 2.38816000f, "aml_90"));
    public static final RegistryObject<EntityType<FittedGroundVehicleEntity>> ZSL_92 =
            ENTITIES.register("zsl_92", () -> vehicle(
                    (type, level) -> new FittedGroundVehicleEntity(type, level, "zsl_92"),
                    2.96688300f, 2.36095000f, "zsl_92"));
    public static final RegistryObject<EntityType<FittedGroundVehicleEntity>> FV721_FOX =
            ENTITIES.register("fv721_fox", () -> vehicle(
                    (type, level) -> new FittedGroundVehicleEntity(type, level, "fv721_fox"),
                    2.18051600f, 1.69310700f, "fv721_fox"));
    public static final RegistryObject<EntityType<FittedGroundVehicleEntity>> GAZ_3937_VODNIK_AA =
            ENTITIES.register("gaz_3937_vodnik_aa", () -> vehicle(
                    (type, level) -> new FittedGroundVehicleEntity(type, level, "gaz_3937_vodnik_aa"),
                    2.79907200f, 3.67378200f, "gaz_3937_vodnik_aa"));
    public static final RegistryObject<EntityType<FittedGroundVehicleEntity>> BMD_1 =
            ENTITIES.register("bmd_1", () -> vehicle(
                    (type, level) -> new FittedGroundVehicleEntity(type, level, "bmd_1"),
                    2.61633800f, 1.96880100f, "bmd_1"));
    public static final RegistryObject<EntityType<FittedGroundVehicleEntity>> BTR_ZD =
            ENTITIES.register("btr_zd", () -> vehicle(
                    (type, level) -> new FittedGroundVehicleEntity(type, level, "btr_zd"),
                    2.47680000f, 2.93419800f, "btr_zd"));
    public static final RegistryObject<EntityType<FittedGroundVehicleEntity>> WARRIOR =
            ENTITIES.register("warrior", () -> vehicle(
                    (type, level) -> new FittedGroundVehicleEntity(type, level, "warrior"),
                    2.95699600f, 1.90781100f, "warrior"));
    public static final RegistryObject<EntityType<FittedGroundVehicleEntity>> BEGLEITPANZER =
            ENTITIES.register("begleitpanzer", () -> vehicle(
                    (type, level) -> new FittedGroundVehicleEntity(type, level, "begleitpanzer"),
                    3.10325400f, 1.84414700f, "begleitpanzer"));
    public static final RegistryObject<EntityType<FittedGroundVehicleEntity>> TYPE_89_IFV =
            ENTITIES.register("type_89_ifv", () -> vehicle(
                    (type, level) -> new FittedGroundVehicleEntity(type, level, "type_89_ifv"),
                    3.40000000f, 1.95680000f, "type_89_ifv"));
    public static final RegistryObject<EntityType<FittedGroundVehicleEntity>> BTR_90 =
            ENTITIES.register("btr_90", () -> vehicle(
                    (type, level) -> new FittedGroundVehicleEntity(type, level, "btr_90"),
                    9.29722f, 2.93303f, "btr_90"));
    public static final RegistryObject<EntityType<FittedGroundVehicleEntity>> LEOPARD_2A7 =
            ENTITIES.register("leopard_2a7", () -> vehicle(
                    (type, level) -> new FittedGroundVehicleEntity(type, level, "leopard_2a7"),
                    14.23741f, 3.77539f, "leopard_2a7"));
    public static final RegistryObject<EntityType<FittedGroundVehicleEntity>> LEOPARD_2A4 =
            ENTITIES.register("leopard_2a4", () -> vehicle(
                    (type, level) -> new FittedGroundVehicleEntity(type, level, "leopard_2a4"),
                    11.32897f, 3.76247f, "leopard_2a4"));
    public static final RegistryObject<EntityType<FittedGroundVehicleEntity>> LAV_III =
            ENTITIES.register("lav_iii", () -> vehicle(
                    (type, level) -> new FittedGroundVehicleEntity(type, level, "lav_iii"),
                    8.35951f, 4.20007f, "lav_iii"));
    public static final RegistryObject<EntityType<FittedGroundVehicleEntity>> K2A1_BLACK_PANTHER =
            ENTITIES.register("k2a1_black_panther", () -> vehicle(
                    (type, level) -> new FittedGroundVehicleEntity(type, level, "k2a1_black_panther"),
                    15.01081f, 4.37306f, "k2a1_black_panther"));
    public static final RegistryObject<EntityType<FittedGroundVehicleEntity>> M109A7_PALADIN =
            ENTITIES.register("m109a7_paladin", () -> vehicle(
                    (type, level) -> new FittedGroundVehicleEntity(type, level, "m109a7_paladin"),
                    15.80975f, 5.35243f, "m109a7_paladin"));
    public static final RegistryObject<EntityType<FittedGroundVehicleEntity>> MARDER_1A5 =
            ENTITIES.register("marder_1a5", () -> vehicle(
                    (type, level) -> new FittedGroundVehicleEntity(type, level, "marder_1a5"),
                    7.06807f, 3.07481f, "marder_1a5"));
    public static final RegistryObject<EntityType<FittedGroundVehicleEntity>> ZBD_09 =
            ENTITIES.register("zbd_09", () -> vehicle(
                    (type, level) -> new FittedGroundVehicleEntity(type, level, "zbd_09"),
                    9.17936f, 2.84484f, "zbd_09"));
    public static final RegistryObject<EntityType<FittedGroundVehicleEntity>> ZTL_09 =
            ENTITIES.register("ztl_09", () -> vehicle(
                    (type, level) -> new FittedGroundVehicleEntity(type, level, "ztl_09"),
                    11.98627f, 3.21714f, "ztl_09"));
    public static final RegistryObject<EntityType<FittedGroundVehicleEntity>> PZH_2000 =
            ENTITIES.register("pzh_2000", () -> vehicle(
                    (type, level) -> new FittedGroundVehicleEntity(type, level, "pzh_2000"),
                    18.89894f, 4.67317f, "pzh_2000"));
    public static final RegistryObject<EntityType<FittedGroundVehicleEntity>> QN_506MODEL =
            ENTITIES.register("qn_506model", () -> vehicle(
                    (type, level) -> new FittedGroundVehicleEntity(type, level, "qn_506model"),
                    6.72442f, 5.09355f, "qn_506model"));
    public static final RegistryObject<EntityType<FittedGroundVehicleEntity>> TUNGUSKA =
            ENTITIES.register("tunguska", () -> vehicle(
                    (type, level) -> new FittedGroundVehicleEntity(type, level, "tunguska"),
                    3.34f, 4.05f, "tunguska"));
    public static final RegistryObject<EntityType<FittedGroundVehicleEntity>> VT_4A1 =
            ENTITIES.register("vt_4a1", () -> vehicle(
                    (type, level) -> new FittedGroundVehicleEntity(type, level, "vt_4a1"),
                    12.12491f, 4.02015f, "vt_4a1"));

    public static final RegistryObject<EntityType<AuthoredHelicopter>> MI_24A =
            ENTITIES.register("mi_24a", () -> vehicle(
                    (type, level) -> new AuthoredHelicopter(type, level, "mi_24a"),
                    28.44752f, 9.35451f, "mi_24a"));
    public static final RegistryObject<EntityType<AuthoredHelicopter>> MI_24D =
            ENTITIES.register("mi_24d", () -> vehicle(
                    (type, level) -> new AuthoredHelicopter(type, level, "mi_24d"),
                    28.44752f, 9.35451f, "mi_24d"));
    public static final RegistryObject<EntityType<AuthoredHelicopter>> MI_26 =
            ENTITIES.register("mi_26", () -> vehicle(
                    (type, level) -> new AuthoredHelicopter(type, level, "mi_26"),
                    61.38406f, 16.96875f, "mi_26"));
    public static final RegistryObject<EntityType<AuthoredHelicopter>> AH_1F =
            ENTITIES.register("ah_1f", () -> vehicle(
                    (type, level) -> new AuthoredHelicopter(type, level, "ah_1f"),
                    20.75f, 5.75f, "ah_1f"));

    public static void register(IEventBus eventBus) {
        ENTITIES.register(eventBus);
    }

    private static <T extends Entity> EntityType<T> vehicle(EntityType.EntityFactory<T> factory,
                                                             float width, float height, String id) {
        return EntityType.Builder.m_20704_(factory, MobCategory.MISC)
                .m_20699_(width, height)
                .m_20702_(10)
                .m_20712_(id);
    }

    private static <T extends Entity> EntityType.Builder<T> fastProjectile(EntityType.EntityFactory<T> factory) {
        return EntityType.Builder.m_20704_(factory, MobCategory.MISC)
                .setShouldReceiveVelocityUpdates(false)
                .setTrackingRange(FAST_PROJECTILE_TRACKING_RANGE)
                .setUpdateInterval(FAST_PROJECTILE_UPDATE_INTERVAL)
                .m_20716_();
    }

    private ModEntities() {
    }
}
