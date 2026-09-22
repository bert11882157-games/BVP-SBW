package com.atsuishio.superbwarfare.api.projectile.impact;

/**
 * Marker for provider-owned, nonphysical vehicle impact-volume data.
 *
 * <p>Views exist only for one server-side coarse entity impact. They are not entity collision boxes,
 * are never synchronized to clients, and must not mutate the target's physical OBB collection.</p>
 */
public interface VehicleImpactVolumeView {
}
