package team.startup.application.domain.application.service.impl

import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.util.UUID
import javax.sql.DataSource

/**
 * V28 트리거가 커밋 시점에 보내는 `application_admin_changes` NOTIFY를 인스턴스마다 하나의 연결로 받아 구독자에게 전달한다.
 * 연결이 끊기면 다시 LISTEN 하고, 그 사이 신호를 잃었을 수 있으므로 모든 구독자에게 재조회 신호를 보낸다.
 */
@Component
@ConditionalOnProperty("application.admin-events.enabled", havingValue = "true")
class AdminDataChangeListener(
    private val dataSource: DataSource,
    private val stream: AdminDataChangeStream,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile private var running = false

    @Volatile private var listening = false

    fun isListening() = listening

    private var thread: Thread? = null

    override fun start() {
        running = true
        thread = Thread(::run, "admin-data-change-listener").apply { isDaemon = true }.also(Thread::start)
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(5_000)
        listening = false
    }

    override fun isRunning() = running

    private fun run() {
        var backoff = INITIAL_BACKOFF_MS
        var interrupted = false
        while (running) {
            try {
                dataSource.connection.use { connection ->
                    connection.createStatement().use { it.execute("LISTEN $CHANNEL") }
                    val notifications = connection.unwrap(PGConnection::class.java)
                    listening = true
                    if (interrupted) stream.resyncAll()
                    backoff = INITIAL_BACKOFF_MS
                    while (running) {
                        notifications
                            .getNotifications(POLL_MS)
                            .orEmpty()
                            .mapNotNull { runCatching { UUID.fromString(it.parameter) }.getOrNull() }
                            .distinct()
                            .forEach(stream::changed)
                    }
                }
            } catch (exception: Exception) {
                listening = false
                if (!running) break
                interrupted = true
                log.warn("Admin data change listener disconnected; retrying in {} ms", backoff, exception)
                try {
                    Thread.sleep(backoff)
                } catch (_: InterruptedException) {
                    break
                }
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
        listening = false
    }

    private companion object {
        const val CHANNEL = "application_admin_changes"
        const val POLL_MS = 1_000
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
