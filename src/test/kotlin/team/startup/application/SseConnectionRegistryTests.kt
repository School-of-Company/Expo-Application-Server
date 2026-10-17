package team.startup.application

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import team.startup.application.global.sse.SseConnectionLimitException
import team.startup.application.global.sse.SseConnectionRegistry
import team.startup.application.global.sse.SseLimits
import team.startup.application.global.sse.SseMessage
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SseConnectionRegistryTests {
    private val emitters = CopyOnWriteArrayList<RecordingEmitter>()
    private var behavior: (Long) -> RecordingEmitter = { RecordingEmitter(it) }
    private var registry = registry()

    @AfterEach
    fun close() = registry.close()

    @Test
    fun `events reach only connections of the same key and both subscribers of a key`() {
        registry.open("a")
        registry.open("a")
        registry.open("b")
        registry.publish("a", SseMessage("1", "changed", mapOf("key" to "a")))
        eventually { emitters.take(2).all { it.events() == listOf("id:1|event:changed|data:{key=a}") } }
        Thread.sleep(100)
        assertEquals(emptyList<String>(), emitters[2].events())
    }

    @Test
    fun `connection limits are enforced per key and in total`() {
        registry.close()
        registry = registry(SseLimits(3, 2, Duration.ofMinutes(1), Duration.ofMinutes(1)))
        registry.open("a")
        registry.open("a")
        assertThrows(SseConnectionLimitException::class.java) { registry.open("a") }
        registry.open("b")
        assertThrows(SseConnectionLimitException::class.java) { registry.open("c") }
        assertEquals(3, registry.connectionCount())
        assertEquals(2, registry.connectionCount("a"))
    }

    @Test
    fun `slow receiver keeps only the latest pending event instead of queueing every change`() {
        val release = CountDownLatch(1)
        behavior = { RecordingEmitter(it, block = release) }
        registry.open("a")
        eventually { emitters.single().blocked }
        repeat(100) { registry.publish("a", SseMessage("$it", "changed", it)) }
        release.countDown()
        eventually { emitters.single().events().isNotEmpty() }
        Thread.sleep(200)
        assertEquals(listOf("id:99|event:changed|data:99"), emitters.single().events())
    }

    @Test
    fun `send failure closes and forgets the connection`() {
        behavior = { RecordingEmitter(it, fail = true) }
        registry.open("a")
        eventually { registry.connectionCount() == 0 }
        registry.publish("a", SseMessage("1", "changed", 1))
        assertEquals(0, registry.connectionCount("a"))
    }

    @Test
    fun `heartbeat is a comment and never a data event`() {
        registry.close()
        registry = registry(SseLimits(10, 10, Duration.ofMinutes(1), Duration.ofMillis(50)))
        registry.open("a")
        eventually { emitters.single().comments() >= 3 }
        assertEquals(emptyList<String>(), emitters.single().events())
    }

    @Test
    fun `connections use the configured lifetime as the emitter timeout`() {
        registry.open("a")
        assertEquals(Duration.ofMinutes(30).toMillis(), emitters.single().timeout)
    }

    private fun registry(limits: SseLimits = SseLimits(10, 10, Duration.ofMinutes(30), Duration.ofMinutes(1))) =
        SseConnectionRegistry<String>("test", limits, { it.toString() }) { timeout ->
            behavior(timeout).also(emitters::add)
        }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            assertTrue(System.nanoTime() < deadline, "condition not met in time")
            Thread.sleep(10)
        }
    }

    class RecordingEmitter(
        timeout: Long,
        private val block: CountDownLatch? = null,
        private val fail: Boolean = false,
    ) : SseEmitter(timeout) {
        private val sent = CopyOnWriteArrayList<String>()

        @Volatile var blocked = false

        override fun send(builder: SseEventBuilder) {
            if (fail) throw IOException("client gone")
            if (block != null && sent.isEmpty()) {
                blocked = true
                block.await(5, TimeUnit.SECONDS)
            }
            sent.add(
                builder
                    .build()
                    .joinToString("") { it.data.toString() }
                    .trim()
                    .lines()
                    .joinToString("|"),
            )
        }

        fun events() = sent.filterNot { it.startsWith(":") }

        fun comments() = sent.count { it.startsWith(":") }
    }
}
