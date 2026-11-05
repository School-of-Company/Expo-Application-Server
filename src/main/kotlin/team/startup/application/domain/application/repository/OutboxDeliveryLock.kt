package team.startup.application.domain.application.repository

import com.zaxxer.hikari.HikariDataSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.util.WeakHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

private class DeliveryAdmission(
    size: Int,
) {
    init {
        require(size >= 2) { "Outbox delivery requires a Hikari maximumPoolSize of at least 2" }
    }

    // shortcut: budget two connections per delivery (pool 2 has no spare); reassess if callbacks borrow more.
    val entries = Semaphore(maxOf(1, (size - 1) / 2), true)
    val active = ThreadLocal.withInitial { false }
}

private val deliveryAdmissions = WeakHashMap<HikariDataSource, DeliveryAdmission>()

// A session lock orders remote deliveries without holding a transaction or an Expo mutation lock.
internal fun <T> JdbcTemplate.withDeliveryLock(
    namespace: Int,
    key: String,
    waitSeconds: Long = 0,
    admissionRejected: () -> T? = { null },
    action: (JdbcTemplate) -> T,
): T? {
    val source = requireNotNull(dataSource)
    val admission =
        (source as? HikariDataSource)?.let {
            synchronized(deliveryAdmissions) { deliveryAdmissions.getOrPut(it) { DeliveryAdmission(it.maximumPoolSize) } }
        }
    check(admission?.active?.get() != true) { "Nested outbox delivery must not borrow another connection" }
    val acquired =
        try {
            admission?.entries?.tryAcquire(waitSeconds, TimeUnit.SECONDS) ?: true
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ex
        }
    if (!acquired) return admissionRejected()
    admission?.active?.set(true)
    try {
        return source.connection.use { connection ->
            check(connection.autoCommit) { "Outbox delivery must run outside a transaction" }
            val scoped = JdbcTemplate(SingleConnectionDataSource(connection, true))
            if (scoped.queryForObject("SELECT pg_try_advisory_lock(?, hashtext(?))", Boolean::class.java, namespace, key) != true) {
                return@use null
            }
            try {
                action(scoped)
            } finally {
                scoped.queryForObject("SELECT pg_advisory_unlock(?, hashtext(?))", Boolean::class.java, namespace, key)
            }
        }
    } finally {
        admission?.active?.remove()
        admission?.entries?.release()
    }
}
