package com.atsuishio.superbwarfare.mixins;

import com.google.common.collect.MapMaker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import software.bernie.geckolib.core.animation.AnimationProcessor;
import software.bernie.geckolib.core.state.BoneSnapshot;

import java.util.Map;

/**
 * GeckoLib's tickAnimation starts every frame by checking, bone by bone, that the animatable's snapshot map has an
 * entry for each registered bone (updateBoneSnapshots only ever adds missing entries). A vehicle has hundreds of
 * bones, so this was a string-keyed lookup per bone per vehicle per frame. Once a full pass has run for a snapshot
 * map, it holds every bone until this processor's bone set changes (registerGeoBone / setActiveModel) or the map
 * shrinks, so the pass is skipped until then.
 */
@Mixin(value = AnimationProcessor.class, remap = false)
public class AnimationProcessorSnapshotMixin {
    @Unique private int sbw$boneRevision;
    /** Snapshot maps (by identity, weakly held) that hold every bone: {bone revision, map size after the pass}. */
    @Unique private final Map<Map<String, BoneSnapshot>, int[]> sbw$completeSnapshots = new MapMaker().weakKeys().makeMap();

    @Inject(method = {"registerGeoBone", "setActiveModel"}, at = @At("HEAD"))
    private void sbw$bonesChanged(CallbackInfo ci) {
        sbw$boneRevision++;
    }

    @Inject(method = "updateBoneSnapshots", at = @At("HEAD"), cancellable = true)
    private void sbw$skipCompleteSnapshots(Map<String, BoneSnapshot> snapshots,
                                           CallbackInfoReturnable<Map<String, BoneSnapshot>> cir) {
        int[] complete = sbw$completeSnapshots.get(snapshots);
        if (complete != null && complete[0] == sbw$boneRevision && snapshots.size() >= complete[1]) {
            cir.setReturnValue(snapshots);
        }
    }

    @Inject(method = "updateBoneSnapshots", at = @At("RETURN"))
    private void sbw$markComplete(Map<String, BoneSnapshot> snapshots,
                                  CallbackInfoReturnable<Map<String, BoneSnapshot>> cir) {
        int[] complete = sbw$completeSnapshots.computeIfAbsent(snapshots, key -> new int[2]);
        complete[0] = sbw$boneRevision;
        complete[1] = snapshots.size();
    }
}
