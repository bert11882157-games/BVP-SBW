package com.yourname.berts_vehicle_pack.carrier;

import com.atsuishio.superbwarfare.api.vehicle.deck.DeckPose;
import com.atsuishio.superbwarfare.api.vehicle.deck.DeckRegistry;
import com.atsuishio.superbwarfare.api.vehicle.deck.DeckSurface;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Where a held carrier would be launched: the ship's centre on the water surface the player looks at (up to
 * [RANGE] blocks away), bow along the player's heading. The same plan drives the green/red hologram on the client
 * and the placement on the server, which re-checks it.
 */
public final class CarrierPlacement {
    public static final double RANGE = 192.0D;

    public record Plan(String carrierId, DeckSurface surface, DeckPose pose, CarrierHull.Problem problem) {
        public boolean valid() {
            return problem == null;
        }

        public Plan withProblem(CarrierHull.Problem found) {
            return new Plan(carrierId, surface, pose, found);
        }
    }

    private CarrierPlacement() {
    }

    /** The plan for [player] holding carrier [carrierId], or null when the player is not looking at anything. */
    public static Plan plan(Level level, Player player, String carrierId) {
        Plan aim = aim(level, player, carrierId);
        if (aim == null || aim.problem() != null) return aim;
        return aim.withProblem(problem(level, aim.surface(), aim.pose()));
    }

    /**
     * Where the player is aiming the carrier, with only the cheap water test done: the problem is NEEDS_WATER or
     * null (not yet checked). The hologram re-runs the full hull check a few times a second, not every frame.
     */
    public static Plan aim(Level level, Player player, String carrierId) {
        DeckSurface surface = BvpDeckSurfaces.get(carrierId);
        if (surface == null) return null;
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(RANGE));
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY,
                player));
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        float yaw = Mth.wrapDegrees(player.getYRot());
        BlockPos pos = hit.getBlockPos();
        if (!level.getFluidState(pos).is(FluidTags.WATER)) {
            Vec3 at = hit.getLocation();
            return new Plan(carrierId, surface, new DeckPose(at.x, at.y, at.z, yaw), CarrierHull.Problem.NEEDS_WATER);
        }
        // the surface of this water column
        BlockPos top = pos;
        while (top.getY() < level.getMaxBuildHeight() - 1 && level.getFluidState(top.above()).is(FluidTags.WATER)) {
            top = top.above();
        }
        FluidState fluid = level.getFluidState(top);
        double waterline = top.getY() + fluid.getHeight(level, top);
        DeckPose pose = new DeckPose(hit.getLocation().x, waterline, hit.getLocation().z, yaw);
        return new Plan(carrierId, surface, pose, null);
    }

    public static CarrierHull.Problem problem(Level level, DeckSurface surface, DeckPose pose) {
        if (!DeckRegistry.near(level, pose.envelope(surface).inflate(2.0D)).isEmpty()) {
            return CarrierHull.Problem.OCCUPIED;
        }
        return CarrierHull.placementProblem(level, surface, pose);
    }

    /** True when [carrierId] names a vehicle with a deck (the pack's carriers). */
    public static boolean isCarrier(String carrierId) {
        return BvpDeckSurfaces.get(carrierId) != null;
    }
}
