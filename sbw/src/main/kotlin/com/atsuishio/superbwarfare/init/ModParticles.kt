package com.atsuishio.superbwarfare.init

import com.atsuishio.superbwarfare.api.effect.ExplosionBurstPresentations
import com.atsuishio.superbwarfare.api.effect.FireballPresentations
import com.atsuishio.superbwarfare.api.effect.ShockwavePresentations
import com.atsuishio.superbwarfare.client.particle.*
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RegisterParticleProvidersEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD, value = [Dist.CLIENT])
object ModParticles {
    @SubscribeEvent
    fun registerParticles(event: RegisterParticleProvidersEvent) {
        ExplosionBurstPresentations.registerClientEmitter(ExplosionBurstClient::spawn)
        ShockwavePresentations.registerClientEmitter(ShockwaveClient::emit)
        FireballPresentations.registerClientEmitter(FireballClient::emit)
        with(event) {
            registerSpriteSet(ModParticleTypes.FIRE_STAR.get()) { FireStarParticle.provider(it) }
            registerSpriteSet(ModParticleTypes.WHITE_STAR.get()) { WhiteStarParticle.provider(it) }
            registerSpriteSet(ModParticleTypes.CHAFF_BURST.get()) { ChaffBurstParticle.Provider(it) }
            registerSpriteSet(ModParticleTypes.RISING_SMOKE.get()) { RisingSmokeParticle.provider(it) }
            registerSpecial(ModParticleTypes.BULLET_DECAL.get(), BulletDecalParticle.Provider())
            registerSpriteSet(ModParticleTypes.CUSTOM_CLOUD.get()) { CustomCloudParticle.Provider(it) }
            registerSpriteSet(ModParticleTypes.CUSTOM_SMOKE.get()) { CustomSmokeParticle.Provider(it) }
            registerSpriteSet(ModParticleTypes.CANNON_MUZZLE_FLARE.get()) { CannonMuzzleFlareParticle.Provider(it) }
            registerSpriteSet(ModParticleTypes.BLAST_FIREBALL.get()) { BlastSprites.fireball(it) }
            registerSpriteSet(ModParticleTypes.BLAST_SOOT.get()) { BlastSprites.soot(it) }
            registerSpriteSet(ModParticleTypes.BLAST_SHOCKWAVE.get()) { BlastSprites.shockwave(it) }
        }
    }
}
