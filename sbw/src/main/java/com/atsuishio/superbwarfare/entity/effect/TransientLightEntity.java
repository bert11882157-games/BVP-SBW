package com.atsuishio.superbwarfare.entity.effect;

import com.atsuishio.superbwarfare.api.effect.DynamicLightSource;
import com.atsuishio.superbwarfare.api.effect.TransientLightSpec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;

/**
 * Invisible tracked light source. It changes no blocks and carries only its
 * luminance and lifetime through vanilla entity data synchronization.
 */
public final class TransientLightEntity extends Entity implements DynamicLightSource {
    private static final EntityDataAccessor<Integer> LUMINANCE =
            SynchedEntityData.defineId(TransientLightEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> LIFETIME_TICKS =
            SynchedEntityData.defineId(TransientLightEntity.class, EntityDataSerializers.INT);

    public TransientLightEntity(EntityType<? extends TransientLightEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    public void configure(TransientLightSpec spec) {
        if (spec != null) configure(spec.luminance(), spec.lifetimeTicks());
    }

    public void configure(int luminance, int lifetimeTicks) {
        this.entityData.set(LUMINANCE, Math.max(0, Math.min(15, luminance)));
        this.entityData.set(LIFETIME_TICKS, Math.max(1, lifetimeTicks));
    }

    @Override
    public int getDynamicLightLuminance() {
        int lifetime = this.entityData.get(LIFETIME_TICKS);
        if (lifetime <= 0 || this.tickCount >= lifetime || this.isRemoved()) {
            return 0;
        }
        return this.entityData.get(LUMINANCE);
    }

    public int getLifetimeTicks() {
        return this.entityData.get(LIFETIME_TICKS);
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(LUMINANCE, 0);
        this.entityData.define(LIFETIME_TICKS, 1);
    }

    @Override
    public void tick() {
        super.tick();
        this.noPhysics = true;
        if (this.tickCount >= this.entityData.get(LIFETIME_TICKS)) {
            this.discard();
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        // The entity type is no-save; entity data is authoritative while tracked.
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        // The entity type is no-save; entity data is authoritative while tracked.
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
