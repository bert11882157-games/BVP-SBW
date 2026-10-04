package com.atsuishio.superbwarfare.api.vehicle.deck

/**
 * An entity whose hull is terrain (aircraft carriers). The deck answers movement collision, aircraft gear/terrain
 * probes, ground-vehicle tilt rays, placement rays and the flight-kick check like blocks do, and carries whatever
 * stands on it when the owner moves or turns ([DeckCarry]). The owner's own OBBs stay hit/pick volumes only:
 * vehicle-to-vehicle contact, OBB support and crushing skip deck owners.
 *
 * Implemented by an [net.minecraft.world.entity.Entity]; registered per level on join ([DeckRegistry]).
 */
interface DeckSurfaceEntity {
    /** The heightfield in the entity's data frame, or null while it is not available (never throws). */
    fun deckSurface(): DeckSurface?
}
