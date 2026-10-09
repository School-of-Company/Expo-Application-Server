package team.startup.application

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.service.PurgePreregisterSessionsService
import team.startup.application.domain.application.service.impl.SessionAttendanceDispatcher
import team.startup.application.domain.application.service.impl.SessionAttendanceOutbox
import team.startup.application.domain.application.service.impl.SessionAttendanceScheduler
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "application.attendance.enabled=true",
        "application.attendance.poll-delay-ms=3600000",
    ],
)
@Testcontainers
class SessionAttendanceOutboxTests {
    @Autowired private lateinit var outbox: SessionAttendanceOutbox

    @Autowired private lateinit var dispatcher: SessionAttendanceDispatcher

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @Autowired private lateinit var purge: PurgePreregisterSessionsService

    @Autowired private lateinit var scheduler: SessionAttendanceScheduler

    @BeforeEach
    fun reset() {
        jdbc.execute("TRUNCATE TABLE tb_session_attendance_outbox RESTART IDENTITY")
        jdbc.execute("TRUNCATE TABLE tb_preregister_deleted_expo")
        requests.clear()
        responseStatus.set(204)
        deliveryGate.set(null)
    }

    @AfterEach
    fun releaseDelivery() {
        deliveryGate.get()?.second?.countDown()
    }

    @Test
    fun `확정 취소 재신청을 참가자 ID와 토큰을 포함해 계약 순서대로 발송한다`() {
        inTransaction {
            outbox.confirmed(EXPO, 42, 1)
            outbox.cancelled(EXPO, 42)
            outbox.confirmed(EXPO, 42, 2)
        }
        repeat(3) { assertTrue(dispatcher.dispatchNext()) }
        assertFalse(dispatcher.dispatchNext())
        val path = "/internal/expos/$EXPO/participants/42/preregister-session"
        assertEquals(
            listOf(
                Request("PUT", path, "{\"sessionId\":1}", TOKEN, "application/json"),
                Request("DELETE", path, "", TOKEN, null),
                Request("PUT", path, "{\"sessionId\":2}", TOKEN, "application/json"),
            ),
            requests.toList(),
        )
        assertEquals(0, count())
    }

    @Test
    fun `신청 트랜잭션 롤백은 발송 기록도 롤백하고 단독 저장은 거부한다`() {
        assertThrows(IllegalTransactionStateException::class.java) { outbox.confirmed(EXPO, 42, 1) }
        assertThrows(IllegalStateException::class.java) {
            inTransaction {
                outbox.confirmed(EXPO, 42, 1)
                error("allocation rolled back")
            }
        }
        assertEquals(0, count())
        assertFalse(dispatcher.dispatchNext())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `미커밋 확정은 발송하지 않는다`() {
        inTransaction {
            outbox.confirmed(EXPO, 42, 1)
            val executor = Executors.newSingleThreadExecutor()
            try {
                assertFalse(executor.submit<Boolean> { dispatcher.dispatchNext() }.get(5, TimeUnit.SECONDS))
            } finally {
                executor.shutdownNow()
            }
        }
        assertTrue(dispatcher.dispatchNext())
    }

    @Test
    fun `5xx는 지속 저장하고 후속 취소를 막으며 다른 참가자는 발송한다`() {
        inTransaction {
            outbox.confirmed(EXPO, 42, 1)
            outbox.cancelled(EXPO, 42)
            outbox.confirmed(EXPO, 43, 1)
        }
        responseStatus.set(503)
        assertFalse(dispatcher.dispatchNext())
        assertEquals(3, count())
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM tb_session_attendance_outbox WHERE id = 1", Int::class.java))
        assertEquals(
            "Attendance HTTP 503",
            jdbc.queryForObject("SELECT last_error FROM tb_session_attendance_outbox WHERE id = 1", String::class.java),
        )
        responseStatus.set(204)
        assertTrue(dispatcher.dispatchNext())
        assertFalse(dispatcher.dispatchNext())
        assertTrue(requests[1].path.contains("/participants/43/"))
        makeDue()
        assertTrue(dispatcher.dispatchNext())
        assertTrue(dispatcher.dispatchNext())
        assertEquals(listOf("PUT", "PUT", "PUT", "DELETE"), requests.map { it.method })
        assertEquals(0, count())
    }

    @Test
    fun `404와 인증 오류 및 예상 밖 성공 응답도 유실시키지 않는다`() {
        inTransaction {
            outbox.confirmed(EXPO, 42, 1)
            outbox.cancelled(EXPO, 42)
        }
        for (status in listOf(404, 401, 429, 200)) {
            responseStatus.set(status)
            makeDue()
            assertTrue(dispatcher.dispatchNext())
            assertFalse(dispatcher.dispatchNext())
            assertEquals(2, count())
        }
        assertTrue(requests.all { it.method == "PUT" })
    }

    @Test
    fun `취소 실패는 재신청 PUT보다 먼저 재시도한다`() {
        inTransaction {
            outbox.cancelled(EXPO, 42)
            outbox.confirmed(EXPO, 42, 2)
        }
        responseStatus.set(500)
        dispatcher.dispatchNext()
        assertFalse(dispatcher.dispatchNext())
        responseStatus.set(204)
        makeDue()
        dispatcher.dispatchNext()
        dispatcher.dispatchNext()
        assertEquals(listOf("DELETE", "DELETE", "PUT"), requests.map { it.method })
    }

    @Test
    fun `응답 유실도 재시도하며 후속 취소를 먼저 보내지 않는다`() {
        inTransaction {
            outbox.confirmed(EXPO, 42, 1)
            outbox.cancelled(EXPO, 42)
        }
        responseStatus.set(0)
        assertFalse(dispatcher.dispatchNext())
        assertFalse(dispatcher.dispatchNext())
        assertEquals(2, count())
        assertTrue(requests.all { it.method == "PUT" })
        assertEquals(
            "Attendance connection failure",
            jdbc.queryForObject("SELECT last_error FROM tb_session_attendance_outbox WHERE id = 1", String::class.java),
        )
        responseStatus.set(204)
        makeDue()
        assertTrue(dispatcher.dispatchNext())
        assertTrue(dispatcher.dispatchNext())
        assertEquals("DELETE", requests.last().method)
    }

    @Test
    fun `연결 장애가 나면 스케줄러는 다음 참가자를 발송하지 않고 다음 폴링에서 복구한다`() {
        inTransaction {
            outbox.confirmed(EXPO, 42, 1)
            outbox.confirmed(EXPO, 43, 1)
        }
        responseStatus.set(0)
        scheduler.dispatch()
        assertTrue(requests.isNotEmpty())
        assertTrue(requests.all { it.path.contains("/participants/42/") })
        assertEquals(listOf(1, 0), jdbc.query("SELECT attempts FROM tb_session_attendance_outbox ORDER BY id", { rs, _ -> rs.getInt(1) }))
        assertEquals(2, count())
        responseStatus.set(204)
        makeDue()
        scheduler.dispatch()
        assertEquals(0, count())
        assertTrue(requests.last().path.contains("/participants/43/"))
    }

    @Test
    fun `재시도 횟수가 커져도 대기 시간은 5분으로 제한한다`() {
        inTransaction { outbox.confirmed(EXPO, 42, 1) }
        jdbc.update("UPDATE tb_session_attendance_outbox SET attempts = 2147483647")
        responseStatus.set(503)
        dispatcher.dispatchNext()
        assertEquals(Int.MAX_VALUE, jdbc.queryForObject("SELECT attempts FROM tb_session_attendance_outbox", Int::class.java))
        val seconds =
            jdbc.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (next_attempt_at - CURRENT_TIMESTAMP)) FROM tb_session_attendance_outbox",
                Double::class.java,
            )!!
        assertTrue(seconds in 295.0..300.0)
    }

    @Test
    fun `동시 발송기는 진행 중인 참가자의 후속 요청을 건너뛴다`() {
        inTransaction {
            outbox.confirmed(EXPO, 42, 1)
            outbox.cancelled(EXPO, 42)
            outbox.confirmed(EXPO, 43, 1)
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        deliveryGate.set(entered to release)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<Boolean> { dispatcher.dispatchNext() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(dispatcher.dispatchNext())
            assertFalse(dispatcher.dispatchNext())
            assertEquals(2, requests.size)
            assertTrue(requests[1].path.contains("/participants/43/"))
            release.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS))
            assertTrue(dispatcher.dispatchNext())
            assertEquals("DELETE", requests.last().method)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `응답 성공 후 로컬 롤백은 같은 요청을 재발송한 뒤 후속 요청을 보낸다`() {
        inTransaction {
            outbox.cancelled(EXPO, 42)
            outbox.confirmed(EXPO, 42, 2)
        }
        assertThrows(IllegalStateException::class.java) {
            inTransaction {
                assertTrue(dispatcher.dispatchNext())
                error("commit failed")
            }
        }
        assertEquals(2, count())
        assertTrue(dispatcher.dispatchNext())
        assertTrue(dispatcher.dispatchNext())
        assertEquals(listOf("DELETE", "DELETE", "PUT"), requests.map { it.method })
    }

    @Test
    fun `같은 참가자의 동시 기록은 이전 트랜잭션 커밋 뒤에 순번을 받는다`() {
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        lateinit var second: Future<*>
        try {
            inTransaction {
                outbox.cancelled(EXPO, 42)
                second =
                    executor.submit {
                        inTransaction {
                            started.countDown()
                            outbox.confirmed(EXPO, 42, 2)
                        }
                    }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                assertThrows(TimeoutException::class.java) { second.get(200, TimeUnit.MILLISECONDS) }
            }
            second.get(5, TimeUnit.SECONDS)
            assertTrue(dispatcher.dispatchNext())
            assertTrue(dispatcher.dispatchNext())
            assertEquals(listOf("DELETE", "PUT"), requests.map { it.method })
        } finally {
            executor.shutdownNow()
        }
    }

    private fun inTransaction(action: () -> Unit) {
        TransactionTemplate(transactionManager).executeWithoutResult { action() }
    }

    @Test
    fun `정리된 박람회의 미발송 PUT은 QR 취소 DELETE로 발송한다`() {
        inTransaction {
            outbox.confirmed(EXPO, 42, 1)
            outbox.cancelled(EXPO, 42)
        }
        purge.execute(EXPO)
        assertTrue(dispatcher.dispatchNext())
        assertTrue(dispatcher.dispatchNext())
        assertEquals(listOf("DELETE", "DELETE"), requests.map { it.method })
    }

    @Test
    fun `박람회 정리는 진행 중인 PUT을 기다리고 정리 후 새 PUT을 보내지 않는다`() {
        inTransaction {
            outbox.confirmed(EXPO, 42, 1)
            outbox.confirmed(EXPO, 43, 1)
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val purgeStarted = CountDownLatch(1)
        deliveryGate.set(entered to release)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<Boolean> { dispatcher.dispatchNext() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val purging =
                executor.submit {
                    purgeStarted.countDown()
                    purge.execute(EXPO)
                }
            assertTrue(purgeStarted.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { purging.get(200, TimeUnit.MILLISECONDS) }
            release.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS))
            purging.get(5, TimeUnit.SECONDS)
            assertTrue(dispatcher.dispatchNext())
            assertEquals(listOf("PUT", "DELETE"), requests.map { it.method })
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun makeDue() {
        jdbc.update("UPDATE tb_session_attendance_outbox SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'")
    }

    private fun count(): Int = jdbc.queryForObject("SELECT COUNT(*) FROM tb_session_attendance_outbox", Int::class.java)!!

    data class Request(
        val method: String,
        val path: String,
        val body: String,
        val token: String?,
        val contentType: String?,
    )

    companion object {
        private val EXPO = UUID.fromString("b5079e18-1ca0-4b1e-91fc-17faed571af8")
        private const val TOKEN = "attendance-test-internal-token-32-characters"
        private val requests = CopyOnWriteArrayList<Request>()
        private val responseStatus = AtomicInteger(204)
        private val deliveryGate = AtomicReference<Pair<CountDownLatch, CountDownLatch>?>()
        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
                createContext("/internal/expos/") { exchange ->
                    requests.add(
                        Request(
                            exchange.requestMethod,
                            exchange.requestURI.path,
                            exchange.requestBody.bufferedReader().readText(),
                            exchange.requestHeaders.getFirst("X-Internal-Token"),
                            exchange.requestHeaders.getFirst("Content-Type"),
                        ),
                    )
                    val gate = deliveryGate.getAndSet(null)
                    gate?.first?.countDown()
                    gate?.second?.await(10, TimeUnit.SECONDS)
                    if (responseStatus.get() != 0) exchange.sendResponseHeaders(responseStatus.get(), -1)
                    exchange.close()
                }
                start()
            }

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun attendanceProperties(registry: DynamicPropertyRegistry) {
            registry.add("application.attendance.url") { "http://127.0.0.1:${server.address.port}" }
            registry.add("application.attendance.internal-token") { TOKEN }
        }
    }
}
