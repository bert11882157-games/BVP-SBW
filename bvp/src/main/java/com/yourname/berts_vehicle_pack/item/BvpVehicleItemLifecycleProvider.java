package com.yourname.berts_vehicle_pack.item;

import com.atsuishio.superbwarfare.api.vehicle.lifecycle.VehicleItemLifecycleProvider;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

/** BVP ownership adapter for the shared, bounded vehicle-item lifecycle. */
public final class BvpVehicleItemLifecycleProvider implements VehicleItemLifecycleProvider {
    @Override
    public ItemStack createFromEntity(VehicleEntity vehicle) {
        ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(vehicle.m_6095_());
        if (typeId == null || !BertsVehiclePack.MODID.equals(typeId.m_135827_())) {
            return null;
        }
        return BvpVehicleItem.create(vehicle.m_6095_(), vehicle.createVehicleItemState());
    }

    @Override
    public VehicleEntity createFromType(EntityType<?> type, Level level) {
        ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(type);
        if (typeId == null || !BertsVehiclePack.MODID.equals(typeId.m_135827_())) {
            return null;
        }
        Entity entity = type.m_20615_(level);
        return entity instanceof VehicleEntity vehicle ? vehicle : null;
    }

    @Override
    public boolean restorePlacementState(VehicleEntity vehicle, CompoundTag state) {
        ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(vehicle.m_6095_());
        return typeId != null
                && BertsVehiclePack.MODID.equals(typeId.m_135827_())
                && vehicle.restoreVehicleItemState(state);
    }
}
