package com.yourname.berts_vehicle_pack.init;

import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.item.BvpVehicleItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public class ModTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.f_279569_, BertsVehiclePack.MODID);

    // Retain the original tab registry identity for the tank category.
    public static final RegistryObject<CreativeModeTab> VEHICLE_TAB = TABS.register("vehicles",
            () -> vehicleTab("tanks", "vehicles", 0).m_257652_());

    public static final RegistryObject<CreativeModeTab> IFV_APC_TAB = TABS.register("ifv_apc",
            () -> vehicleTab("ifv_apc", "ifv_apc", 1)
                    .withTabsBefore(VEHICLE_TAB.getKey()).m_257652_());

    public static final RegistryObject<CreativeModeTab> AIRCRAFT_TAB = TABS.register("aircraft",
            () -> vehicleTab("aircraft", "aircraft", 2)
                    .withTabsBefore(IFV_APC_TAB.getKey()).m_257652_());

    public static final RegistryObject<CreativeModeTab> MISC_TAB = TABS.register("misc",
            () -> vehicleTab("misc", "misc", 3)
                    .withTabsBefore(AIRCRAFT_TAB.getKey()).m_257652_());

    private static CreativeModeTab.Builder vehicleTab(String categoryId, String titleId, int column) {
        BvpCreativeCatalog.Category category = BvpCreativeCatalog.category(categoryId);
        return CreativeModeTab.m_257815_(CreativeModeTab.Row.TOP, column)
                .m_257941_(Component.m_237115_("itemGroup.berts_vehicle_pack." + titleId))
                .m_257737_(() -> BvpVehicleItem.create(BvpCreativeCatalog.entity(category.iconVehicleId())))
                .m_257501_((parameters, output) -> {
                    if (categoryId.equals("misc")) {
                        output.m_246342_(new ItemStack(ModItems.LEVEL_1_AP_SHELL.get()));
                        output.m_246342_(new ItemStack(ModItems.LEVEL_2_AP_SHELL.get()));
                        output.m_246342_(new ItemStack(ModItems.LEVEL_3_AP_SHELL.get()));
                        output.m_246342_(new ItemStack(ModItems.THREE_OF_26_HE_SHELL.get()));
                    }
                    for (String id : category.vehicleIds()) {
                        output.m_246342_(BvpVehicleItem.create(BvpCreativeCatalog.entity(id)));
                    }
                });
    }

    private ModTabs() {
    }
}
