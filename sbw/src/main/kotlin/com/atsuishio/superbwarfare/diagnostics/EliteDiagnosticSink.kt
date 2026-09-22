package com.atsuishio.superbwarfare.diagnostics

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Bounded asynchronous JSONL output. No gameplay-thread file writes or unbounded event backlog. */
internal class EliteDiagnosticSink(
    val path: Path,
    val session: UUID,
    private val side: String,
    metadata: Map<String, Any?>,
    capacity: Int = 8192,
    private val maxBytes: Long = 64L * 1024 * 1024,
    private val onFailure: (String) -> Unit = {},
) : AutoCloseable {
    private val queue = ArrayBlockingQueue<Map<String, Any?>>(capacity)
    private val sequence = AtomicLong()
    private val admission = Any()
    val dropped = AtomicLong()
    @Volatile var accepting = true
        private set
    @Volatile var failure: String? = null
        private set
    @Volatile private var closing = false
    private val writer: Thread

    init {
        writer = Thread({
            try {
                Files.createDirectories(path.parent)
                val gson = GsonBuilder().disableHtmlEscaping().create()
                Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
                    var bytes = 0L
                    fun write(value: Map<String, Any?>) {
                        val line = gson.toJson(value)
                        bytes += line.toByteArray(Charsets.UTF_8).size + 1
                        out.write(line)
                        out.newLine()
                    }
                    write(mapOf("schema" to 1, "session" to session.toString(), "side" to side,
                        "category" to "session", "event" to "start", "metadata" to metadata,
                        "artifact_sha256" to hashArtifacts(metadata["artifacts"])))
                    while (!closing || queue.isNotEmpty()) {
                        val event = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                        if (bytes >= maxBytes) {
                            close()
                            dropped.incrementAndGet()
                        } else write(event)
                    }
                    write(mapOf("category" to "session", "event" to "stop", "session" to session.toString(),
                        "side" to side, "events" to sequence.get(), "dropped" to dropped.get(), "bytes" to bytes))
                }
            } catch (error: Exception) {
                failure = error.toString()
                onFailure(failure!!)
            } finally {
                accepting = false
            }
        }, "Elite diagnostics $side").apply { isDaemon = true; start() }
    }

    fun offer(tick: Long, category: String, event: String, fields: Map<String, Any?>) = synchronized(admission) {
        if (!accepting) return
        val row = linkedMapOf<String, Any?>(
            "session" to session.toString(), "side" to side, "sequence" to sequence.incrementAndGet(),
            "tick" to tick, "monotonic_ns" to System.nanoTime(), "category" to category, "event" to event,
            "data" to fields.toMap(),
        )
        if (!queue.offer(row)) dropped.incrementAndGet()
    }

    override fun close() = synchronized(admission) { accepting = false; closing = true }
    fun awaitClosed(timeoutMillis: Long): Boolean { writer.join(timeoutMillis); return !writer.isAlive }

    /** Runs only on the writer, never the game/render thread. */
    private fun hashArtifacts(artifacts: Any?): Map<String, String> {
        val paths = artifacts as? Map<*, *> ?: return emptyMap()
        return paths.entries.associate { (id, value) ->
            val file = Path.of(value.toString())
            val hash = if (!Files.isRegularFile(file)) "development_directory" else {
                val digest = MessageDigest.getInstance("SHA-256")
                Files.newInputStream(file).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            id.toString() to hash
        }
    }
}
