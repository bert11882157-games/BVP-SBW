package com.atsuishio.superbwarfare.api.vehicle.weapon.prediction

import com.atsuishio.superbwarfare.entity.projectile.ProjectileEntity
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

internal enum class LoadedTraceEvent {
    BLOCK,
    LIQUID,
    CONTEXT_SENSITIVE,
    UNLOADED,
    CLEAR,
}

internal data class LoadedBlockTrace(
    val event: LoadedTraceEvent,
    val hit: BlockHitResult? = null,
    val completedFraction: Double = 1.0,
    val frontier: Vec3,
)

/** Immutable DDA state; each bounded advance returns a new state or a terminal trace result. */
internal data class LoadedTraceCursor(
    val start: Vec3,
    val end: Vec3,
    val collisionModel: NominalBlockCollisionModel,
    val detectLiquid: Boolean,
    val rejectContextSensitive: Boolean,
    val blockX: Int,
    val blockY: Int,
    val blockZ: Int,
    val signX: Int,
    val signY: Int,
    val signZ: Int,
    val stepX: Double,
    val stepY: Double,
    val stepZ: Double,
    val nextX: Double,
    val nextY: Double,
    val nextZ: Double,
    val initialCellPending: Boolean,
)

internal data class LoadedTraceAdvance(
    val cursor: LoadedTraceCursor?,
    val result: LoadedBlockTrace?,
    val visited: Int,
)

/**
 * Non-loading voxel traversal using the same axis tie order as the live SBW projectile ray
 * query. The legacy trace() wrapper drains the same immutable cursor, while client prediction
 * can retain it between bounded slices without changing event order or collision semantics.
 */
internal object LoadedChunkBlockTrace {
    private val CONTEXT_SENSITIVE_BLOCKS = setOf(Blocks.POWDER_SNOW, Blocks.SCAFFOLDING)

    fun begin(
        start: Vec3,
        end: Vec3,
        collisionModel: NominalBlockCollisionModel,
        detectLiquid: Boolean,
        rejectContextSensitive: Boolean,
    ): LoadedTraceCursor {
        val startX = Mth.lerp(-0.0000001, end.x, start.x)
        val startY = Mth.lerp(-0.0000001, end.y, start.y)
        val startZ = Mth.lerp(-0.0000001, end.z, start.z)
        val endX = Mth.lerp(-0.0000001, start.x, end.x)
        val endY = Mth.lerp(-0.0000001, start.y, end.y)
        val endZ = Mth.lerp(-0.0000001, start.z, end.z)
        val blockX = Mth.floor(endX)
        val blockY = Mth.floor(endY)
        val blockZ = Mth.floor(endZ)
        val deltaX = startX - endX
        val deltaY = startY - endY
        val deltaZ = startZ - endZ
        val signX = Mth.sign(deltaX)
        val signY = Mth.sign(deltaY)
        val signZ = Mth.sign(deltaZ)
        val stepX = if (signX == 0) Double.MAX_VALUE else signX.toDouble() / deltaX
        val stepY = if (signY == 0) Double.MAX_VALUE else signY.toDouble() / deltaY
        val stepZ = if (signZ == 0) Double.MAX_VALUE else signZ.toDouble() / deltaZ
        return LoadedTraceCursor(
            start,
            end,
            collisionModel,
            detectLiquid,
            rejectContextSensitive,
            blockX,
            blockY,
            blockZ,
            signX,
            signY,
            signZ,
            stepX,
            stepY,
            stepZ,
            stepX * if (signX > 0) 1 - Mth.frac(endX) else Mth.frac(endX),
            stepY * if (signY > 0) 1 - Mth.frac(endY) else Mth.frac(endY),
            stepZ * if (signZ > 0) 1 - Mth.frac(endZ) else Mth.frac(endZ),
            true,
        )
    }

    fun advance(
        level: Level,
        initial: LoadedTraceCursor,
        maxVoxelVisits: Int,
        deadlineNanos: Long = 0L,
    ): LoadedTraceAdvance {
        if (maxVoxelVisits <= 0) return LoadedTraceAdvance(initial, null, 0)
        if (initial.start == initial.end) {
            return LoadedTraceAdvance(null, LoadedBlockTrace(LoadedTraceEvent.CLEAR, frontier = initial.end), 0)
        }
        val context = ClipContext(
            initial.start,
            initial.end,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            null,
        )
        val liquidContext = if (initial.detectLiquid) {
            ClipContext(
                initial.start,
                initial.end,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.ANY,
                null,
            )
        } else null

        var state = initial
        var visited = 0
        val mutablePos = BlockPos.MutableBlockPos(state.blockX, state.blockY, state.blockZ)
        while (visited < maxVoxelVisits) {
            if (deadlineReached(deadlineNanos)) return LoadedTraceAdvance(state, null, visited)
            val enteredFraction: Double
            if (state.initialCellPending) {
                enteredFraction = 0.0
                state = state.copy(initialCellPending = false)
            } else {
                if (state.nextX > 1.0 && state.nextY > 1.0 && state.nextZ > 1.0) {
                    return LoadedTraceAdvance(null, LoadedBlockTrace(LoadedTraceEvent.CLEAR, frontier = state.end), visited)
                }
                if (state.nextX < state.nextY) {
                    if (state.nextX < state.nextZ) {
                        enteredFraction = state.nextX
                        state = state.copy(blockX = state.blockX + state.signX, nextX = state.nextX + state.stepX)
                    } else {
                        enteredFraction = state.nextZ
                        state = state.copy(blockZ = state.blockZ + state.signZ, nextZ = state.nextZ + state.stepZ)
                    }
                } else if (state.nextY < state.nextZ) {
                    enteredFraction = state.nextY
                    state = state.copy(blockY = state.blockY + state.signY, nextY = state.nextY + state.stepY)
                } else {
                    enteredFraction = state.nextZ
                    state = state.copy(blockZ = state.blockZ + state.signZ, nextZ = state.nextZ + state.stepZ)
                }
            }
            mutablePos.set(state.blockX, state.blockY, state.blockZ)
            visited++
            visit(
                level,
                context,
                liquidContext,
                mutablePos,
                state.collisionModel,
                state.rejectContextSensitive,
                state.start,
                state.end,
                enteredFraction,
            )?.let { return LoadedTraceAdvance(null, it, visited) }
            if (deadlineReached(deadlineNanos)) return LoadedTraceAdvance(state, null, visited)
        }
        return LoadedTraceAdvance(state, null, visited)
    }

    fun trace(
        level: Level,
        start: Vec3,
        end: Vec3,
        collisionModel: NominalBlockCollisionModel,
        detectLiquid: Boolean,
        rejectContextSensitive: Boolean,
    ): LoadedBlockTrace {
        var cursor = begin(start, end, collisionModel, detectLiquid, rejectContextSensitive)
        while (true) {
            val slice = advance(level, cursor, Int.MAX_VALUE)
            slice.result?.let { return it }
            cursor = requireNotNull(slice.cursor)
        }
    }

    private fun visit(
        level: Level,
        blockContext: ClipContext,
        liquidContext: ClipContext?,
        pos: BlockPos,
        collisionModel: NominalBlockCollisionModel,
        rejectContextSensitive: Boolean,
        start: Vec3,
        end: Vec3,
        enteredFraction: Double,
    ): LoadedBlockTrace? {
        val frontierFraction = enteredFraction.coerceIn(0.0, 1.0)
        if (!level.hasChunkAt(pos)) {
            return LoadedBlockTrace(
                LoadedTraceEvent.UNLOADED,
                completedFraction = frontierFraction,
                frontier = frontier(start, end, frontierFraction),
            )
        }
        val state = level.getBlockState(pos)
        val fluidState = level.getFluidState(pos)
        if (collisionModel == NominalBlockCollisionModel.SBW_PROJECTILE &&
            ProjectileEntity.isNominalBlockIgnored(state)
        ) return null
        if (rejectContextSensitive && state.block in CONTEXT_SENSITIVE_BLOCKS) {
            return LoadedBlockTrace(
                LoadedTraceEvent.CONTEXT_SENSITIVE,
                completedFraction = frontierFraction,
                frontier = frontier(start, end, frontierFraction),
            )
        }

        val blockHit = clipCell(level, blockContext, pos, state, fluidState, collisionModel)
        val liquidHit = liquidContext
            ?.takeIf { !fluidState.isEmpty }
            ?.let { clipCell(level, it, pos, state, fluidState, collisionModel) }
        val blockDistance = blockHit?.location?.let(start::distanceToSqr) ?: Double.POSITIVE_INFINITY
        val liquidDistance = liquidHit?.location?.let(start::distanceToSqr) ?: Double.POSITIVE_INFINITY
        val hit = if (liquidDistance < blockDistance) liquidHit else blockHit
        if (hit != null) {
            val fraction = if (start.distanceToSqr(end) <= 1.0E-18) 0.0
            else start.distanceTo(hit.location) / start.distanceTo(end)
            return LoadedBlockTrace(
                if (hit === liquidHit) LoadedTraceEvent.LIQUID else LoadedTraceEvent.BLOCK,
                hit,
                fraction,
                hit.location,
            )
        }
        return null
    }

    private fun frontier(start: Vec3, end: Vec3, fraction: Double): Vec3 = Vec3(
        start.x + (end.x - start.x) * fraction,
        start.y + (end.y - start.y) * fraction,
        start.z + (end.z - start.z) * fraction,
    )

    private fun clipCell(
        level: Level,
        context: ClipContext,
        pos: BlockPos,
        blockState: BlockState,
        fluidState: FluidState,
        collisionModel: NominalBlockCollisionModel,
    ): BlockHitResult? = when (collisionModel) {
        NominalBlockCollisionModel.SBW_PROJECTILE ->
            ProjectileEntity.rayTraceAcceptedNominalCell(level, context, pos, blockState, fluidState)
        NominalBlockCollisionModel.STANDARD_PROJECTILE -> {
            val blockShape = context.getBlockShape(blockState, level, pos)
            val blockHit =
                level.clipWithInteractionOverride(context.from, context.to, pos, blockShape, blockState)
            val fluidShape = context.getFluidShape(fluidState, level, pos)
            val fluidHit = fluidShape.clip(context.from, context.to, pos)
            val blockDistance = blockHit?.location?.let(context.from::distanceToSqr) ?: Double.POSITIVE_INFINITY
            val fluidDistance = fluidHit?.location?.let(context.from::distanceToSqr) ?: Double.POSITIVE_INFINITY
            if (blockDistance <= fluidDistance) blockHit else fluidHit
        }
    }

    private fun deadlineReached(deadlineNanos: Long): Boolean =
        deadlineNanos != 0L && System.nanoTime() >= deadlineNanos
}
