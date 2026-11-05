package team.startup.application

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.repository.FieldVisitRequestRepository
import team.startup.application.domain.application.repository.withDeliveryLock
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class OutboxDeliveryPoolTests {
    private fun pool(size: Int) =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = size
                minimumIdle = 0
                connectionTimeout = 1000
            },
        )

    @Test
    fun `다른 잠금 경로가 두번째 연결을 빼앗지 않고 다음 실행에서 재개한다`() {
        pool(2).use { ds ->
            val jdbc = JdbcTemplate(ds)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val holder =
                    executor.submit<Int?> {
                        jdbc.withDeliveryLock(38, "attendance") { scoped ->
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                            scoped.queryForObject("SELECT 1", Int::class.java)
                        }
                    }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val competing =
                    executor.submit<Int?> {
                        JdbcTemplate(ds).withDeliveryLock(49, "notification") {
                            jdbc.queryForObject("SELECT 2", Int::class.java)
                        }
                    }
                assertNull(competing.get(5, TimeUnit.SECONDS))
                release.countDown()
                assertEquals(1, holder.get(5, TimeUnit.SECONDS))
                assertEquals(2, jdbc.withDeliveryLock(49, "notification") { jdbc.queryForObject("SELECT 2", Int::class.java) })
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `FIELD와 모든 outbox namespace는 저장소 인스턴스와 관계없이 같은 상한을 쓴다`() {
        pool(5).use { ds ->
            val jdbc = JdbcTemplate(ds)
            val requests = FieldVisitRequestRepository(jdbc, jacksonObjectMapper())
            val entered = CountDownLatch(2)
            val release = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val holders =
                    listOf(
                        executor.submit<Int?> {
                            requests.withLock(UUID.randomUUID(), "field") {
                                entered.countDown()
                                check(release.await(10, TimeUnit.SECONDS))
                                jdbc.queryForObject("SELECT 1", Int::class.java)
                            }
                        },
                        executor.submit<Int?> {
                            JdbcTemplate(ds).withDeliveryLock(39, "count") {
                                entered.countDown()
                                check(release.await(10, TimeUnit.SECONDS))
                                jdbc.queryForObject("SELECT 1", Int::class.java)
                            }
                        },
                    )
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals(2, ds.hikariPoolMXBean.activeConnections)
                for (namespace in listOf(38, 39, 47, 48, 49)) {
                    assertNull(JdbcTemplate(ds).withDeliveryLock(namespace, "overflow") { error("must skip") })
                }
                assertNull(
                    FieldVisitRequestRepository(JdbcTemplate(ds), jacksonObjectMapper()).tryWithLock(UUID.randomUUID(), "other") {
                        error("must skip")
                    },
                )
                assertEquals(1, jdbc.queryForObject("SELECT 1", Int::class.java))
                release.countDown()
                holders.forEach { assertEquals(1, it.get(5, TimeUnit.SECONDS)) }
                for (namespace in listOf(38, 39, 47, 48, 49)) {
                    assertEquals(
                        namespace,
                        jdbc.withDeliveryLock(namespace, "resumed") { scoped ->
                            assertEquals(1, ds.hikariPoolMXBean.activeConnections)
                            scoped.queryForObject("SELECT ?::int", Int::class.java, namespace)
                        },
                    )
                }
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `풀 1은 연결 획득 전에 명시적으로 거절하고 풀 2는 계속 처리한다`() {
        pool(1).use { ds ->
            val ex =
                assertThrows<IllegalArgumentException> { JdbcTemplate(ds).withDeliveryLock(49, "unsupported") { error("must reject") } }
            assertTrue(ex.message.orEmpty().contains("at least 2"))
            assertEquals(0, ds.hikariPoolMXBean.activeConnections)
        }
        pool(2).use { ds ->
            repeat(3) {
                assertEquals(
                    1,
                    JdbcTemplate(ds).withDeliveryLock(49, "supported") { JdbcTemplate(ds).queryForObject("SELECT 1", Int::class.java) },
                )
            }
        }
    }

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
