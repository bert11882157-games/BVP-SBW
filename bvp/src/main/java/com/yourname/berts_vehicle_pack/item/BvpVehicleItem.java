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
import net.minecraft.ChatFormatting;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.phys.Vec3;
import com.atsuishio.superbwarfare.api.vehicle.deck.DeckCollisions;
import com.atsuishio.superbwarfare.api.vehicle.deck.DeckPose;
import com.yourname.berts_vehicle_pack.carrier.CarrierPlacement;
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
        Player player = context.m_43723_();
        if (player != null) {
            String id = carrierId(type.get());
            if (id != null) {
                return launchCarrier(level, player, stack, type.get(), id);
            }
            // A carrier deck between the player and the clicked block takes the vehicle, as terrain would.
            Vec3 eye = player.getEyePosition();
            DeckCollisions.Hit deck = DeckCollisions.clip(level, eye, context.getClickLocation(), player);
            if (deck != null && deck.fromAbove) {
                return placeOnDeck(level, player, stack, type.get(), deck);
            }
        }
        if (level.m_5776_()) {
            return InteractionResult.SUCCESS;
        }
        BlockPos placement = context.m_8083_().m_121945_(context.m_43719_());
        float yaw = player == null ? 0.0F : Mth.m_14177_(player.m_146908_());
        return spawn(level, player, stack, type.get(), placement.m_123341_() + 0.5D, placement.m_123342_() + 0.5D,
                placement.m_123343_() + 0.5D, yaw, true);
    }

    /**
     * Air use: a carrier launches onto the water the player looks at (CarrierPlacement); any other vehicle is put
     * down on a carrier deck the player looks at within reach.
     */
    @Override
    public @NotNull InteractionResultHolder<ItemStack> use(@NotNull Level level, @NotNull Player player,
                                                          @NotNull InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        Optional<EntityType<?>> type = getEntityType(stack);
        if (type.isEmpty()) {
            return InteractionResultHolder.pass(stack);
        }
        String id = carrierId(type.get());
        if (id != null) {
            InteractionResult result = launchCarrier(level, player, stack, type.get(), id);
            return new InteractionResultHolder<>(result, stack);
        }
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(player.getBlockReach()));
        DeckCollisions.Hit deck = DeckCollisions.clip(level, eye, end, player);
        if (deck == null || !deck.fromAbove) {
            return InteractionResultHolder.pass(stack);
        }
        return new InteractionResultHolder<>(placeOnDeck(level, player, stack, type.get(), deck), stack);
    }

    private InteractionResult placeOnDeck(Level level, Player player, ItemStack stack, EntityType<?> type,
                                          DeckCollisions.Hit deck) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        Vec3 at = deck.location;
        return spawn(level, player, stack, type, at.x, at.y + 0.5D, at.z, Mth.wrapDegrees(player.getYRot()), true);
    }

    private InteractionResult launchCarrier(Level level, Player player, ItemStack stack, EntityType<?> type,
                                            String id) {
        CarrierPlacement.Plan plan = CarrierPlacement.plan(level, player, id);
        if (plan == null) {
            return InteractionResult.PASS;
        }
        if (!plan.valid()) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.literal("Cannot launch here: " + plan.problem().text)
                        .withStyle(ChatFormatting.RED), true);
            }
            return InteractionResult.FAIL;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        DeckPose pose = plan.pose();
        return spawn(level, player, stack, type, pose.getX(), pose.getY(), pose.getZ(), pose.getYaw(), false);
    }

    /** The carrier id of [type], or null for every other vehicle. */
    private static String carrierId(EntityType<?> type) {
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(type);
        return id != null && CarrierPlacement.isCarrier(id.getPath()) ? id.getPath() : null;
    }

    private InteractionResult spawn(Level level, Player player, ItemStack stack, EntityType<?> type,
                                    double x, double y, double z, float yaw, boolean checkCollision) {
        VehicleEntity vehicle = VehicleItemLifecycleProviders.createFromType(LIFECYCLE_PROVIDER_ID, type, level);
        if (vehicle == null) {
            return InteractionResult.FAIL;
        }
        vehicle.moveTo(x, y, z, yaw, 0.0F);
        vehicle.yRotO = yaw;
        vehicle.xRotO = 0.0F;

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
        vehicle.moveTo(x, y, z, yaw, 0.0F);
        vehicle.yRotO = yaw;
        vehicle.xRotO = 0.0F;
        if ((checkCollision && !level.noCollision(vehicle, vehicle.getBoundingBox())) || !level.addFreshEntity(vehicle)) {
            return InteractionResult.FAIL;
        }

        level.gameEvent(player, GameEvent.ENTITY_PLACE, vehicle.position());
        if (player == null || !player.getAbilities().instabuild) {
            stack.shrink(1);
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
