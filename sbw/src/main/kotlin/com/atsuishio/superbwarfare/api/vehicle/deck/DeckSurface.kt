package com.atsuishio.superbwarfare.api.vehicle.deck

import com.google.gson.JsonObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A walkable hull as terrain: a heightfield over the owner's plan in its data frame (+X left, +Y up, +Z forward,
 * blocks, origin at the entity position). Every cell is a solid column from [bottomAt] to [topAt]; empty cells are
 * open water/air. The column model is what lets the hull answer exactly like blocks do (axis-aligned solids) for
 * movement collision, wheel and gear probes and placement rays, at any yaw.
 */
class DeckSurface(
    val cellSize: Double,
    val minX: Double,
    val minZ: Double,
    val sizeX: Int,
    val sizeZ: Int,
    /** The flight deck: one exact height every flush cell shares, so aircraft roll on a plane. */
    val deckHeight: Double,
    private val top: FloatArray,
    private val bottom: FloatArray,
) {
    val maxX: Double = minX + sizeX * cellSize
    val maxZ: Double = minZ + sizeZ * cellSize
    val maxTop: Double
    val minBottom: Double
    /** Horizontal distance from the origin to the farthest corner of the plan. */
    val radius: Double

    init {
        require(cellSize > 0.0 && sizeX > 0 && sizeZ > 0) { "empty deck surface" }
        require(top.size == sizeX * sizeZ && bottom.size == top.size) { "deck surface size mismatch" }
        var hi = Double.NEGATIVE_INFINITY
        var lo = Double.POSITIVE_INFINITY
        for (k in top.indices) {
            if (top[k].isNaN()) continue
            hi = max(hi, top[k].toDouble())
            lo = min(lo, bottom[k].toDouble())
        }
        require(hi.isFinite()) { "deck surface has no columns" }
        maxTop = hi
        minBottom = lo
        val fx = max(-minX, maxX)
        val fz = max(-minZ, maxZ)
        radius = sqrt(fx * fx + fz * fz)
    }

    /** Column index under a local point, or -1 off the plan or over an empty cell. */
    fun column(localX: Double, localZ: Double): Int {
        val i = floor((localX - minX) / cellSize).toInt()
        val j = floor((localZ - minZ) / cellSize).toInt()
        if (i < 0 || j < 0 || i >= sizeX || j >= sizeZ) return -1
        val k = j * sizeX + i
        return if (top[k].isNaN()) -1 else k
    }

    fun topOf(column: Int): Double = top[column].toDouble()
    fun bottomOf(column: Int): Double = bottom[column].toDouble()

    /** Local column top under a local point, NaN when there is none. */
    fun topAt(localX: Double, localZ: Double): Double {
        val k = column(localX, localZ)
        return if (k < 0) Double.NaN else top[k].toDouble()
    }

    companion object {
        const val EMPTY: Short = Short.MIN_VALUE

        /** `sbw/decks/<id>.json` as written by tools/carrier/carrier_gen.py (heights in 1/16 block, RLE int16). */
        @JvmStatic
        fun parse(json: JsonObject): DeckSurface {
            require(json.get("Version")?.asInt == 1) { "unsupported deck surface version" }
            val sizeX = json.get("SizeX").asInt
            val sizeZ = json.get("SizeZ").asInt
            require(sizeX in 1..4096 && sizeZ in 1..4096) { "deck surface too large" }
            return DeckSurface(
                json.get("CellSize").asDouble, json.get("MinX").asDouble, json.get("MinZ").asDouble, sizeX, sizeZ,
                json.get("DeckHeight").asDouble,
                decode(json.get("Top").asString, sizeX * sizeZ), decode(json.get("Bottom").asString, sizeX * sizeZ),
            )
        }

        /** Base64 of little-endian (value, count) int16 pairs; value in 1/16 block, [EMPTY] for no column. */
        @JvmStatic
        fun decode(text: String, size: Int): FloatArray {
            val bytes = ByteBuffer.wrap(Base64.getDecoder().decode(text)).order(ByteOrder.LITTLE_ENDIAN)
            val out = FloatArray(size)
            var k = 0
            while (bytes.remaining() >= 4) {
                val value = bytes.short
                val count = bytes.short.toInt()
                require(count > 0 && k + count <= size) { "deck surface run overflows the grid" }
                val v = if (value == EMPTY) Float.NaN else value / 16f
                out.fill(v, k, k + count)
                k += count
            }
            require(k == size) { "deck surface runs cover $k of $size cells" }
            return out
        }
    }
}
