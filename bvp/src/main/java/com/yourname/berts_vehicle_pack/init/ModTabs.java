package com.yourname.berts_vehicle_pack.init;

import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.item.BvpVehicleItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

public class ModTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.f_279569_, BertsVehiclePack.MODID);

    private static final List<RegistryObject<? extends EntityType<?>>> VEHICLES = List.of(
            ModEntities.T72B,
            ModEntities.T72B3,
            ModEntities.T72B3_UBH_COPE,
            ModEntities.T80B_OBR1976,
            ModEntities.M60A1,
            ModEntities.M1_ABRAMS_ELITE,
            ModEntities.M1A2_ABRAMS_SEP_V2,
            ModEntities.T64B_OBR1976,
            ModEntities.M48A3_ELITE,
            ModEntities.T80U_OBR1985,
            ModEntities.T72A,
            ModEntities.T90A,
            ModEntities.T90M,
            ModEntities.BMPT,
            ModEntities.ZSU23_4,
            ModEntities.ZTZ99A,
            ModEntities.LEO2A6,
            ModEntities.TOYOTA_JIHAD_DSHK,
            ModEntities.TOYOTA_JIHAD_SPG9,
            ModEntities.BTR80A,
            ModEntities.BMP2,
            ModEntities.BMP2M,
            ModEntities.T55A,
            ModEntities.MI24V,
            ModEntities.MI28N,
            ModEntities.KA50,
            ModEntities.BMP3M_ELITE,
            ModEntities.M2_BRADLEY,
            ModEntities.MARDER_1A1,
            ModEntities.CV9040_NO_NET,
            ModEntities.M551A1,
            ModEntities.TOYOTA_JIHAD_BMP1,
            ModEntities.TOYOTA_JIHAD_S5,
            ModEntities.ZU23_2,
            ModEntities.BMP_1AM,
            ModEntities.MARDER_1A2,
            ModEntities.M41,
            ModEntities.PANTHER_G,
            ModEntities.STUG_III,
            ModEntities.T62A,
            ModEntities.TIGER_1,
            ModEntities.TIGER_II,
            ModEntities.S2S25_SPRUT_SD,
            ModEntities.KAMAZ4310,
            ModEntities.BM_21_GRAD,
            ModEntities.AMX_10RC,
            ModEntities.BTR60PB,
            ModEntities.VBCI,
            ModEntities.LAV25,
            ModEntities.M1128,
            ModEntities.BTR152,
            ModEntities.AH6J,
            ModEntities.AH1G_COBRA,
            ModEntities.KORD_TRIPOD,
            ModEntities.MILAN_TRIPOD,
            ModEntities.BROWNING_TRIPOD,
            ModEntities.TOW_TRIPOD,
            ModEntities.MIG19
    );

    public static final RegistryObject<CreativeModeTab> VEHICLE_TAB = TABS.register("vehicles", () ->
            CreativeModeTab.m_257815_(CreativeModeTab.Row.TOP, 0)
                    .m_257941_(Component.m_237115_("itemGroup.berts_vehicle_pack.vehicles"))
                    .m_257737_(() -> BvpVehicleItem.create(ModEntities.T72B.get()))
                    .m_257501_((parameters, output) -> {
                        output.m_246342_(new ItemStack(ModItems.LEVEL_1_AP_SHELL.get()));
                        output.m_246342_(new ItemStack(ModItems.LEVEL_2_AP_SHELL.get()));
                        output.m_246342_(new ItemStack(ModItems.LEVEL_3_AP_SHELL.get()));
                        output.m_246342_(new ItemStack(ModItems.THREE_OF_26_HE_SHELL.get()));
                        for (RegistryObject<? extends EntityType<?>> vehicle : VEHICLES) {
                            output.m_246342_(BvpVehicleItem.create(vehicle.get()));
                        }
                    })
                    .m_257652_());

    private ModTabs() {
    }
}
