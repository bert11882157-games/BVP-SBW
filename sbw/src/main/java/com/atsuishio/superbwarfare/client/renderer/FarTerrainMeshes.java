package com.atsuishio.superbwarfare.client.renderer;

import com.atsuishio.superbwarfare.client.FarTerrainClient;
import com.atsuishio.superbwarfare.api.vehicle.render.FarTerrainVisibility;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

/** Bounded native terrain fallback, sharing the vehicle pass's projection and depth buffer. */
public final class FarTerrainMeshes {
    private static final long MAX_BYTES = 192L * 1024 * 1024;
    private static long bytes;
    private static final Map<Long, ChunkMesh> meshes = new Long2ObjectLinkedOpenHashMap<>();
    private static final ArrayDeque<BufferBuilder> builderPool = new ArrayDeque<>();
    private static int allocatedBuilders;
    private static int pendingSections;
    private static int budgetBlocked;
    private static Set<FarTerrainVisibility.Section> required = Set.of();
    private static final List<RenderType> renderLayers = RenderType.chunkBufferLayers();
    private static final List<ArrayList<DrawEntry>> drawLayers = renderLayers.stream()
            .map(ignored -> new ArrayList<DrawEntry>()).toList();
    private record DrawEntry(int layer, VertexBuffer buffer, AABB bounds,
                             FarTerrainVisibility.Section section) {}
    private static final class SectionMesh implements AutoCloseable {
        final int y;
        final Map<RenderType, VertexBuffer> layers;
        final int bytes;
        final List<DrawEntry> draws = new ArrayList<>();

        SectionMesh(int y, Map<RenderType, VertexBuffer> layers, int bytes) {
            this.y = y;
            this.layers = layers;
            this.bytes = bytes;
        }

        void register(long key) {
            int x = ChunkPos.getX(key) << 4, z = ChunkPos.getZ(key) << 4;
            var bounds = new AABB(x, y, z, x + 16, y + 16, z + 16);
            var section = new FarTerrainVisibility.Section(key, y >> 4);
            for (var entry : layers.entrySet()) {
                int layer = renderLayers.indexOf(entry.getKey());
                if (layer < 0) continue;
                var draw = new DrawEntry(layer, entry.getValue(), bounds, section);
                draws.add(draw);
                drawLayers.get(layer).add(draw);
            }
        }

        @Override public void close() {
            for (var draw : draws) drawLayers.get(draw.layer).remove(draw);
            draws.clear();
            layers.values().forEach(VertexBuffer::close);
        }
    }
    private static final class ChunkMesh {
        final Map<Integer, SectionMesh> sections = new LinkedHashMap<>();
        final Map<Integer, Long> retryAfter = new HashMap<>();
        final Set<Integer> dirty = new HashSet<>();
    }

    /** Retain uploaded cover until a replacement section has successfully compiled. */
    public static void refresh(long key, int sectionY) {
        ChunkMesh mesh = meshes.get(key);
        if (mesh != null && mesh.sections.containsKey(sectionY)) mesh.dirty.add(sectionY);
    }

    private static boolean needsBuild(FarTerrainVisibility.Section section) {
        var mesh = meshes.get(section.getChunk());
        return !ready(section) || (mesh != null && mesh.dirty.contains(section.getY()));
    }

    private static void replace(long key, ChunkMesh mesh, int y, SectionMesh replacement) {
        SectionMesh previous = mesh.sections.put(y, replacement);
        bytes += replacement.bytes;
        if (previous != null) { previous.close(); bytes -= previous.bytes; }
        replacement.register(key);
        mesh.dirty.remove(y);
        mesh.retryAfter.remove(y);
    }

    public static void invalidate(long key) {
        ChunkMesh old = meshes.remove(key);
        if (old != null) for (SectionMesh section : old.sections.values()) { section.close(); bytes -= section.bytes; }
    }

    public static void clear() {
        for (long key : List.copyOf(meshes.keySet())) invalidate(key);
        bytes = 0;
        required = Set.of();
        pendingSections = 0;
        budgetBlocked = 0;
    }

    public static boolean ready(long key) {
        var data = FarTerrainClient.terrain(key);
        return data != null && required.stream().filter(s -> s.getChunk() == key)
                .allMatch(s -> ready(s));
    }

    public static boolean ready(FarTerrainVisibility.Section section) {
        var data = FarTerrainClient.terrain(section.getChunk());
        if (data == null) return false;
        int index = section.getY() - data.getChunk().getMinSection();
        if (index < 0 || index >= data.getChunk().getSectionsCount()) return false;
        // Received air cannot obscure anything and needs no GPU allocation. Requiring a
        // queued empty mesh made visible vehicles wait behind unrelated solid sections.
        if (data.getChunk().getSection(index).hasOnlyAir()) return true;
        var mesh = meshes.get(section.getChunk());
        return mesh != null && mesh.sections.containsKey(section.getY());
    }

    public static long allocatedBytes() { return bytes; }
    public static int pendingSections() { return pendingSections; }
    public static int budgetBlockedSections() { return budgetBlocked; }
    public static int failedSections() {
        return meshes.values().stream().mapToInt(m -> m.retryAfter.size()).sum();
    }

    /** Kept for renderer integrations; the owning far pass supplies the current sightline sections. */
    public static void prepare() { prepare(required); }

    /** Build possible occluders first, never the entire underground column before visible cover. */
    public static void prepare(Set<FarTerrainVisibility.Section> needed) {
        required = needed;
        budgetBlocked = 0;
        pendingSections = (int) needed.stream().filter(FarTerrainMeshes::needsBuild).count();
        long deadline = System.nanoTime() + 2_000_000L;
        int compiled = 0;
        for (var requested : needed) {
            if (!needsBuild(requested)) continue;
            var data = FarTerrainClient.terrain(requested.getChunk());
            if (data == null) continue;
            LevelChunk chunk = data.getChunk();
            ChunkMesh mesh = meshes.computeIfAbsent(chunk.getPos().toLong(), ignored -> new ChunkMesh());
            int y = requested.getY();
            if (mesh.retryAfter.getOrDefault(y, 0L) > System.nanoTime()) continue;
            int index = y - chunk.getMinSection();
            if (index < 0 || index >= chunk.getSectionsCount()) continue;
            if (chunk.getSection(index).hasOnlyAir()) {
                replace(requested.getChunk(), mesh, y, new SectionMesh(y << 4, Map.of(), 0));
                pendingSections--;
                continue;
            }
            if (compiled >= 1 || System.nanoTime() > deadline) return;
            try {
                SectionMesh section = compile(chunk, index);
                compiled++;
                var previous = mesh.sections.get(y);
                int additionalBytes = section.bytes - (previous == null ? 0 : previous.bytes);
                if (bytes + additionalBytes > MAX_BYTES) evictUnused(additionalBytes);
                if (bytes + additionalBytes > MAX_BYTES) {
                    section.close();
                    budgetBlocked++;
                    continue;
                }
                replace(requested.getChunk(), mesh, y, section);
                pendingSections--;
            } catch (RuntimeException failure) {
                mesh.retryAfter.put(y, System.nanoTime() + 3_000_000_000L);
                LoggerFactory.getLogger(FarTerrainMeshes.class).warn("Far terrain section rebuild failed; retaining previous cover and retrying", failure);
            }
        }
    }

    private static void evictUnused(int incomingBytes) {
        for (var chunk : meshes.entrySet()) {
            var iterator = chunk.getValue().sections.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (required.contains(new FarTerrainVisibility.Section(chunk.getKey(), entry.getKey()))) continue;
                entry.getValue().close();
                bytes -= entry.getValue().bytes;
                iterator.remove();
                if (bytes + incomingBytes <= MAX_BYTES) return;
            }
        }
    }

    private static SectionMesh compile(LevelChunk chunk, int sectionIndex) {
        int y0 = chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        var dispatcher = Minecraft.getInstance().getBlockRenderer();
        var view = FarTerrainClient.INSTANCE;
        var builders = new HashMap<RenderType, BufferBuilder>();
        var buffers = new HashMap<RenderType, VertexBuffer>();
        var pose = new PoseStack();
        var random = RandomSource.create();
        var pos = new BlockPos.MutableBlockPos();
        int size = 0;
        try {
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                pos.set(x0 + x, y0 + y, z0 + z);
                var state = chunk.getBlockState(pos);
                if (state.isAir()) continue;
                if (state.getRenderShape() == RenderShape.MODEL) {
                    var model = dispatcher.getBlockModel(state);
                    var blockEntity = chunk.getBlockEntities().get(pos);
                    var modelData = model.getModelData(view, pos, state,
                            blockEntity == null ? ModelData.EMPTY : blockEntity.getModelData());
                    for (var layer : model.getRenderTypes(state, random, modelData)) {
                        var builder = builder(builders, layer);
                        pose.pushPose();
                        pose.translate(x, y, z);
                        dispatcher.renderBatched(state, pos, view, pose, builder, true, random, modelData, layer);
                        pose.popPose();
                    }
                }
                var fluid = state.getFluidState();
                if (!fluid.isEmpty()) {
                    dispatcher.renderLiquid(pos, view, builder(builders, ItemBlockRenderTypes.getRenderLayer(fluid)), state, fluid);
                }
            }
            for (var entry : builders.entrySet()) {
                BufferBuilder.RenderedBuffer rendered = entry.getValue().end();
                size += rendered.vertexBuffer().remaining() + rendered.indexBuffer().remaining();
                var buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                buffers.put(entry.getKey(), buffer);
                buffer.bind();
                buffer.upload(rendered);
            }
            return new SectionMesh(y0, buffers, size);
        } catch (RuntimeException failure) {
            buffers.values().forEach(VertexBuffer::close);
            throw failure;
        } finally {
            VertexBuffer.unbind();
            for (var builder : builders.values()) {
                if (builder.building()) builder.end().release();
                builder.clear();
                builderPool.addLast(builder);
            }
        }
    }

    private static BufferBuilder builder(Map<RenderType, BufferBuilder> builders, RenderType layer) {
        return builders.computeIfAbsent(layer, ignored -> {
            // BufferBuilder owns native storage; reuse it instead of allocating one per section.
            if (builderPool.isEmpty() && allocatedBuilders >= 16) {
                throw new IllegalStateException("Too many far terrain render layers");
            }
            BufferBuilder builder;
            if (builderPool.isEmpty()) {
                builder = new BufferBuilder(65536);
                allocatedBuilders++;
            } else builder = builderPool.removeFirst();
            builder.clear();
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            return builder;
        });
    }

    public static boolean draw(PoseStack pose, Matrix4f projection, Vec3 camera, Frustum frustum, float partialTick) {
        var modelView = new Matrix4f();
        for (int layerIndex = 0; layerIndex < renderLayers.size(); layerIndex++) {
            var draws = drawLayers.get(layerIndex);
            if (draws.isEmpty()) continue;
            var layer = renderLayers.get(layerIndex);
            layer.setupRenderState();
            try {
                var shader = RenderSystem.getShader();
                if (shader == null) return false;
                if (shader.CHUNK_OFFSET != null) shader.CHUNK_OFFSET.set(0F, 0F, 0F);
                // Only actual uploaded buffers enter this list. Empty sections and absent layers
                // never create per-frame map lookups, iterators or temporary bounds.
                for (int index = 0; index < draws.size(); index++) {
                    var draw = draws.get(index);
                    if (!required.contains(draw.section) || !frustum.isVisible(draw.bounds)) continue;
                    modelView.set(pose.last().pose()).translate((float) (draw.bounds.minX - camera.x),
                            (float) (draw.bounds.minY - camera.y), (float) (draw.bounds.minZ - camera.z));
                    draw.buffer.bind();
                    draw.buffer.drawWithShader(modelView, projection, shader);
                }
            } finally {
                VertexBuffer.unbind();
                layer.clearRenderState();
            }
        }
        var mc = Minecraft.getInstance();
        var dispatcher = mc.getBlockEntityRenderDispatcher();
        var source = mc.renderBuffers().bufferSource();
        for (var data : FarTerrainClient.chunks()) for (var entity : data.getChunk().getBlockEntities().values()) {
            var pos = entity.getBlockPos();
            if (!frustum.isVisible(new AABB(pos).inflate(1))) continue;
            // Ordinary block-entity rendering still owns chunks already present in ClientLevel.
            if (mc.level != null && mc.level.hasChunkAt(pos)) continue;
            pose.pushPose();
            try {
                pose.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
                var renderer = dispatcher.getRenderer(entity);
                if (renderer != null) renderer.render(entity, partialTick, pose, source,
                        LevelRenderer.getLightColor(FarTerrainClient.INSTANCE, pos), OverlayTexture.NO_OVERLAY);
            } catch (RuntimeException failure) {
                LoggerFactory.getLogger(FarTerrainMeshes.class).warn("Far terrain block entity failed; hiding vehicles", failure);
                return false;
            } finally { pose.popPose(); }
        }
        source.endBatch();
        return true;
    }
}
