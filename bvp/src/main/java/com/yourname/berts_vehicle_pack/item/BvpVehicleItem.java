package com.yourname.berts_vehicle_pack.item;

import com.atsuishio.superbwarfare.api.vehicle.lifecycle.VehicleItemLifecycleProviders;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.client.renderer.item.BvpVehicleItemRenderer;
import com.yourname.berts_vehicle_pack.init.ModItems;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * One BVP item ID carries a typed vehicle identity and, after crowbar
 * recovery, a bounded durable-state envelope.  It never places SBW's container block.
 */
public final class BvpVehicleItem extends Item {
    public static final ResourceLocation LIFECYCLE_PROVIDER_ID =
            new ResourceLocation(BertsVehiclePack.MODID, "vehicle_item");
    private static final String ROOT_TAG = "BvpVehicle";
    private static final String SCHEMA_TAG = "Schema";
    private static final String ENTITY_TYPE_TAG = "EntityType";
    private static final String STATE_TAG = "VehicleState";
    private static final int SCHEMA_VERSION = 1;

    public BvpVehicleItem() {
        super(new Item.Properties().m_41487_(1).m_41486_());
    }

    public static ItemStack create(EntityType<?> type) {
        return create(type, null);
    }

    public static ItemStack create(EntityType<?> type, CompoundTag durableState) {
        ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(type);
        if (typeId == null || !BertsVehiclePack.MODID.equals(typeId.m_135827_())) {
            return ItemStack.f_41583_;
        }

        ItemStack stack = new ItemStack(ModItems.VEHICLE.get());
        CompoundTag root = new CompoundTag();
        root.m_128405_(SCHEMA_TAG, SCHEMA_VERSION);
        root.m_128359_(ENTITY_TYPE_TAG, typeId.toString());
        if (durableState != null && !durableState.m_128456_()) {
            root.m_128365_(STATE_TAG, durableState.m_6426_());
        }
        stack.m_41784_().m_128365_(ROOT_TAG, root);
        return stack;
    }

    public static Optional<ResourceLocation> getEntityTypeId(ItemStack stack) {
        CompoundTag root = getEnvelope(stack);
        if (root == null || root.m_128451_(SCHEMA_TAG) != SCHEMA_VERSION) {
            return Optional.empty();
        }
        ResourceLocation id = ResourceLocation.m_135820_(root.m_128461_(ENTITY_TYPE_TAG));
        if (id == null || !BertsVehiclePack.MODID.equals(id.m_135827_())) {
            return Optional.empty();
        }
        return Optional.of(id);
    }

    public static Optional<EntityType<?>> getEntityType(ItemStack stack) {
        return getEntityTypeId(stack).map(ForgeRegistries.ENTITY_TYPES::getValue).filter(type -> type != null);
    }

    public static CompoundTag getDurableState(ItemStack stack) {
        CompoundTag root = getEnvelope(stack);
        if (root == null || !root.m_128425_(STATE_TAG, Tag.f_178203_)) {
            return null;
        }
        return root.m_128469_(STATE_TAG).m_6426_();
    }

    private static CompoundTag getEnvelope(ItemStack stack) {
        if (!(stack.m_41720_() instanceof BvpVehicleItem) || stack.m_41613_() != 1 || !stack.m_41782_()) {
            return null;
        }
        CompoundTag tag = stack.m_41783_();
        return tag != null && tag.m_128425_(ROOT_TAG, Tag.f_178203_)
                ? tag.m_128469_(ROOT_TAG)
                : null;
    }

    @Override
    public @NotNull Component m_7626_(@NotNull ItemStack stack) {
        return getEntityType(stack)
                .map(EntityType::m_20676_)
                .orElseGet(() -> Component.m_237115_("item.berts_vehicle_pack.vehicle"));
    }

    @Override
    public @NotNull InteractionResult m_6225_(@NotNull UseOnContext context) {
        ItemStack stack = context.m_43722_();
        Optional<EntityType<?>> type = getEntityType(stack);
        if (type.isEmpty()) {
            return InteractionResult.FAIL;
        }

        Level level = context.m_43725_();
        if (level.m_5776_()) {
            return InteractionResult.SUCCESS;
        }

        VehicleEntity vehicle = VehicleItemLifecycleProviders.createFromType(
                LIFECYCLE_PROVIDER_ID,
                type.get(),
                level
        );
        if (vehicle == null) {
            return InteractionResult.FAIL;
        }

        BlockPos placement = context.m_8083_().m_121945_(context.m_43719_());
        Player player = context.m_43723_();
        float yaw = player == null ? 0.0F : Mth.m_14177_(player.m_146908_());
        vehicle.m_7678_(
                placement.m_123341_() + 0.5D,
                placement.m_123342_() + 0.5D,
                placement.m_123343_() + 0.5D,
                yaw,
                0.0F
        );
        vehicle.f_19859_ = yaw;
        vehicle.f_19860_ = 0.0F;

        CompoundTag durableState = getDurableState(stack);
        if (durableState != null && !VehicleItemLifecycleProviders.restorePlacementState(
                LIFECYCLE_PROVIDER_ID,
                vehicle,
                durableState
        )) {
            return InteractionResult.FAIL;
        }

        // State restoration cannot choose position or orientation.  The player's forward bearing
        // is the sole placement heading, including for a recovered vehicle.
        vehicle.m_7678_(
                placement.m_123341_() + 0.5D,
                placement.m_123342_() + 0.5D,
                placement.m_123343_() + 0.5D,
                yaw,
                0.0F
        );
        vehicle.f_19859_ = yaw;
        vehicle.f_19860_ = 0.0F;
        if (!level.m_45756_(vehicle, vehicle.m_20191_()) || !level.m_7967_(vehicle)) {
            return InteractionResult.FAIL;
        }

        level.m_220400_(player, GameEvent.f_157810_, vehicle.m_20182_());
        if (player == null || !player.m_150110_().f_35937_) {
            stack.m_41774_(1);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void initializeClient(@NotNull Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            private final BlockEntityWithoutLevelRenderer renderer = new BvpVehicleItemRenderer();

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return renderer;
            }
        });
    }
}
