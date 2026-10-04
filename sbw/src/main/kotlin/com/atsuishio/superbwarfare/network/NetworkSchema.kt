package com.atsuishio.superbwarfare.network

import net.minecraft.server.level.ServerPlayer
import java.util.WeakHashMap

enum class PacketDirection {
    PLAY_TO_CLIENT,
    PLAY_TO_SERVER,
}

enum class PacketDelivery {
    EXACT_EVENT,
    RELIABLE_EDGE,
    LATEST_SNAPSHOT,
    BULK_STATE,
    REPLACEABLE_PRESENTATION,
}

enum class PacketPriority {
    P0,
    P1,
    P2,
    P3,
}

/** Absolute allocation ceilings. These are safety limits, not performance-budget claims. */
data class PacketCodecLimits(
    val maxWireBytes: Int,
    val maxCollectionElements: Int,
    val maxStringChars: Int,
    val maxNbtBytes: Int,
    val maxCompressedBytes: Int,
    val maxDecompressedBytes: Int,
)

object PacketLimitProfiles {
    @JvmField val FAR_TERRAIN = PacketCodecLimits(1024 * 1024, 2048, 256, 0, 0, 0)
    @JvmField val FAR_TERRAIN_CONTROL = PacketCodecLimits(32 * 1024, 2048, 256, 0, 0, 0)
    @JvmField val AIRCRAFT_REQUEST = PacketCodecLimits(16 * 1024, 32, 4096, 0, 0, 0)
    @JvmField val AIRCRAFT_STATE = PacketCodecLimits(256 * 1024, 32, 65536, 0, 0, 0)
    @JvmField
    val FAR_RENDER = PacketCodecLimits(256 * 1024, 256, 8192, 0, 0, 0)

    @JvmField
    val TINY = PacketCodecLimits(8 * 1024, 64, 256, 0, 0, 0)

    @JvmField
    val CONTROL = PacketCodecLimits(32 * 1024, 512, 512, 0, 0, 0)

    @JvmField
    val PRESENTATION = PacketCodecLimits(128 * 1024, 2_048, 2_048, 0, 0, 0)

    @JvmField
    val NBT_STATE = PacketCodecLimits(512 * 1024, 2_048, 512, 256 * 1024, 0, 0)

    @JvmField
    val CONTACT_STATE = PacketCodecLimits(1024 * 1024, 2_048, 512, 128 * 1024, 0, 0)

    @JvmField
    val DATASET = PacketCodecLimits(1024 * 1024, 4_096, 512, 0, 1024 * 1024, 8 * 1024 * 1024)
}

data class PacketSchema(
    val id: Int,
    val direction: PacketDirection,
    val sinceSchemaVersion: Int,
    val limits: PacketCodecLimits,
    val delivery: PacketDelivery,
    val priority: PacketPriority,
    val handlerOwner: String,
    val feature: String,
    val ratePerSecond: Int = 0,
    val burst: Int = 0,
)

data class RegisteredPacketSchema(
    val schema: PacketSchema,
    val type: Class<out PacketPayload>,
)

/**
 * Runtime counterpart of the packaged packet manifest. Registration fails closed on duplicate IDs,
 * duplicate types, direction mismatches, missing bounds, or an accidental discriminator gap.
 */
object NetworkPacketManifest {
    const val PROTOCOL_VERSION = 47
    const val EXPECTED_PACKET_COUNT = 87

    private val byId = LinkedHashMap<Int, RegisteredPacketSchema>()
    private val byType = LinkedHashMap<Class<out PacketPayload>, RegisteredPacketSchema>()
    private var sealed = false

    @Synchronized
    fun register(schema: PacketSchema, type: Class<out PacketPayload>) {
        check(!sealed) { "The SBW packet manifest is already sealed" }
        require(schema.id >= 0) { "Negative packet discriminator ${schema.id} for ${type.name}" }
        require(schema.limits.maxWireBytes in 1..DATASET_HARD_MAX_BYTES) {
            "Invalid wire bound for ${type.name}: ${schema.limits.maxWireBytes}"
        }
        require(schema.limits.maxCollectionElements > 0) { "Missing collection bound for ${type.name}" }
        require(schema.limits.maxStringChars > 0) { "Missing string bound for ${type.name}" }
        require(schema.handlerOwner.isNotBlank()) { "Missing handler owner for ${type.name}" }
        require(schema.feature.isNotBlank()) { "Missing feature for ${type.name}" }
        if (schema.direction == PacketDirection.PLAY_TO_SERVER) {
            require(schema.ratePerSecond > 0 && schema.burst >= schema.ratePerSecond) {
                "Missing C2S rate limit for ${type.name}"
            }
        }

        check(byId.putIfAbsent(schema.id, RegisteredPacketSchema(schema, type)) == null) {
            "Duplicate packet discriminator ${schema.id}"
        }
        check(byType.putIfAbsent(type, RegisteredPacketSchema(schema, type)) == null) {
            "Duplicate packet registration ${type.name}"
        }
    }

    @Synchronized
    fun seal() {
        check(!sealed) { "The SBW packet manifest was sealed twice" }
        check(byId.size == EXPECTED_PACKET_COUNT) {
            "Expected $EXPECTED_PACKET_COUNT SBW packets, registered ${byId.size}"
        }
        val missing = (0 until EXPECTED_PACKET_COUNT).filterNot(byId::containsKey)
        check(missing.isEmpty()) { "Missing SBW packet discriminators: $missing" }
        sealed = true
    }

    @Synchronized
    fun schemaFor(type: Class<out PacketPayload>): RegisteredPacketSchema =
        checkNotNull(byType[type]) { "Unregistered packet type ${type.name}" }

    @Synchronized
    fun entries(): List<RegisteredPacketSchema> = byId.values.toList()

    private const val DATASET_HARD_MAX_BYTES = 1024 * 1024
}

/** Low-cardinality per-player token buckets shared by every C2S payload. */
object NetworkPacketGuard {
    private data class Bucket(var tokens: Double, var lastRefillNanos: Long)

    private val playerBuckets = WeakHashMap<ServerPlayer, MutableMap<Int, Bucket>>()

    @Synchronized
    fun admit(player: ServerPlayer, registered: RegisteredPacketSchema, nowNanos: Long = System.nanoTime()): Boolean {
        val schema = registered.schema
        if (schema.direction != PacketDirection.PLAY_TO_SERVER) return false

        val buckets = playerBuckets.getOrPut(player) { HashMap() }
        val bucket = buckets.getOrPut(schema.id) { Bucket(schema.burst.toDouble(), nowNanos) }
        val elapsedSeconds = ((nowNanos - bucket.lastRefillNanos).coerceAtLeast(0L)).toDouble() / 1_000_000_000.0
        bucket.lastRefillNanos = nowNanos
        bucket.tokens = (bucket.tokens + elapsedSeconds * schema.ratePerSecond).coerceAtMost(schema.burst.toDouble())
        if (bucket.tokens < 1.0) return false
        bucket.tokens -= 1.0
        return true
    }
}
