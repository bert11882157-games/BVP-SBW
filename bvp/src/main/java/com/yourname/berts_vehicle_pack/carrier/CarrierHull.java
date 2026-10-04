package com.yourname.berts_vehicle_pack.carrier;

import com.atsuishio.superbwarfare.api.vehicle.deck.DeckPose;
import com.atsuishio.superbwarfare.api.vehicle.deck.DeckSurface;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Where a carrier hull may float, from its deck heightfield.
 * <ul>
 * <li>Placement: every underwater cell of the hull (2-block grid) needs water at the waterline and no solid block
 * down to its keel; every cell of the plan needs open space from the waterline up to its column top.</li>
 * <li>Sailing: the hull's underwater outline (1-block spacing) plus its plan outline above water must stay clear of
 * solid blocks; a step that would ground or hit land is refused.</li>
 * </ul>
 * Unloaded chunks count as blocked: a hull never sails or is placed into terrain it cannot see.
 */
public final class CarrierHull {
    public enum Problem {
        NEEDS_WATER("needs open water under the whole hull"),
        TOO_SHALLOW("water too shallow for the keel"),
        BLOCKED("something is in the way"),
        NOT_LOADED("part of the area is not loaded"),
        OCCUPIED("another carrier is there");

        public final String text;

        Problem(String text) {
            this.text = text;
        }
    }

    /** Local sample points: x, z, column top, column bottom. */
    private record Samples(double[] placeUnder, double[] placePlan, double[] sailUnder, double[] sailPlan) {
    }

    private static final Map<DeckSurface, Samples> SAMPLES = new WeakHashMap<>();
    /** The waterline is local Y 0; cells whose bottom is below this are the underwater hull. */
    private static final double UNDERWATER = -0.5;

    private CarrierHull() {
    }

    private static synchronized Samples samples(DeckSurface surface) {
        return SAMPLES.computeIfAbsent(surface, CarrierHull::build);
    }

    private static Samples build(DeckSurface surface) {
        Grid under2 = new Grid(), plan2 = new Grid(), underEdge = new Grid(), planEdge = new Grid();
        double cell = surface.getCellSize();
        int stride2 = Math.max(1, (int) Math.round(2.0 / cell));
        int stride1 = Math.max(1, (int) Math.round(1.0 / cell));
        for (int j = 0; j < surface.getSizeZ(); j++) {
            for (int i = 0; i < surface.getSizeX(); i++) {
                double x = surface.getMinX() + (i + 0.5) * cell;
                double z = surface.getMinZ() + (j + 0.5) * cell;
                int k = surface.column(x, z);
                if (k < 0) continue;
                double top = surface.topOf(k), bottom = surface.bottomOf(k);
                boolean under = bottom < UNDERWATER;
                boolean grid2 = i % stride2 == 0 && j % stride2 == 0;
                boolean grid1 = (i + j) % stride1 == 0;     // about one sample per block along any outline
                if (grid2) plan2.add(x, z, top, bottom);
                if (under && grid2) under2.add(x, z, top, bottom);
                if (grid1 && edge(surface, x, z, cell, false)) planEdge.add(x, z, top, bottom);
                if (under && grid1 && edge(surface, x, z, cell, true)) underEdge.add(x, z, top, bottom);
            }
        }
        return new Samples(under2.array(), plan2.array(), underEdge.array(), planEdge.array());
    }

    private static boolean edge(DeckSurface surface, double x, double z, double cell, boolean underwater) {
        double[][] around = {{cell, 0}, {-cell, 0}, {0, cell}, {0, -cell}};
        for (double[] d : around) {
            int k = surface.column(x + d[0], z + d[1]);
            if (k < 0 || (underwater && surface.bottomOf(k) >= UNDERWATER)) return true;
        }
        return false;
    }

    /** Null when the hull may be placed at [pose] (pose Y on the water surface), else what is wrong. */
    public static Problem placementProblem(Level level, DeckSurface surface, DeckPose pose) {
        Samples s = samples(surface);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        double[] under = s.placeUnder();
        for (int n = 0; n < under.length; n += 4) {
            double wx = pose.worldX(under[n], under[n + 1]), wz = pose.worldZ(under[n], under[n + 1]);
            p.set(wx, pose.getY() - 0.5, wz);
            if (!level.isLoaded(p)) return Problem.NOT_LOADED;
            if (!level.getFluidState(p).is(FluidTags.WATER)) return Problem.NEEDS_WATER;
            double keel = pose.getY() + under[n + 3] + 0.25;
            for (double y = pose.getY() - 1.5; y > keel - 1.0; y -= 2.0) {
                p.set(wx, Math.max(y, keel), wz);
                if (!open(level, p)) return Problem.TOO_SHALLOW;
            }
        }
        double[] plan = s.placePlan();
        for (int n = 0; n < plan.length; n += 4) {
            double wx = pose.worldX(plan[n], plan[n + 1]), wz = pose.worldZ(plan[n], plan[n + 1]);
            double top = pose.getY() + plan[n + 2];
            for (double y = pose.getY() + 0.5; y < top + 1.0; y += 3.0) {
                p.set(wx, Math.min(y, top), wz);
                if (!level.isLoaded(p)) return Problem.NOT_LOADED;
                if (!open(level, p)) return Problem.BLOCKED;
            }
        }
        return null;
    }

    /** True when the hull at [pose] touches no solid block (its underwater outline and its plan outline). */
    public static boolean clear(Level level, DeckSurface surface, DeckPose pose) {
        Samples s = samples(surface);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        double[] under = s.sailUnder();
        for (int n = 0; n < under.length; n += 4) {
            double wx = pose.worldX(under[n], under[n + 1]), wz = pose.worldZ(under[n], under[n + 1]);
            double keel = pose.getY() + under[n + 3] + 0.25;
            for (double y : new double[]{pose.getY() - 0.5, (pose.getY() + keel) * 0.5, keel}) {
                p.set(wx, y, wz);
                if (!level.isLoaded(p) || !open(level, p)) return false;
            }
        }
        double[] plan = s.sailPlan();
        for (int n = 0; n < plan.length; n += 4) {
            double wx = pose.worldX(plan[n], plan[n + 1]), wz = pose.worldZ(plan[n], plan[n + 1]);
            double top = pose.getY() + plan[n + 2];
            for (double y : new double[]{pose.getY() + 0.5, pose.getY() + 2.5, top - 0.5}) {
                if (y > top) continue;
                p.set(wx, y, wz);
                if (!level.isLoaded(p) || !open(level, p)) return false;
            }
        }
        return true;
    }

    private static boolean open(Level level, BlockPos p) {
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    private static final class Grid {
        private double[] values = new double[256];
        private int size;

        void add(double x, double z, double top, double bottom) {
            if (size + 4 > values.length) values = java.util.Arrays.copyOf(values, values.length * 2);
            values[size++] = x;
            values[size++] = z;
            values[size++] = top;
            values[size++] = bottom;
        }

        double[] array() {
            return java.util.Arrays.copyOf(values, size);
        }
    }
}
