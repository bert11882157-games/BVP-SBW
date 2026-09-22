package com.atsuishio.superbwarfare.data.vehicle;

import com.atsuishio.superbwarfare.data.CustomData;
import com.atsuishio.superbwarfare.data.DataLoader;
import com.atsuishio.superbwarfare.data.DefaultDataSupplier;
import com.atsuishio.superbwarfare.data.JsonPropertyModifier;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.entity.vehicle.damage.DamageModifier;
import com.atsuishio.superbwarfare.entity.vehicle.damage.DamageModify;
import com.atsuishio.superbwarfare.init.ModDamageTypes;
import net.minecraft.world.entity.EntityType;

import java.util.List;

public class VehicleData implements DefaultDataSupplier<DefaultVehicleData> {

    private static volatile int dataRevision;

    public final String id;
    public final VehicleEntity vehicle;

    public VehicleData(VehicleEntity entity) {
        this.id = getRegistryId(entity.getType());
        this.vehicle = entity;
    }

    private final JsonPropertyModifier<VehicleData, DefaultVehicleData> jsonPropModifier = new JsonPropertyModifier<>();

    private DefaultVehicleData cache = null;
    private int cacheRevision = -1;

    public DefaultVehicleData compute() {
        int revision = dataRevision;
        if (cache != null && cacheRevision == revision) return cache;

        var raw = getDefault().copy();

        if (vehicle.isInitialized()) {
            jsonPropModifier.update(this.vehicle.getOverride());
            raw = jsonPropModifier.computeProperties(this, raw);
        }

        raw.limit();
        cache = raw;
        cacheRevision = revision;

        return raw;
    }

    public void update() {
        this.cache = null;
    }

    public static void invalidateAll() {
        dataRevision++;
    }

    public static DefaultVehicleData getDefault(String id) {
        var isDefault = !CustomData.VEHICLE_DATA.containsKey(id);
        var data = CustomData.VEHICLE_DATA.getOrElseGet(id, DefaultVehicleData::new);
        data.isDefaultData = isDefault;
        return data;
    }

    public DefaultVehicleData getDefault() {
        return getDefault(this.id);
    }

    public static DefaultVehicleData getDefault(VehicleEntity entity) {
        return getDefault(entity.getType());
    }

    public static DefaultVehicleData getDefault(EntityType<?> type) {
        return getDefault(getRegistryId(type));
    }

    public static String getRegistryId(EntityType<?> type) {
        return EntityType.getKey(type).toString();
    }

    @SuppressWarnings("unchecked")
    public DamageModifier damageModifier() {
        var modifier = new DamageModifier();
        var data = compute();

        if (data.applyDefaultDamageModifiers) {
            modifier.addAll(DamageModifier.createDefaultModifier().toList());
            modifier.reduce(5, ModDamageTypes.VEHICLE_STRIKE);
        }

        return modifier.addAll((List<DamageModify>) DataLoader.processValue(data.damageModifiers));
    }
}
