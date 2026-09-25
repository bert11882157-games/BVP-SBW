package com.atsuishio.superbwarfare.event

import com.atsuishio.superbwarfare.api.projectile.HeavyWarheadBlastPolicy
import com.atsuishio.superbwarfare.entity.vehicle.M1A2Entity
import com.atsuishio.superbwarfare.init.ModSerializers
import com.atsuishio.superbwarfare.tools.CustomExplosion
import com.atsuishio.superbwarfare.tools.blast.BlastModel
import com.atsuishio.superbwarfare.tools.blast.BlastParameters
import com.atsuishio.superbwarfare.tools.blast.TntBlast
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraftforge.event.level.ExplosionEvent
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.RegistryObject
import java.util.function.Supplier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import sun.misc.Unsafe

class ZeroDamageVehicleExplosionTest {
    companion object {
        private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").let {
            it.isAccessible = true
            it.get(null) as Unsafe
        }
        @BeforeAll @JvmStatic fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
            // Plain JUnit does not run Forge's deferred registration phase. Supply the
            // real registered serializers before VehicleEntity initializes its data keys.
            val entries = DeferredRegister::class.java.getDeclaredField("entries").apply { isAccessible = true }
                .get(ModSerializers.REGISTRY) as Map<*, *>
            val value = RegistryObject::class.java.getDeclaredField("value").apply { isAccessible = true }
            for ((key, supplier) in entries) value.set(key, (supplier as Supplier<*>).get())
        }
        private fun <T> unconstructed(type: Class<T>): T = type.cast(unsafe.allocateInstance(type))
    }

    private fun blast(damage: Float): CustomExplosion = unconstructed(CustomExplosion::class.java).also {
        CustomExplosion::class.java.getDeclaredField("damage").apply { isAccessible = true }.setFloat(it, damage)
    }

    @Test fun `actual vehicle detonation listener rejects visual only or invalid damage before touching the world`() {
        // Intentionally unconstructed world/vehicle fixtures trip if a visual blast enters
        // radius, visibility or hurt processing. This invokes the production Forge handler.
        val level = unconstructed(ServerLevel::class.java)
        val vehicle = unconstructed(M1A2Entity::class.java)
        for (damage in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val targets = mutableListOf<Entity>(vehicle)
            val event = ExplosionEvent.Detonate(level, blast(damage), targets)
            assertDoesNotThrow { LivingEventHandler.onExplosionDetonate(event) }
            assertTrue(targets.isEmpty(), "Zero-damage legacy vehicle target must not fall through to another blast pass")
        }
    }

    @Test fun `typed heavy warheads retain target ownership even with zero native damage`() {
        val explosion = blast(0f)
        CustomExplosion::class.java.getDeclaredField("activeHeavyWarheadBlast").apply { isAccessible = true }
            .set(explosion, HeavyWarheadBlastPolicy(2.0, 6.0, .2f, .5f))
        val vehicle = unconstructed(M1A2Entity::class.java)
        val targets = mutableListOf<Entity>(vehicle)
        LivingEventHandler.onExplosionDetonate(ExplosionEvent.Detonate(
            unconstructed(ServerLevel::class.java), explosion, targets))
        assertEquals(1, targets.size)
        assertSame(vehicle, targets.single(), "The typed blast pass must still receive its vehicle")
        assertTrue(blast(40f).hasLegacyVehicleBlastDamage(), "Positive legacy blast admission is preserved")
    }

    @Test fun `TNT-equivalent blasts keep every vehicle target for their own pass`() {
        val explosion = blast(200f)
        assertFalse(explosion.usesTntModel(), "An unconfigured explosion stays on the legacy path")
        explosion.setTntPlan(TntBlast.Plan(100.0, BlastParameters.DEFAULT, BlastModel.radii(100.0), null))
        assertTrue(explosion.usesTntModel())
        assertEquals(100.0, explosion.tntEquivalentKg())
        // Unconstructed world/vehicle fixtures trip if the legacy armor-scaled pass touches them.
        val vehicle = unconstructed(M1A2Entity::class.java)
        val targets = mutableListOf<Entity>(vehicle)
        assertDoesNotThrow { LivingEventHandler.onExplosionDetonate(ExplosionEvent.Detonate(
            unconstructed(ServerLevel::class.java), explosion, targets)) }
        assertSame(vehicle, targets.single(), "The TNT model applies vehicle rules itself")
        assertTrue(explosion.ownsGroundVehicleBlast(vehicle))
    }
}
