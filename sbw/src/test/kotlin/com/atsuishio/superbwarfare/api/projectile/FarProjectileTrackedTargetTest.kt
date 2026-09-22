package com.atsuishio.superbwarfare.api.projectile

import com.atsuishio.superbwarfare.tools.OBB
import com.atsuishio.superbwarfare.world.phys.ProjectileHitSelection
import it.unimi.dsi.fastutil.longs.Long2ObjectFunction
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.entity.*
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.stream.Stream

/** Exercises the real Minecraft accessible-section query used beneath ServerLevel.getEntities. */
class FarProjectileTrackedTargetTest {
    private class Target : EntityAccess {
        private val uuid = UUID.randomUUID()
        val obb = OBB(Vector3d(520.0, 65.0, 8.0), Vector3d(2.0, 1.0, 4.0), Quaterniond(), OBB.Part.WHEEL_LEFT)
        override fun getId() = 17
        override fun getUUID() = uuid
        override fun blockPosition() = BlockPos(520, 64, 8)
        override fun getBoundingBox() = AABB(518.0, 64.0, 4.0, 522.0, 66.0, 12.0)
        override fun setLevelCallback(callback: EntityInLevelCallback) = Unit
        override fun getSelfAndPassengers(): Stream<out EntityAccess> = Stream.of(this)
        override fun getPassengersAndSelf(): Stream<out EntityAccess> = Stream.of(this)
        override fun setRemoved(reason: Entity.RemovalReason) = Unit
        override fun shouldBeSaved() = true
        override fun isAlwaysTicking() = false
    }

    @Test fun `FULL tracked target is found and hit without becoming a ticking entity`() {
        val storage = EntitySectionStorage(Target::class.java, Long2ObjectFunction { Visibility.TRACKED })
        val lookup = EntityLookup<Target>()
        val target = Target()
        val section = storage.getOrCreateSection(SectionPos.asLong(target.blockPosition()))
        section.add(target); lookup.add(target)
        val getter = LevelEntityGetterAdapter(lookup, storage)
        val start = Vec3(490.0, 65.0, 8.0); val end = Vec3(530.0, 65.0, 8.0)
        val candidates = mutableListOf<Target>()
        getter.get(AABB(start, end).inflate(9.0), candidates::add)
        assertEquals(Visibility.TRACKED, section.status)
        assertFalse(section.status.isTicking)
        assertEquals(listOf(target), candidates)
        val hit = ProjectileHitSelection.nearestObb(candidates.map { it.obb }, start, end, 0.0)
        assertNotNull(hit)
        assertEquals(OBB.Part.WHEEL_LEFT, hit!!.part())
        assertEquals(518.0, hit.point().x, 1e-9)
        // A nearer terrain endpoint still prevents a vehicle contact through the actual narrowphase.
        assertNull(ProjectileHitSelection.nearestObb(candidates.map { it.obb }, start, Vec3(510.0, 65.0, 8.0), 0.0))
    }

    @Test fun `hidden section is not treated as a loaded hittable target`() {
        val storage = EntitySectionStorage(Target::class.java, Long2ObjectFunction { Visibility.HIDDEN })
        val target = Target()
        storage.getOrCreateSection(SectionPos.asLong(target.blockPosition())).add(target)
        val candidates = mutableListOf<Target>()
        LevelEntityGetterAdapter(EntityLookup<Target>(), storage).get(target.boundingBox.inflate(9.0), candidates::add)
        assertTrue(candidates.isEmpty())
    }
}
