package team.startup.application

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
import team.startup.application.domain.application.repository.SessionAttendanceOutboxRepository
import team.startup.application.domain.application.service.PurgePreregisterSessionsService
import team.startup.application.domain.application.service.impl.SessionAttendanceDispatcher
import team.startup.application.domain.application.service.impl.SessionAttendanceGateway
import team.startup.application.domain.application.service.impl.SessionAttendanceOutbox
import team.startup.application.domain.application.service.impl.SessionAttendanceScheduler
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
        remoteState.clear()
        lateDelivery = CountDownLatch(1)
        responseStatus.set(204)
        deliveryGate.set(null)
    }

    @AfterEach
    fun releaseDelivery() {
        deliveryGate.get()?.second?.countDown()
        requests.groupBy { it.path.substringBefore('?') to it.revision }.values.forEach { deliveries ->
            assertEquals(1, deliveries.map { it.method to it.body }.toSet().size)
        }
    }

    @Test
    fun `확정 취소 재신청을 참가자 ID와 토큰을 포함해 계약 순서대로 발송한다`() {
        inTransaction {
            outbox.confirmed(EXPO, 42, 1)
            outbox.cancelled(EXPO, 42, 1)
            outbox.confirmed(EXPO, 42, 2)
        }
        repeat(3) { assertTrue(dispatcher.dispatchNext()) }
        assertFalse(dispatcher.dispatchNext())
        val path = "/internal/expos/$EXPO/participants/42/preregister-session"
        assertEquals(
            listOf(
                Request("PUT", path, "{\"sessionId\":1,\"revision\":2}", TOKEN, "application/json"),
                Request("DELETE", "$path?revision=5&sessionId=1", "", TOKEN, null),
                Request("PUT", path, "{\"sessionId\":2,\"revision\":6}", TOKEN, "application/json"),
            ),
            requests.toList(),
        )
        assertEquals(0, count())
        assertEquals(6L to 2L, remoteState[path])
    }

    @Test
    fun `현장 재등록은 사전등록 이력이 있는 참가자만 취소 다음 순서로 회차 없는 PUT을 보낸다`() {
        jdbc.update("DELETE FROM tb_preregister_application WHERE expo_id = ?", EXPO)
        jdbc.update(
            "INSERT INTO tb_preregister_session_state (expo_id, session_id, definition) VALUES (?, 1, '{\"startedAt\":\"2000-01-01T00:00:00Z\",\"endedAt\":\"2100-01-01T00:00:00Z\"}') ON CONFLICT DO NOTHING",
            EXPO,
        )
        jdbc.update(
            "INSERT INTO tb_preregister_application (expo_id, session_id, representative_id, participant_id, status) " +
                "VALUES (?, 1, 42, 42, 'CANCELLED'), (?, 1, 44, 44, 'CONFIRMED')",
            EXPO,
            EXPO,
        )
        inTransaction { outbox.cancelled(EXPO, 42, 1) }
        // 43은 사전등록 이력이 없다. 44처럼 다른 날 회차가 확정으로 남아도 회차 제한을 빼도록 보낸다(#261)
        outbox.fieldRegistered(EXPO, listOf(42, 43, 44))
        assertEquals(3, count())

        repeat(3) { assertTrue(dispatcher.dispatchNext()) }
        assertFalse(dispatcher.dispatchNext())
        val path = "/internal/expos/$EXPO/participants/42/preregister-session"
        assertEquals(
            listOf(
                Request("DELETE", "$path?revision=3&sessionId=1", "", TOKEN, null),
                Request("PUT", "$path/field-registration", "{\"revision\":4}", TOKEN, "application/json"),
                Request(
                    "PUT",
                    "/internal/expos/$EXPO/participants/44/preregister-session/field-registration",
                    "{\"revision\":6}",
                    TOKEN,
                    "application/json",
                ),
            ),
            requests.toList(),
        )

        // 박람회가 삭제되면 재등록 대신 같은 메시지의 보상 DELETE로 모든 회차를 푼다
        requests.clear()
        outbox.fieldRegistered(EXPO, listOf(42))
        jdbc.update("INSERT INTO tb_preregister_deleted_expo (expo_id) VALUES (?)", EXPO)
        assertTrue(dispatcher.dispatchNext())
        assertEquals(listOf(Request("DELETE", "$path?revision=9", "", TOKEN, null)), requests.toList())
        assertEquals(0, count())
        jdbc.update("DELETE FROM tb_preregister_application WHERE expo_id = ?", EXPO)
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
            outbox.cancelled(EXPO, 42, 1)
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
            outbox.cancelled(EXPO, 42, 1)
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
            outbox.cancelled(EXPO, 42, 1)
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
            outbox.cancelled(EXPO, 42, 1)
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
        assertEquals(setOf(2L), requests.filter { it.method == "PUT" }.map { it.revision }.toSet())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `타임아웃된 요청이 재시작 뒤 재신청보다 늦게 완료돼도 최신 회차를 유지한다`(cancelled: Boolean) {
        inTransaction {
            if (cancelled) outbox.cancelled(EXPO, 42, 1) else outbox.confirmed(EXPO, 42, 1)
            outbox.cancelled(EXPO, 42, 1)
            outbox.confirmed(EXPO, 42, 2)
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        deliveryGate.set(entered to release)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val timedOut = executor.submit<Boolean> { dispatcher.dispatchNext() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(timedOut.get(12, TimeUnit.SECONDS))
            assertEquals(3, count())
            makeDue()
            val restarted =
                SessionAttendanceDispatcher(
                    SessionAttendanceOutboxRepository(jdbc),
                    SessionAttendanceGateway("http://127.0.0.1:${server.address.port}", TOKEN),
                )
            repeat(3) { assertTrue(restarted.dispatchNext()) }
            val originalRevision = if (cancelled) 3L else 2L
            assertEquals(listOf(originalRevision, originalRevision, 5L, 6L), requests.map { it.revision })
            val path = "/internal/expos/$EXPO/participants/42/preregister-session"
            assertEquals(6L to 2L, remoteState[path])
            release.countDown()
            assertTrue(lateDelivery.await(5, TimeUnit.SECONDS))
            assertEquals(6L to 2L, remoteState[path])
            assertEquals(0, count())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
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
            outbox.cancelled(EXPO, 42, 1)
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
            assertEquals(listOf(2L, 6L, 5L), requests.map { it.revision })
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `발송은 호출자의 DB 트랜잭션 안에서 실행할 수 없다`() {
        inTransaction {
            outbox.cancelled(EXPO, 42, 1)
            outbox.confirmed(EXPO, 42, 2)
        }
        assertThrows(org.springframework.transaction.IllegalTransactionStateException::class.java) {
            inTransaction {
                dispatcher.dispatchNext()
            }
        }
        assertEquals(2, count())
        assertTrue(dispatcher.dispatchNext())
        assertTrue(dispatcher.dispatchNext())
        assertEquals(listOf("DELETE", "PUT"), requests.map { it.method })
    }

    @Test
    fun `HTTP 성공 뒤 DB 완료 실패는 순서 선두를 남기고 동일 통지를 재발송한다`() {
        inTransaction {
            outbox.cancelled(EXPO, 42, 1)
            outbox.confirmed(EXPO, 42, 2)
        }
        jdbc.execute(
            "CREATE FUNCTION fail_outbox_delete() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''completion failed''; END'",
        )
        jdbc.execute(
            "CREATE TRIGGER fail_outbox_delete BEFORE DELETE ON tb_session_attendance_outbox FOR EACH ROW EXECUTE FUNCTION fail_outbox_delete()",
        )
        try {
            assertThrows(org.springframework.dao.DataAccessException::class.java) { dispatcher.dispatchNext() }
            assertEquals(2, count())
            assertEquals(listOf("DELETE"), requests.map { it.method })
        } finally {
            jdbc.execute("DROP TRIGGER fail_outbox_delete ON tb_session_attendance_outbox")
            jdbc.execute("DROP FUNCTION fail_outbox_delete()")
        }
        assertTrue(dispatcher.dispatchNext())
        assertTrue(dispatcher.dispatchNext())
        assertEquals(listOf("DELETE", "DELETE", "PUT"), requests.map { it.method })
        assertEquals(listOf(3L, 3L, 4L), requests.map { it.revision })
    }

    @Test
    fun `같은 참가자의 동시 기록은 이전 트랜잭션 커밋 뒤에 순번을 받는다`() {
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        lateinit var second: Future<*>
        try {
            inTransaction {
                outbox.cancelled(EXPO, 42, 1)
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
            outbox.cancelled(EXPO, 42, 1)
        }
        purge.execute(EXPO)
        assertTrue(dispatcher.dispatchNext())
        assertTrue(dispatcher.dispatchNext())
        assertEquals(listOf("DELETE", "DELETE"), requests.map { it.method })
        assertEquals(listOf(3L, 5L), requests.map { it.revision })
    }

    @Test
    fun `응답 유실 뒤 정리는 PUT과 다른 revision의 DELETE를 재시작 뒤에도 재사용한다`() {
        inTransaction { outbox.confirmed(EXPO, 42, 1) }
        responseStatus.set(0)
        assertFalse(dispatcher.dispatchNext())
        purge.execute(EXPO)
        responseStatus.set(503)
        makeDue()
        assertFalse(dispatcher.dispatchNext())
        responseStatus.set(204)
        makeDue()
        val restarted =
            SessionAttendanceDispatcher(
                SessionAttendanceOutboxRepository(jdbc),
                SessionAttendanceGateway("http://127.0.0.1:${server.address.port}", TOKEN),
            )
        assertTrue(restarted.dispatchNext())
        assertEquals(listOf("PUT", "DELETE"), requests.map { it.method }.distinct())
        assertEquals(listOf(2L, 3L), requests.map { it.revision }.distinct())
        assertEquals(listOf(3L, 3L), requests.takeLast(2).map { it.revision })
        assertEquals(0, count())
    }

    @Test
    fun `박람회 정리는 느린 PUT을 기다리지 않고 발송자는 뒤이어 DELETE로 보정한다`() {
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
            purging.get(2, TimeUnit.SECONDS)
            release.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS))
            purging.get(5, TimeUnit.SECONDS)
            assertTrue(dispatcher.dispatchNext())
            assertEquals(listOf("PUT", "DELETE", "DELETE"), requests.map { it.method })
            assertEquals(listOf(2L, 3L, 5L), requests.map { it.revision })
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
    ) {
        val revision: Long
            get() =
                if (method == "DELETE") {
                    path.substringAfter("?revision=").substringBefore('&').toLong()
                } else {
                    body.substringAfter("\"revision\":").substringBefore('}').toLong()
                }
    }

    companion object {
        private val EXPO = UUID.fromString("b5079e18-1ca0-4b1e-91fc-17faed571af8")
        private const val TOKEN = "attendance-test-internal-token-32-characters"
        private val requests = CopyOnWriteArrayList<Request>()
        private val remoteState = ConcurrentHashMap<String, Pair<Long, Long?>>()
        private var lateDelivery = CountDownLatch(1)
        private val responseStatus = AtomicInteger(204)
        private val deliveryGate = AtomicReference<Pair<CountDownLatch, CountDownLatch>?>()
        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
                createContext("/internal/expos/") { exchange ->
                    val request =
                        Request(
                            exchange.requestMethod,
                            exchange.requestURI.toString(),
                            exchange.requestBody.bufferedReader().readText(),
                            exchange.requestHeaders.getFirst("X-Internal-Token"),
                            exchange.requestHeaders.getFirst("Content-Type"),
                        )
                    requests.add(request)
                    val gate = deliveryGate.getAndSet(null)
                    gate?.first?.countDown()
                    gate?.second?.await(30, TimeUnit.SECONDS)
                    val sessionId =
                        if (request.method == "DELETE" || !request.body.contains("\"sessionId\":")) {
                            null
                        } else {
                            request.body
                                .substringAfter("\"sessionId\":")
                                .substringBefore(',')
                                .toLong()
                        }
                    val key = exchange.requestURI.path
                    var status = responseStatus.get()
                    if (status == 204 || status == 0) {
                        remoteState.compute(key) { _, previous ->
                            if (previous == null || previous.first < request.revision) {
                                request.revision to sessionId
                            } else {
                                if (previous.first == request.revision && previous.second != sessionId) status = 409
                                previous
                            }
                        }
                    }
                    if (gate != null) lateDelivery.countDown()
                    if (status != 0) exchange.sendResponseHeaders(status, -1)
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
