package com.yourname.berts_vehicle_pack.init;

import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.item.BvpVehicleItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, BertsVehiclePack.MODID);

    public static final RegistryObject<Item> VEHICLE = ITEMS.register("vehicle", BvpVehicleItem::new);
    public static final RegistryObject<Item> LEVEL_1_AP_SHELL = shell("level_1_ap_shell");
    public static final RegistryObject<Item> LEVEL_2_AP_SHELL = shell("level_2_ap_shell");
    public static final RegistryObject<Item> LEVEL_3_AP_SHELL = shell("level_3_ap_shell");
    public static final RegistryObject<Item> THREE_OF_26_HE_SHELL = shell("3of26_he_shell");

    private ModItems() {
    }

    private static RegistryObject<Item> shell(String name) {
        return ITEMS.register(name, () -> new Item(new Item.Properties()));
    }
}
