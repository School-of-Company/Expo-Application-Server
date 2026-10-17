package team.startup.application.global.sse

import org.slf4j.LoggerFactory
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** SSE 한 건. [id]는 SSE id, [name]은 event 이름, [data]는 JSON으로 직렬화된다. */
data class SseMessage(
    val id: String,
    val name: String,
    val data: Any,
)

data class SseLimits(
    val maxConnections: Int,
    val maxConnectionsPerKey: Int,
    /** 연결 최대 수명. 끝나면 서버가 닫고, 클라이언트는 재연결하면서 권한을 다시 검증받고 GET으로 재동기화한다. */
    val lifetime: Duration,
    val heartbeat: Duration,
    val senderThreads: Int = 2,
) {
    init {
        require(maxConnections > 0 && maxConnectionsPerKey in 1..maxConnections && senderThreads > 0)
        require(!lifetime.isNegative && !lifetime.isZero && !heartbeat.isNegative && !heartbeat.isZero)
    }
}

class SseConnectionLimitException : RuntimeException("SSE 연결 수 한도를 넘었습니다.")

/**
 * 도메인이 공유하는 SSE 연결 관리. 연결 등록·해제, heartbeat, 전송 실패·수명 만료 정리를 한곳에서 맡는다.
 *
 * - 전송은 공용 송신 스레드에서 하며 요청 스레드를 점유하지 않는다.
 * - 연결마다 보내지 않은 데이터 이벤트는 최신 하나만 남긴다(latest-wins). 이벤트는 "다시 조회하라"는 신호이므로
 *   느린 수신자에게 쌓아 두지 않는다. 그래서 연결당 송신 큐는 이벤트 1개와 heartbeat 1개로 제한된다.
 * - heartbeat는 SSE 주석(`:heartbeat`)이며 데이터 이벤트와 구분된다. 도메인 조회를 호출하지 않는다.
 */
class SseConnectionRegistry<K : Any>(
    private val name: String,
    private val limits: SseLimits,
    private val serialize: (Any) -> String,
    private val newEmitter: (timeoutMillis: Long) -> SseEmitter = ::SseEmitter,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val connections = ConcurrentHashMap<K, MutableSet<Connection>>()
    private val total = AtomicInteger()
    private val sender: ExecutorService =
        ThreadPoolExecutor(
            limits.senderThreads,
            limits.senderThreads,
            0,
            TimeUnit.MILLISECONDS,
            // 연결마다 예약된 drain은 최대 하나이므로 큐 크기는 연결 수 한도를 넘지 않는다.
            LinkedBlockingQueue(limits.maxConnections),
            daemonThreads("$name-sse-sender"),
        )
    private val scheduler: ScheduledExecutorService =
        ScheduledThreadPoolExecutor(1, daemonThreads("$name-sse-heartbeat")).apply {
            val period = limits.heartbeat.toMillis()
            scheduleAtFixedRate({
                connections.values.forEach { set ->
                    set.forEach { it.heartbeat() }
                }
            }, period, period, TimeUnit.MILLISECONDS)
        }

    fun open(key: K): SseEmitter {
        val emitter = newEmitter(limits.lifetime.toMillis())
        val connection = Connection(key, emitter)
        synchronized(connections) {
            val current = connections[key]?.size ?: 0
            if (total.get() >= limits.maxConnections || current >= limits.maxConnectionsPerKey) throw SseConnectionLimitException()
            connections.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }.add(connection)
            total.incrementAndGet()
        }
        emitter.onCompletion { connection.close(null) }
        emitter.onTimeout { connection.close(null) }
        emitter.onError { connection.close(it) }
        // 프록시가 응답 헤더를 바로 넘기도록 첫 바이트를 보낸다.
        connection.heartbeat()
        return emitter
    }

    fun publish(
        key: K,
        message: SseMessage,
    ) {
        connections[key]?.forEach { it.offer(message) }
    }

    /** 신호 유실 가능성이 있을 때(예: 전달 경로 재연결) 모든 구독자에게 재조회를 알린다. */
    fun publishAll(message: (K) -> SseMessage) {
        connections.forEach { (key, set) ->
            val event = message(key)
            set.forEach { it.offer(event) }
        }
    }

    fun connectionCount(key: K? = null): Int = if (key == null) total.get() else connections[key]?.size ?: 0

    override fun close() {
        scheduler.shutdownNow()
        connections.values.flatten().forEach { it.close(null) }
        sender.shutdownNow()
    }

    private inner class Connection(
        val key: K,
        val emitter: SseEmitter,
    ) {
        private val pending = AtomicReference<SseMessage?>()
        private val heartbeatDue = AtomicBoolean(false)
        private val scheduled = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)

        fun offer(message: SseMessage) {
            pending.set(message)
            schedule()
        }

        fun heartbeat() {
            heartbeatDue.set(true)
            schedule()
        }

        private fun schedule() {
            if (closed.get() || !scheduled.compareAndSet(false, true)) return
            try {
                sender.execute(::drain)
            } catch (_: RejectedExecutionException) {
                scheduled.set(false)
                close(null)
            }
        }

        private fun drain() {
            try {
                while (!closed.get()) {
                    val message = pending.getAndSet(null)
                    val beat = heartbeatDue.getAndSet(false)
                    when {
                        message != null -> {
                            emitter.send(
                                SseEmitter
                                    .event()
                                    .id(message.id)
                                    .name(message.name)
                                    .data(serialize(message.data)),
                            )
                        }

                        beat -> {
                            emitter.send(SseEmitter.event().comment("heartbeat"))
                        }

                        else -> {
                            break
                        }
                    }
                }
            } catch (exception: Exception) {
                close(exception)
            } finally {
                scheduled.set(false)
                if (!closed.get() && (pending.get() != null || heartbeatDue.get())) schedule()
            }
        }

        fun close(cause: Throwable?) {
            if (!closed.compareAndSet(false, true)) return
            synchronized(connections) {
                connections.computeIfPresent(key) { _, set -> set.apply { remove(this@Connection) }.takeIf { it.isNotEmpty() } }
                total.decrementAndGet()
            }
            if (cause != null) log.debug("SSE connection {} closed: {}", name, cause.toString())
            try {
                emitter.complete()
            } catch (_: Exception) {
                // 이미 끝난 응답이다.
            }
        }
    }

    private fun daemonThreads(prefix: String): ThreadFactory {
        val sequence = AtomicInteger()
        return ThreadFactory { runnable ->
            Thread(runnable, "$prefix-${sequence.incrementAndGet()}").apply { isDaemon = true }
        }
    }
}
