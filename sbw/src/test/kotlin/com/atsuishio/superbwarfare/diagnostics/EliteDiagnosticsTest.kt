package com.atsuishio.superbwarfare.diagnostics

import com.atsuishio.superbwarfare.network.PacketLimitProfiles
import com.atsuishio.superbwarfare.network.message.receive.EliteDiagnosticsStateMessage
import com.atsuishio.superbwarfare.serialization.ByteBufDecoder
import com.atsuishio.superbwarfare.serialization.ByteBufEncoder
import com.google.gson.JsonParser
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class EliteDiagnosticsTest {
    @TempDir lateinit var directory: Path

    @Test fun `close drains accepted events without sharing mutable fields`() {
        val path = directory.resolve("drain.jsonl")
        val sink = EliteDiagnosticSink(path, UUID.randomUUID(), "server", emptyMap())
        val fields = mutableMapOf<String, Any?>("weapon" to "Cannon", "count" to 1)
        sink.offer(12, "audio", "start", fields)
        fields["count"] = 99
        sink.close()
        sink.offer(13, "audio", "after_close", emptyMap())
        assertTrue(sink.awaitClosed(5000))
        assertNull(sink.failure)
        val lines = Files.readAllLines(path).map { JsonParser.parseString(it).asJsonObject }
        assertEquals(listOf("start", "start", "stop"), lines.map { it["event"].asString })
        assertEquals(1, lines[1]["data"].asJsonObject["count"].asInt)
        assertEquals(1, lines.last()["events"].asInt)
    }

    @Test fun `concurrent producers and close lose no accepted event without accounting`() {
        val path = directory.resolve("concurrent.jsonl")
        val sink = EliteDiagnosticSink(path, UUID.randomUUID(), "server", emptyMap(), capacity = 32)
        val begin = CountDownLatch(1)
        val threads = List(4) { producer -> Thread {
            begin.await()
            repeat(1000) { sink.offer(it.toLong(), "test", "sample", mapOf("producer" to producer)) }
        }.apply { start() } }
        begin.countDown()
        threads.forEach { it.join() }
        sink.close()
        assertTrue(sink.awaitClosed(5000))
        val rows = Files.readAllLines(path).map { JsonParser.parseString(it).asJsonObject }
        val footer = rows.last()
        assertEquals(4000, footer["events"].asInt)
        assertEquals(4000L, rows.size - 2L + footer["dropped"].asLong)
        val sequences = rows.drop(1).dropLast(1).map { it["sequence"].asLong }
        assertEquals(sequences.sorted().distinct(), sequences)
    }

    @Test fun `byte cap stops capture and reports dropped backlog`() {
        val path = directory.resolve("bounded.jsonl")
        val sink = EliteDiagnosticSink(path, UUID.randomUUID(), "client", emptyMap(), 16, 1500)
        repeat(10000) { sink.offer(it.toLong(), "test", "sample", mapOf("payload" to "x".repeat(100))) }
        sink.close()
        assertTrue(sink.awaitClosed(5000))
        assertTrue(Files.size(path) < 4096)
        assertTrue(sink.dropped.get() > 0)
        assertFalse(sink.accepting)
    }

    @Test fun `writer IO failure is reported without throwing on gameplay thread`() {
        val parentFile = directory.resolve("not-a-directory")
        Files.writeString(parentFile, "existing file")
        val failure = AtomicReference<String>()
        val sink = EliteDiagnosticSink(parentFile.resolve("capture.jsonl"), UUID.randomUUID(),
            "server", emptyMap(), onFailure = failure::set)
        assertTrue(sink.awaitClosed(5000))
        assertNotNull(sink.failure)
        assertEquals(sink.failure, failure.get())
        assertFalse(sink.accepting)
        assertEquals("existing file", Files.readString(parentFile))
    }

    @Test fun `capture identifies actual artifact bytes`() {
        val artifact = directory.resolve("artifact.jar")
        Files.writeString(artifact, "abc")
        val path = directory.resolve("provenance.jsonl")
        val sink = EliteDiagnosticSink(path, UUID.randomUUID(), "server",
            mapOf("artifacts" to mapOf("sbw" to artifact.toString())))
        sink.close()
        assertTrue(sink.awaitClosed(5000))
        val header = JsonParser.parseString(Files.readAllLines(path).first()).asJsonObject
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            header["artifact_sha256"].asJsonObject["sbw"].asString)
    }

    @Test fun `session toggle packet round trip preserves identity and state`() {
        for (enabled in listOf(true, false)) {
            val source = EliteDiagnosticsStateMessage(UUID.randomUUID(), enabled)
            val buffer = FriendlyByteBuf(Unpooled.buffer())
            try {
                ByteBufEncoder(buffer, PacketLimitProfiles.TINY)
                    .encodeSerializableValue(EliteDiagnosticsStateMessage.serializer(), source)
                assertTrue(buffer.readableBytes() < PacketLimitProfiles.TINY.maxWireBytes)
                val decoded = ByteBufDecoder(buffer, PacketLimitProfiles.TINY)
                    .decodeSerializableValue(EliteDiagnosticsStateMessage.serializer())
                assertEquals(source, decoded)
            } finally { buffer.release() }
        }
    }
}
