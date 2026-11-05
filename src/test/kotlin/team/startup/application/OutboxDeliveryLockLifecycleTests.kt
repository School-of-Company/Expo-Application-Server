package team.startup.application

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.web.server.ResponseStatusException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.repository.FieldVisitRequestRepository
import team.startup.application.domain.application.repository.withDeliveryLock
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.sql.SQLTransientConnectionException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class OutboxDeliveryLockLifecycleTests {
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
    fun `예외와 재진입 거절과 잠금 충돌은 연결과 허가를 반환한다`() {
        pool(2).use { ds ->
            val jdbc = JdbcTemplate(ds)
            repeat(3) {
                assertThrows<IllegalStateException> {
                    jdbc.withDeliveryLock(49, "failure") { error("callback failed") }
                }
                jdbc.withDeliveryLock(49, "outer") {
                    assertThrows<IllegalStateException> { jdbc.withDeliveryLock(38, "nested") { error("must reject") } }
                }
            }
            ds.connection.use { blocker ->
                blocker.createStatement().use { it.execute("SELECT pg_advisory_lock(38, hashtext('busy'))") }
                repeat(3) { assertNull(jdbc.withDeliveryLock(38, "busy") { error("locked by another session") }) }
                blocker.createStatement().use { it.execute("SELECT pg_advisory_unlock(38, hashtext('busy'))") }
            }
            assertEquals(1, jdbc.withDeliveryLock(49, "outer") { it.queryForObject("SELECT 1", Int::class.java) })
            assertEquals(0, ds.hikariPoolMXBean.activeConnections)
        }
    }

    @Test
    fun `대기 취소와 선행 인터럽트는 허가를 누수하지 않고 대기자는 해제 후 재개한다`() {
        pool(2).use { ds ->
            val jdbc = JdbcTemplate(ds)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            val waiting = AtomicReference<Thread>()
            val interrupted = CountDownLatch(1)
            try {
                val holder =
                    executor.submit {
                        jdbc.withDeliveryLock(38, "holder") {
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                        }
                    }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val cancelled =
                    executor.submit {
                        waiting.set(Thread.currentThread())
                        try {
                            jdbc.withDeliveryLock(49, "cancelled", 5) { error("must cancel before entry") }
                        } catch (ex: InterruptedException) {
                            assertTrue(Thread.currentThread().isInterrupted)
                            interrupted.countDown()
                        }
                    }
                awaitWaiting(waiting)
                assertTrue(cancelled.cancel(true))
                assertTrue(interrupted.await(5, TimeUnit.SECONDS))
                waiting.set(null)
                val nextEntered = CountDownLatch(1)
                val nextRelease = CountDownLatch(1)
                val next =
                    executor.submit<Int?> {
                        waiting.set(Thread.currentThread())
                        jdbc.withDeliveryLock(49, "next", 5) {
                            nextEntered.countDown()
                            check(nextRelease.await(5, TimeUnit.SECONDS))
                            7
                        }
                    }
                awaitWaiting(waiting)
                assertNull(jdbc.withDeliveryLock(47, "no-barging") { error("must skip") })
                release.countDown()
                holder.get(5, TimeUnit.SECONDS)
                assertTrue(nextEntered.await(5, TimeUnit.SECONDS))
                nextRelease.countDown()
                assertEquals(7, next.get(5, TimeUnit.SECONDS))
                executor
                    .submit {
                        Thread.currentThread().interrupt()
                        assertThrows<InterruptedException> { jdbc.withDeliveryLock(38, "interrupted") { error("must reject") } }
                        assertTrue(Thread.interrupted())
                    }.get(5, TimeUnit.SECONDS)
                assertEquals(1, jdbc.withDeliveryLock(38, "resumed") { 1 })
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    private fun awaitWaiting(thread: AtomicReference<Thread>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.get()?.state != Thread.State.TIMED_WAITING) {
            check(System.nanoTime() < deadline) { "Admission waiter did not park" }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
        }
    }

    @Test
    fun `연결 획득 실패도 허가를 반환한다`() {
        pool(2).use { ds ->
            val jdbc = JdbcTemplate(ds)
            ds.connection.use {
                ds.connection.use {
                    assertThrows<SQLTransientConnectionException> { jdbc.withDeliveryLock(38, "no-connection") { error("must fail") } }
                }
            }
            assertEquals(1, jdbc.withDeliveryLock(38, "resumed") { it.queryForObject("SELECT 1", Int::class.java) })
        }
    }

    @Test
    fun `포화로 건너뛴 스케줄러는 다음 주기에서 처리한다`() {
        pool(2).use { ds ->
            val jdbc = JdbcTemplate(ds)
            val skipped = CountDownLatch(1)
            val delivered = CountDownLatch(1)
            val scheduler = ThreadPoolTaskScheduler()
            scheduler.setWaitForTasksToCompleteOnShutdown(true)
            scheduler.setAwaitTerminationSeconds(5)
            scheduler.initialize()
            try {
                jdbc.withDeliveryLock(75, "field") {
                    scheduler.scheduleWithFixedDelay({
                        val result =
                            jdbc.withDeliveryLock(
                                38,
                                "attendance",
                            ) { scoped -> scoped.queryForObject("SELECT 1", Int::class.java) }
                        if (result == null) skipped.countDown() else delivered.countDown()
                    }, Duration.ofMillis(10))
                    assertTrue(skipped.await(5, TimeUnit.SECONDS))
                    assertEquals(1L, delivered.count)
                }
                assertTrue(delivered.await(5, TimeUnit.SECONDS))
            } finally {
                scheduler.shutdown()
            }
        }
    }

    @Test
    fun `FIELD 실행 중 취소해도 세션 잠금과 공통 허가를 반환한다`() {
        pool(2).use { ds ->
            val requests = FieldVisitRequestRepository(JdbcTemplate(ds), jacksonObjectMapper())
            val expoId = UUID.randomUUID()
            val entered = CountDownLatch(1)
            val stopped = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val task =
                    executor.submit {
                        val failure =
                            assertThrows<ResponseStatusException> {
                                requests.withLock(expoId, "cancelled") {
                                    entered.countDown()
                                    CountDownLatch(1).await()
                                }
                            }
                        assertEquals(503, failure.statusCode.value())
                        assertTrue(Thread.currentThread().isInterrupted)
                        stopped.countDown()
                    }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertTrue(task.cancel(true))
                assertTrue(stopped.await(5, TimeUnit.SECONDS))
                assertEquals(1, requests.withLock(expoId, "cancelled") { 1 })
                assertEquals(0, ds.hikariPoolMXBean.activeConnections)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `먼저 기다린 진입자가 나중 대기자보다 먼저 허가를 얻는다`() {
        pool(2).use { ds ->
            val jdbc = JdbcTemplate(ds)
            val executor = Executors.newFixedThreadPool(2)
            val firstEntered = CountDownLatch(1)
            val firstRelease = CountDownLatch(1)
            val order = CopyOnWriteArrayList<Int>()
            val jobs = mutableListOf<java.util.concurrent.Future<*>>()
            try {
                jdbc.withDeliveryLock(38, "holder") {
                    for (index in 1..2) {
                        val waiting = AtomicReference<Thread>()
                        jobs +=
                            executor.submit {
                                waiting.set(Thread.currentThread())
                                jdbc.withDeliveryLock(49, "waiter-$index", 5) {
                                    order.add(index)
                                    if (index == 1) {
                                        firstEntered.countDown()
                                        check(firstRelease.await(5, TimeUnit.SECONDS))
                                    }
                                }
                            }
                        awaitWaiting(waiting)
                    }
                }
                assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
                assertEquals(listOf(1), order.toList())
                firstRelease.countDown()
                jobs.forEach { it.get(5, TimeUnit.SECONDS) }
                assertEquals(listOf(1, 2), order.toList())
            } finally {
                firstRelease.countDown()
                executor.shutdownNow()
            }
        }
    }

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
