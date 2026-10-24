package com.yourname.berts_vehicle_pack.entity;

import com.atsuishio.superbwarfare.api.vehicle.deck.DeckCarry;
import com.atsuishio.superbwarfare.api.vehicle.deck.DeckPose;
import com.atsuishio.superbwarfare.api.vehicle.deck.DeckSurface;
import com.atsuishio.superbwarfare.api.vehicle.deck.DeckSurfaceEntity;
import com.yourname.berts_vehicle_pack.carrier.BvpDeckSurfaces;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * A boat or craft with a cargo deck (the LCAC's well and ramps): its hull is terrain exactly as an aircraft carrier's (SBW
 * DeckSurfaceEntity, heightfield in data/berts_vehicle_pack/sbw/decks/&lt;id&gt;.json), so vehicles and players drive and walk on
 * it through the ramps at any heading, and whatever stands on it moves and turns with the craft (DeckCarry). Unlike the carrier it
 * keeps the SBW ship engine for its own movement; the deck assumes it never rolls (BodyRollRate 0 in its data).
 */
public final class FittedDeckBoatEntity extends FittedBoatEntity implements DeckSurfaceEntity {
    public FittedDeckBoatEntity(EntityType<?> type, Level level, String profileId) {
        super(type, level, profileId);
    }

    @Override
    public DeckSurface deckSurface() {
        return BvpDeckSurfaces.get(this.getArmorProfileId());
    }

    @Override
    protected void afterVehicleTick() {
        super.afterVehicleTick();
        DeckCarry.afterOwnerMoved(this);
    }

    /** The deck is terrain: the hull never crushes what stands or drives on it. */
    @Override
    public boolean canCrushEntities() {
        return false;
    }

    /** The whole hull, not the small physics box at its centre. */
    @Override
    public AABB getBoundingBoxForCulling() {
        DeckSurface surface = this.deckSurface();
        if (surface == null) return super.getBoundingBoxForCulling();
        return DeckPose.of(this).envelope(surface).inflate(2.0D);
    }

    /**
     * Drawn out to the hull radius (16 blocks) plus 128: the craft is tracked to its clients out to 160 blocks (the entity type's 10-chunk
     * tracking range), so it is drawn wherever it exists, but not out to the 780 blocks vanilla would allow a 15 x 6.5 x 15 box and not the
     * carrier's +512 (a 267-block hull seen from the far end of the world). A 27 m craft is a few pixels beyond that.
     */
    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSquared) {
        DeckSurface surface = this.deckSurface();
        double reach = (surface == null ? 0.0D : surface.getRadius()) + 128.0D;
        return distanceSquared < reach * reach;
    }
}
