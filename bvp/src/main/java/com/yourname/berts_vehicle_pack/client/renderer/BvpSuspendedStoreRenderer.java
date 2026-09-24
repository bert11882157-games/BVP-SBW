package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.client.aircraft.AircraftArmamentClient;
import com.atsuishio.superbwarfare.client.aircraft.AircraftArmamentSnapshot;
import com.atsuishio.superbwarfare.client.aircraft.AircraftDefinitionView;
import com.atsuishio.superbwarfare.client.aircraft.AircraftMountView;
import com.atsuishio.superbwarfare.client.aircraft.AircraftStoreView;
import com.atsuishio.superbwarfare.client.aircraft.AircraftStoreItemRenderer;
import com.atsuishio.superbwarfare.client.aircraft.AircraftMountPresentation;
import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.atsuishio.superbwarfare.api.vehicle.flight.AircraftWreckBreakup;
import com.atsuishio.superbwarfare.client.renderer.AircraftDetachedWings;
import com.example.sbwmeshloader.core.PolyMeshModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/** Exact authored groups and static external stores; no entity/weapon/effect renderer is invoked. */
final class BvpSuspendedStoreRenderer {
    private static final int MAX_EXTERNAL_MODELS = 64;
    // Keep ownership aligned with BaseVehicleRenderer's global lifetime; never orphan loader instances.
    private static final Map<String, StoreAsset> ASSETS = new LinkedHashMap<>();
    private PolyMeshModel model;
    private EntityType<?> type;
    private long catalogue = -1;
    private BedrockBone[] bones = new BedrockBone[0];
    private boolean[] previousVisibility = new boolean[0];
    private final Map<String, Map<String, int[]>> groups = new HashMap<>();
    private final Map<String, StoreAsset> modelBindings = new HashMap<>();
    private boolean applied;
    private record CapturedStore(Vec3 point, AircraftStoreView store, StoreAsset asset) {}

    private static boolean onWing(GeoVehicleEntity entity, Vec3 point, int side) {
        String id = side == 1 ? "superbwarfare:wing_left" : "superbwarfare:wing_right";
        var module = entity.computed().getAircraftSurfaceModules().stream()
                .filter(entry -> entry.getId().equals(id)).findFirst().orElse(null);
        if (module == null || module.getHitboxes().isEmpty()) return false;
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        for (var box : module.getHitboxes()) {
            minX = Math.min(minX, box.getMin().f_82479_);
            maxX = Math.max(maxX, box.getMax().f_82479_);
        }
        return point.f_82479_ >= minX - .15 && point.f_82479_ <= maxX + .15;
    }

    private StoreAsset asset(AircraftStoreView store) {
        if (store.getModel() == null || store.getTexture() == null) return null;
        if (!modelBindings.containsKey(store.getId())) {
            String key = store.getModel() + "|" + store.getTexture();
            StoreAsset candidate = ASSETS.get(key);
            if (candidate == null && ASSETS.size() < MAX_EXTERNAL_MODELS) {
                candidate = new StoreAsset(store.getModel(), store.getTexture());
                ASSETS.put(key, candidate);
            }
            modelBindings.put(store.getId(), candidate);
        }
        return modelBindings.get(store.getId());
    }

    /** Freeze loaded stores at separation; later firing or loadout edits cannot change debris. */
    AircraftDetachedWings.Visual captureWing(GeoVehicleEntity entity, int side, int light, float partialTick) {
        AircraftArmamentSnapshot state = AircraftArmamentClient.getVehicleSnapshot(entity);
        if (state == null) return null;
        var captured = new ArrayList<CapturedStore>();
        for (AircraftMountView pair : state.getDefinition().getMounts()) {
            if (pair.getInternal()) continue;
            String selected = state.getSelections().get(pair.getId());
            AircraftStoreView store = state.getStores().get(selected);
            if (store == null || pair.getGroups().containsKey(selected)) continue;
            var positions = state.rackPositions(pair, AircraftMountPresentation.speed(entity, partialTick));
            for (int index = 0; index < positions.size(); index++) {
                Vec3 point = positions.get(index);
                if (state.storePresent(pair, index) && onWing(entity, point, side))
                    captured.add(new CapturedStore(point, store, asset(store)));
            }
        }
        if (captured.isEmpty()) return null;
        var frozen = List.copyOf(captured);
        return (pose, buffers, impacted) -> {
            for (CapturedStore store : frozen) {
                PolyMeshModel mesh = store.asset == null ? null : store.asset.ready();
                if (mesh != null || (store.store.getModel() == null && store.store.getItem() != null))
                    mount(store.point, store.store, store.asset, mesh, pose, buffers, light, 1f);
            }
        };
    }

    void apply(GeoVehicleEntity entity, PolyMeshModel current) {
        restore();
        AircraftArmamentSnapshot state = AircraftArmamentClient.getVehicleSnapshot(entity);
        long revision = AircraftArmamentClient.getCatalogueRevision(entity.getUUID());
        if (current != model || entity.m_6095_() != type || (state != null && revision != catalogue)) {
            clear(); model = current; type = entity.m_6095_();
            bind(state == null ? null : state.getDefinition(), current, revision);
        } else if (catalogue < 0 && state != null) {
            bind(state.getDefinition(), current, revision);
        }
        for (int i = 0; i < bones.length; i++) {
            BedrockBone bone = bones[i];
            if (bone != null) { previousVisibility[i] = bone.visible; bone.visible = false; }
        }
        applied = true;
        if (state == null) return;
        for (var selection : state.getSelections().entrySet()) {
            Map<String, int[]> pair = groups.get(selection.getKey());
            int[] selected = pair == null ? null : pair.get(selection.getValue());
            if (selected == null) continue;
            for (int index : selected) bones[index].visible = previousVisibility[index];
        }
    }

    private void bind(AircraftDefinitionView definition, PolyMeshModel current, long revision) {
        var indices = new LinkedHashMap<String, Integer>();
        groups.clear(); modelBindings.clear();
        // Frozen generator contract, not a vehicle/store-name heuristic. Hide before any remote snapshot.
        current.getBoneMap().keySet().forEach(name -> {
            if (name.startsWith("suspended_")) indices.putIfAbsent(name, indices.size());
        });
        if (definition != null) {
            definition.getNeutralGroups().values().forEach(names -> names.forEach(name -> indices.putIfAbsent(name, indices.size())));
            for (AircraftMountView pair : definition.getMounts()) {
                pair.getGroups().values().forEach(names -> names.forEach(name -> indices.putIfAbsent(name, indices.size())));
            }
        }
        if (indices.size() > 4096) return;
        bones = new BedrockBone[indices.size()];
        previousVisibility = new boolean[bones.length];
        indices.forEach((name, index) -> bones[index] = current.getBone(name));
        if (definition == null) return;
        for (AircraftMountView pair : definition.getMounts()) {
            var stores = new HashMap<String, int[]>();
            for (var group : pair.getGroups().entrySet()) {
                int[] groupIndices = new int[group.getValue().size()];
                boolean valid = groupIndices.length > 0;
                for (int i = 0; i < groupIndices.length; i++) {
                    groupIndices[i] = indices.get(group.getValue().get(i));
                    valid &= bones[groupIndices[i]] != null;
                }
                if (valid) stores.put(group.getKey(), groupIndices);
            }
            groups.put(pair.getId(), stores);
        }
        catalogue = revision;
    }

    void restore() {
        if (!applied) return;
        for (int i = 0; i < bones.length; i++) if (bones[i] != null) bones[i].visible = previousVisibility[i];
        applied = false;
    }

    void clear() {
        restore(); model = null; type = null; catalogue = -1;
        bones = new BedrockBone[0]; previousVisibility = new boolean[0];
        groups.clear(); modelBindings.clear();
    }

    void render(GeoVehicleEntity entity, PoseStack pose, MultiBufferSource buffers, int light, float alpha, float partialTick) {
        AircraftArmamentSnapshot state = AircraftArmamentClient.getVehicleSnapshot(entity);
        if (state == null || alpha <= 0 || entity.getAircraftWreckImpactTime() >= 0) return;
        for (AircraftMountView pair : state.getDefinition().getMounts()) {
            if (pair.getInternal()) continue;
            String selected = state.getSelections().get(pair.getId());
            AircraftStoreView store = state.getStores().get(selected);
            if (store == null || pair.getGroups().containsKey(selected)) continue;
            StoreAsset asset = asset(store);
            PolyMeshModel mesh = asset == null ? null : asset.ready();
            if (mesh == null && (store.getModel() != null || store.getItem() == null)) continue;
            var positions = state.rackPositions(pair, AircraftMountPresentation.speed(entity, partialTick));
            int missing = AircraftWreckBreakup.mask(entity);
            for (int index = 0; index < positions.size(); index++) {
                Vec3 point = positions.get(index);
                boolean detached = ((missing & 1) != 0 && onWing(entity, point, 1)) ||
                        ((missing & 2) != 0 && onWing(entity, point, 2));
                if (!detached && state.storePresent(pair, index))
                    mount(positions.get(index), store, asset, mesh, pose, buffers, light, alpha);
            }
        }
    }

    private static void mount(Vec3 point, AircraftStoreView store, StoreAsset asset, PolyMeshModel mesh,
                              PoseStack pose, MultiBufferSource buffers, int light, float alpha) {
        pose.m_85836_();
        try {
            // Native HULL blocks to BVP's already-rotated model frame, matching live root offsets.
            pose.m_85837_(-point.f_82479_, point.f_82480_, -point.f_82481_);
            float scale = (float) store.getScale();
            pose.m_85841_(scale, scale, scale);
            if (mesh != null) {
                mesh.renderCutoutOnly(pose, buffers, asset.texture, light, alpha);
                mesh.renderTranslucentOnly(pose, buffers, asset.texture, light, alpha);
            } else if (alpha >= 1.0F) {
                AircraftStoreItemRenderer.render(store.getItem(), pose, buffers, light);
            }
        } finally { pose.m_85849_(); }
    }

    /** Asset-only use of the existing bounded parse/upload queue and atomic generation publication. */
    private static final class StoreAsset extends BaseVehicleRenderer<GeoVehicleEntity> {
        final ResourceLocation texture;
        long textureGeneration = -1;
        boolean texturePresent;
        StoreAsset(ResourceLocation model, ResourceLocation texture) {
            super(null, model, texture, "Authored suspended store");
            this.texture = texture;
        }
        PolyMeshModel ready() {
            long generation = currentResourceGeneration();
            if (textureGeneration != generation) {
                textureGeneration = generation;
                texturePresent = AircraftStoreItemRenderer.resourceExists(texture);
            }
            return texturePresent ? getOrLoadModel() : null;
        }
    }
}
