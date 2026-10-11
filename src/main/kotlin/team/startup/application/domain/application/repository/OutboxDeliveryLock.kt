package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource

// A session lock orders remote deliveries without holding a transaction or an Expo mutation lock.
internal fun <T> JdbcTemplate.withDeliveryLock(
    namespace: Int,
    key: String,
    action: (JdbcTemplate) -> T,
): T? =
    requireNotNull(dataSource).connection.use { connection ->
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
