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

    // Wave 2 typed fleet registrations.  Boxes remain conservative until the
    // frozen authoring dimensions are available; no per-vehicle physics path is
    // implied by these registration dimensions.
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

    // Wave 4: four fixed mounted platforms plus one fixed-wing MiG-19S.
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
