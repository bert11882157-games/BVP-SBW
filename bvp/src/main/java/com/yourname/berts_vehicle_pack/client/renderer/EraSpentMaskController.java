package com.yourname.berts_vehicle_pack.client.renderer;

import com.example.sbwmeshloader.core.PolyMeshModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorBox;
import com.yourname.berts_vehicle_pack.armor.EraBrickIds;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

final class EraSpentMaskController {
    private static final Map<PolyMeshModel, ProfileMaskCache> CACHES = new WeakHashMap<>();
    private static final String SPENT_BONE_PREFIX = "bvpEraSpent_";

    private EraSpentMaskController() {
    }

    static void apply(ArmoredVehicleEntity entity, PolyMeshModel loadedModel) {
        if (entity == null || loadedModel == null) {
            return;
        }
        String profileId = entity.getArmorProfileId();
        ArmorProfiles.ArmorProfile profile = ArmorProfiles.get(profileId);
        if (profile.eraBoxes.isEmpty()) {
            return;
        }
        ProfileMaskCache cache = CACHES.get(loadedModel);
        if (cache == null || !cache.matchesProfile(profileId)) {
            cache = ProfileMaskCache.create(profileId, profile.eraBoxes, loadedModel);
            CACHES.put(loadedModel, cache);
        }
        cache.apply(entity.getBvpSpentEraBricks());
    }

    private static final class ProfileMaskCache {
        private final String profileId;
        private final Map<String, BedrockBone> spentBoneByBrickId;
        private String appliedState;
        private Set<String> appliedSpentBrickIds = Collections.emptySet();

        private ProfileMaskCache(String profileId, Map<String, BedrockBone> spentBoneByBrickId) {
            this.profileId = profileId;
            this.spentBoneByBrickId = spentBoneByBrickId;
        }

        static ProfileMaskCache create(String profileId, Iterable<ArmorBox> eraBoxes, PolyMeshModel loadedModel) {
            Map<String, BedrockBone> spentBoneByBrickId = new HashMap<>();
            for (ArmorBox eraBox : eraBoxes) {
                BedrockBone spentBone = loadedModel.getBone(SPENT_BONE_PREFIX + EraBrickIds.boneSuffix(eraBox.name));
                if (spentBone != null) {
                    String stateId = EraBrickIds.stateId(eraBox.name);
                    if (!stateId.isEmpty()) {
                        spentBoneByBrickId.put(stateId, spentBone);
                    }
                }
            }
            return new ProfileMaskCache(profileId, spentBoneByBrickId);
        }

        boolean matchesProfile(String id) {
            return Objects.equals(this.profileId, id);
        }

        void apply(String rawState) {
            String state = rawState == null ? "" : rawState;
            if (state.equals(this.appliedState)) {
                return;
            }
            Set<String> desiredSpent = EraBrickIds.parseStateIds(state);
            for (Map.Entry<String, BedrockBone> entry : this.spentBoneByBrickId.entrySet()) {
                boolean wasSpent = this.appliedSpentBrickIds.contains(entry.getKey());
                boolean shouldBeSpent = desiredSpent.contains(entry.getKey());
                if (this.appliedState == null || wasSpent != shouldBeSpent) {
                    if (shouldBeSpent) {
                        RendererBones.resetPosition(entry.getValue());
                    } else {
                        RendererBones.hide(entry.getValue());
                    }
                }
            }
            this.appliedState = state;
            this.appliedSpentBrickIds = desiredSpent;
        }
    }
}
