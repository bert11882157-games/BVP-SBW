package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.api.vehicle.deck.DeckPose;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.carrier.CarrierPlacement;
import com.yourname.berts_vehicle_pack.item.BvpVehicleItem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Holding a carrier shows the ship as a hologram where it would be launched: green when it fits (open water deep
 * enough for the keel, nothing in the way), red when it does not, with the reason on the action bar. The hologram
 * is the carrier's own mesh, posed exactly as the entity renderer will draw it (data frame through DeckPose).
 */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID, value = Dist.CLIENT)
public final class CarrierPlacementPreview {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    /** Mesh quads per carrier: 4 vertices x (x, y, z) in the data frame, blocks. */
    private static final Map<String, float[]> MESHES = new HashMap<>();
    /** The full hull check (thousands of block lookups) runs at most every few ticks, sooner after a big move. */
    private static final int CHECK_INTERVAL = 4;
    private static final int CHECK_INTERVAL_MOVING = 2;
    private static CarrierPlacement.Plan plan;
    private static CarrierPlacement.Plan checked;
    private static int checkedTick = Integer.MIN_VALUE;
    private static int lastMessageTick = -100;
    private static boolean lastValid;

    private CarrierPlacementPreview() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            plan = null;
            return;
        }
        String id = heldCarrier(player.getMainHandItem());
        if (id == null) {
            plan = null;
            checked = null;
            return;
        }
        CarrierPlacement.Plan aim = CarrierPlacement.aim(minecraft.level, player, id);
        if (aim == null) {
            plan = null;
            return;
        }
        if (aim.problem() == null) {
            int age = player.tickCount - checkedTick;
            boolean stale = checked == null || !checked.carrierId().equals(id) || age < 0 || age >= CHECK_INTERVAL
                    || (age >= CHECK_INTERVAL_MOVING && movedFar(checked.pose(), aim.pose()));
            if (stale) {
                checked = aim.withProblem(CarrierPlacement.problem(minecraft.level, aim.surface(), aim.pose()));
                checkedTick = player.tickCount;
            }
            aim = aim.withProblem(checked.problem());
        }
        plan = aim;
        boolean valid = plan.valid();
        if (player.tickCount - lastMessageTick >= 20 || valid != lastValid) {
            lastMessageTick = player.tickCount;
            lastValid = valid;
            player.displayClientMessage(valid
                    ? Component.literal("Carrier fits here - use to launch").withStyle(ChatFormatting.GREEN)
                    : Component.literal("Carrier: " + plan.problem().text).withStyle(ChatFormatting.RED), true);
        }
    }

    private static boolean movedFar(DeckPose a, DeckPose b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz > 16.0D || Math.abs(net.minecraft.util.Mth.wrapDegrees(a.getYaw() - b.getYaw())) > 5.0F
                || Math.abs(a.getY() - b.getY()) > 0.5D;
    }

    private static String heldCarrier(ItemStack stack) {
        if (!(stack.getItem() instanceof BvpVehicleItem)) return null;
        Optional<ResourceLocation> id = BvpVehicleItem.getEntityTypeId(stack);
        if (id.isEmpty() || !CarrierPlacement.isCarrier(id.get().getPath())) return null;
        return id.get().getPath();
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        CarrierPlacement.Plan current = plan;
        if (current == null) return;
        float[] quads = mesh(current.carrierId());
        if (quads == null || quads.length == 0) return;
        DeckPose pose = current.pose();
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        Matrix4f matrix = poseStack.last().pose();
        float r = current.valid() ? 0.25F : 1.0F;
        float g = current.valid() ? 1.0F : 0.22F;
        float b = current.valid() ? 0.40F : 0.22F;
        float a = 0.32F;
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(RenderType.debugQuads());
        double y0 = pose.getY() - camera.y;
        for (int n = 0; n < quads.length; n += 3) {
            double lx = quads[n], ly = quads[n + 1], lz = quads[n + 2];
            float x = (float) (pose.worldX(lx, lz) - camera.x);
            float y = (float) (y0 + ly);
            float z = (float) (pose.worldZ(lx, lz) - camera.z);
            // lighter tops, darker sides: the hull reads as a shape, not a flat silhouette
            float shade = (n / 12) % 3 == 0 ? 1.0F : 0.82F;
            consumer.vertex(matrix, x, y, z).color(r * shade, g * shade, b * shade, a).endVertex();
        }
        buffers.endBatch(RenderType.debugQuads());
    }

    private static float[] mesh(String id) {
        return MESHES.computeIfAbsent(id, CarrierPlacementPreview::loadMesh);
    }

    /** The carrier's poly mesh as quads (triangles repeat their last vertex) in the data frame. */
    private static float[] loadMesh(String id) {
        ResourceLocation location = new ResourceLocation(BertsVehiclePack.MODID, "custom_geo/" + id + ".geo.json");
        try {
            Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
            if (resource.isEmpty()) return new float[0];
            JsonObject root;
            try (var reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
                root = GSON.fromJson(reader, JsonObject.class);
            }
            JsonObject geometry = root.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
            float[] out = new float[0];
            int size = 0;
            for (var boneElement : geometry.getAsJsonArray("bones")) {
                JsonObject bone = boneElement.getAsJsonObject();
                if (!bone.has("poly_mesh")) continue;
                JsonObject mesh = bone.getAsJsonObject("poly_mesh");
                JsonArray positions = mesh.getAsJsonArray("positions");
                JsonArray polys = mesh.getAsJsonArray("polys");
                if (out.length < size + polys.size() * 12) {
                    out = java.util.Arrays.copyOf(out, size + polys.size() * 12);
                }
                for (var polyElement : polys) {
                    JsonArray poly = polyElement.getAsJsonArray();
                    for (int v = 0; v < 4; v++) {
                        int corner = Math.min(v, poly.size() - 1);
                        JsonArray p = positions.get(poly.get(corner).getAsJsonArray().get(0).getAsInt())
                                .getAsJsonArray();
                        // geo px (+X left, +Y up, nose -Z) -> data frame blocks (+X left, +Y up, nose +Z)
                        out[size++] = p.get(0).getAsFloat() / 16.0F;
                        out[size++] = p.get(1).getAsFloat() / 16.0F;
                        out[size++] = -p.get(2).getAsFloat() / 16.0F;
                    }
                }
            }
            return java.util.Arrays.copyOf(out, size);
        } catch (Exception exception) {
            LOGGER.warn("[BVP Carrier] Placement hologram for {} could not load {}", id, location,
                    exception);
            return new float[0];
        }
    }
}
