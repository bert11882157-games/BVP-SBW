package com.yourname.berts_vehicle_pack.armor;

import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Bounded admission for once-per-impact visual delivery, independent of client rendering. */
public final class ImpactFragmentAdmission<K, L> {
    private static final double SAME_IMPACT_DISTANCE_BLOCKS = 0.0625D;
    private final Map<K, Claim<L>> claims = new WeakHashMap<>();
    private record Claim<L>(L level, long tick, Object kind, Vec3 position) {}

    public synchronized boolean claim(K projectile, L level, long tick, Object kind, Vec3 position) {
        Claim<L> previous = claims.get(projectile);
        if (previous != null && previous.level() == level && previous.tick() == tick
                && previous.kind() == kind
                && previous.position().m_82554_(position) <= SAME_IMPACT_DISTANCE_BLOCKS) return false;
        while (claims.size() >= 256) {
            var iterator = claims.keySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
        claims.put(projectile, new Claim<>(level, tick, kind, position));
        return true;
    }

    /** Compatibility adapter that claims once before invoking a caller-supplied bounded consumer. */
    public <T> int commit(K projectile, L level, long tick, Object kind, Vec3 position,
                         List<T> fragments, Predicate<T> insert, Consumer<RuntimeException> onFailure) {
        if (fragments.isEmpty() || !claim(projectile, level, tick, kind, position)) return 0;
        int inserted = 0;
        for (T fragment : fragments) {
            try {
                if (insert.test(fragment)) inserted++;
            } catch (RuntimeException failure) {
                onFailure.accept(failure);
            }
        }
        return inserted;
    }
}
