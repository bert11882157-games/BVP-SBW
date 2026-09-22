package com.yourname.berts_vehicle_pack.ammo;

import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class BvpTankShells {
    public static final int SAFE_CANNON_SHELL_LIMIT = 20;
    public static final int SUPER_AMMO_RACK_SHELL_LIMIT = 50;
    public static final int NO_AMMO_RACK_WARNING_LEVEL = 0;
    public static final int AMMO_RACK_WARNING_LEVEL = 1;
    public static final int SUPER_AMMO_RACK_WARNING_LEVEL = 2;
    public static final String AMMO_RACK_WARNING =
            "Warning: adding more than 20 shells in the tank will DRASTICALLY increase your chance of getting killed by an ammo rack hit!";
    public static final String SUPER_AMMO_RACK_WARNING =
            "Whoa there buddy. If you insist on filling all of the empty space in the tank with shells, more power to you. I think you can fill in the blanks on what will happen when your tank takes any damage at all.";

    private static final String SUPERB_WARFARE_NAMESPACE = "superbwarfare";
    private static final Set<ResourceLocation> CANNON_SHELL_IDS = Set.of(
            id(BertsVehiclePack.MODID, "level_1_ap_shell"),
            id(BertsVehiclePack.MODID, "level_2_ap_shell"),
            id(BertsVehiclePack.MODID, "level_3_ap_shell"),
            id(BertsVehiclePack.MODID, "3of26_he_shell"),
            id(SUPERB_WARFARE_NAMESPACE, "large_shell_ap"),
            id(SUPERB_WARFARE_NAMESPACE, "large_shell_he"),
            id(SUPERB_WARFARE_NAMESPACE, "large_shell_cm"),
            id(SUPERB_WARFARE_NAMESPACE, "large_shell_gs"),
            id(SUPERB_WARFARE_NAMESPACE, "large_shell_wp")
    );
    private static final Map<Item, Boolean> CANNON_SHELL_ITEMS = new ConcurrentHashMap<>();

    private BvpTankShells() {
    }

    public static boolean isCannonShell(ItemStack stack) {
        return stack != null && !stack.m_41619_() && isCannonShell(stack.m_41720_());
    }

    public static boolean isCannonShell(Item item) {
        return item != null && CANNON_SHELL_ITEMS.computeIfAbsent(item, BvpTankShells::isRegisteredCannonShell);
    }

    private static boolean isRegisteredCannonShell(Item item) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
        return key != null && CANNON_SHELL_IDS.contains(key);
    }

    public static boolean exceedsSuperAmmoRackLimit(Iterable<ItemStack> stacks) {
        return warningLevelForContents(stacks) >= SUPER_AMMO_RACK_WARNING_LEVEL;
    }

    public static int warningLevelForContents(Iterable<ItemStack> stacks) {
        int shellCount = 0;
        for (ItemStack stack : stacks) {
            if (isCannonShell(stack)) {
                shellCount += stack.m_41613_();
                if (shellCount > SUPER_AMMO_RACK_SHELL_LIMIT) {
                    return SUPER_AMMO_RACK_WARNING_LEVEL;
                }
            }
        }
        return warningLevelForShellCount(shellCount);
    }

    public static int warningLevelForShellCount(int shellCount) {
        if (shellCount > SUPER_AMMO_RACK_SHELL_LIMIT) {
            return SUPER_AMMO_RACK_WARNING_LEVEL;
        }
        if (shellCount > SAFE_CANNON_SHELL_LIMIT) {
            return AMMO_RACK_WARNING_LEVEL;
        }
        return NO_AMMO_RACK_WARNING_LEVEL;
    }

    public static String warningTextForLevel(int warningLevel) {
        if (warningLevel >= SUPER_AMMO_RACK_WARNING_LEVEL) {
            return SUPER_AMMO_RACK_WARNING;
        }
        if (warningLevel >= AMMO_RACK_WARNING_LEVEL) {
            return AMMO_RACK_WARNING;
        }
        return "";
    }

    private static ResourceLocation id(String namespace, String path) {
        return new ResourceLocation(namespace, path);
    }
}
