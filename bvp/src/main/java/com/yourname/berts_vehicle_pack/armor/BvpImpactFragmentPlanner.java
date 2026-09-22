package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactDisposition;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactPresentationOutcome;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Deterministic fragment calculation. No entity, world, renderer, or resource-loader access. */
public final class BvpImpactFragmentPlanner {
    private static final double EPSILON_SQR = 1.0E-6D;
    private static final float BASE_SCALE = 2.5F / 3.0F;
    private static final Vec3 WORLD_UP = new Vec3(0, 1, 0);
    private static final Vec3 WORLD_X = new Vec3(1, 0, 0);

    public record Input(boolean acceptedCollision, ProjectileImpactDisposition disposition,
                        ProjectileImpactPresentationOutcome outcome, boolean fragment,
                        Vec3 incoming, Vec3 normal, double caliberMm, boolean cyclic145,
                        boolean incendiary145, int rocketCount, long seed) {}
    public record Fragment(Vec3 direction, float speedBlocksPerTick, int lifetimeTicks, long sequence) {}
    public record Plan(float renderScale, List<Fragment> fragments) {
        public Plan { fragments = List.copyOf(fragments); }
    }

    private BvpImpactFragmentPlanner() {}

    public static Plan plan(Input input) {
        boolean ricochet = input.outcome() == ProjectileImpactPresentationOutcome.RICOCHET;
        boolean nonPenetration = ricochet || input.outcome() == ProjectileImpactPresentationOutcome.NON_PENETRATION;
        if (!input.acceptedCollision() || input.fragment() || !nonPenetration
                || input.disposition() == ProjectileImpactDisposition.PASS && !ricochet
                || !finite(input.incoming()) || input.incoming().m_82556_() <= EPSILON_SQR) {
            return new Plan(0f, List.of());
        }
        int count;
        float scale;
        if (input.cyclic145()) {
            if (!input.incendiary145()) return new Plan(0f, List.of());
            count = Math.max(1, (int) Math.ceil(genericCount(30.0, input.seed()) * 0.5F));
            scale = scale(30.0) * 0.5F;
        } else if (input.rocketCount() > 0) {
            count = input.rocketCount();
            scale = scale(input.caliberMm());
        } else if (!Double.isFinite(input.caliberMm()) || input.caliberMm() <= 12.7D) {
            return new Plan(0f, List.of());
        } else if (input.caliberMm() <= 23.0D) {
            long choice = mixSeed(input.seed());
            if ((choice & 3L) != 0L) return new Plan(0f, List.of());
            count = 1 + (int) ((choice >>> 2) & 1L);
            scale = BASE_SCALE * 0.15F;
        } else {
            count = genericCount(input.caliberMm(), input.seed());
            scale = scale(input.caliberMm());
        }
        // Reject invalid or pathological configuration before allocating an unbounded fan.
        if (count <= 0 || count > 256 || !Float.isFinite(scale)) return new Plan(0f, List.of());
        return fan(input.incoming(), input.normal(), count, scale, input.seed());
    }

    public static Plan fan(Vec3 incomingDirection, Vec3 surfaceNormal, int count, float scale, long seed) {
        if (count <= 0 || count > 256 || !finite(incomingDirection)
                || incomingDirection.m_82556_() <= EPSILON_SQR) return new Plan(0f, List.of());
        Vec3 incoming = normalized(incomingDirection);
        Vec3 normal = normalized(surfaceNormal);
        if (incoming.m_82526_(normal) > 0.0D) normal = normal.m_82490_(-1.0D);
        Vec3 reflected = incoming.m_82546_(normal.m_82490_(2.0D * incoming.m_82526_(normal)));
        reflected = reflected.m_82526_(reflected) <= EPSILON_SQR ? normal : reflected.m_82541_();
        Vec3 tangentU = orthogonal(normal);
        Vec3 tangentV = normal.m_82537_(tangentU).m_82541_();
        double incidence = Math.abs(incoming.m_82526_(normal));
        Random random = new Random(seed);
        List<Fragment> fragments = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            double theta = random.nextDouble() * Math.PI * 2.0D;
            double fanSpread = (0.18D + random.nextDouble() * 0.48D) * (0.65D + 0.35D * incidence);
            Vec3 fanDirection = reflected.m_82549_(tangentU.m_82490_(Math.cos(theta) * fanSpread))
                    .m_82549_(tangentV.m_82490_(Math.sin(theta) * fanSpread)).m_82541_();
            Vec3 direction = fanDirection;
            double outward = direction.m_82526_(normal);
            // Grazing hits retain forward travel while every fragment clears the surface.
            if (outward < 0.22D) direction = direction.m_82549_(normal.m_82490_(0.22D - outward)).m_82541_();
            double speed = 0.85D + random.nextDouble() * (1.65D - 0.85D);
            int lifetime = 2 * (5 + random.nextInt(8 - 5));
            fragments.add(new Fragment(direction, (float) speed, lifetime,
                    seed ^ (0x9E3779B97F4A7C15L * (index + 1L))));
        }
        return new Plan(Float.isFinite(scale) && scale > 0f ? scale : 0.1f, fragments);
    }

    /** Small, fast spherical burst; real terrain/entity damage is resolved by the server ray fan. */
    public static Plan heavyWarhead(long seed) {
        Random random = new Random(seed);
        List<Fragment> fragments = new ArrayList<>(64);
        double phase = random.nextDouble() * Math.PI * 2;
        for (int i = 0; i < 64; i++) {
            double y = 1 - 2 * (i + 0.5) / 64, r = Math.sqrt(1-y*y);
            double angle = i * Math.PI * (3-Math.sqrt(5)) + phase;
            fragments.add(new Fragment(new Vec3(Math.cos(angle)*r,y,Math.sin(angle)*r),
                    4 + random.nextFloat()*2, 4, seed ^ i));
        }
        return new Plan(0.10F, fragments);
    }

    private static int genericCount(double caliber, long seed) {
        return Math.max(1, (int) Math.ceil((4 + new Random(seed).nextInt(3)) * factor(caliber)));
    }
    private static float scale(double caliber) {
        return !Double.isFinite(caliber) ? BASE_SCALE * 0.15F
                : (float) Math.max(BASE_SCALE * 0.15D, BASE_SCALE * factor(caliber));
    }
    private static double factor(double caliber) {
        double steps = (caliber - 125.0D) / 10.0D;
        return Math.max(0.15D, steps < 0.0D ? Math.pow(0.935D, -steps) : Math.pow(1.065D, steps));
    }
    private static long mixSeed(long seed) {
        long value = seed ^ (seed >>> 30);
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.f_82479_)
                && Double.isFinite(value.f_82480_) && Double.isFinite(value.f_82481_);
    }
    private static Vec3 normalized(Vec3 value) {
        return !finite(value) || value.m_82556_() <= EPSILON_SQR ? WORLD_UP : value.m_82541_();
    }
    private static Vec3 orthogonal(Vec3 direction) {
        Vec3 reference = Math.abs(direction.f_82480_) < 0.95D ? WORLD_UP : WORLD_X;
        Vec3 perpendicular = reference.m_82537_(direction);
        return perpendicular.m_82556_() <= EPSILON_SQR ? WORLD_X : perpendicular.m_82541_();
    }
}
