package team.startup.application

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.presentation.dto.CancelOwnerApplicationCommand
import team.startup.application.domain.application.presentation.dto.ManagedOwnerParticipant
import team.startup.application.domain.application.presentation.dto.OwnerApplicationParty
import team.startup.application.domain.application.presentation.dto.OwnerApplicationType
import team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition
import team.startup.application.domain.application.presentation.dto.ProgramSessionPolicy
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.repository.OwnerApplicationRepository
import team.startup.application.domain.application.repository.OwnerCancellationDeliveryRepository
import team.startup.application.domain.application.service.impl.AdminAttendanceGateway
import team.startup.application.domain.application.service.impl.AdminCancellationParticipantGateway
import team.startup.application.domain.application.service.impl.AttendanceCancellationAdapter
import team.startup.application.domain.application.service.impl.AttendanceCancellationStatusGateway
import team.startup.application.domain.application.service.impl.OwnerCancellationDeadlineGateway
import team.startup.application.domain.application.service.impl.OwnerCancellationGateway
import team.startup.application.domain.application.service.impl.ProductionOwnerCancellationGuard
import team.startup.application.domain.application.service.impl.RegistrationGateway
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@Testcontainers
class ProductionOwnerCancellationGuardTests {
    private lateinit var server: HttpServer
    private lateinit var guard: ProductionOwnerCancellationGuard
    private lateinit var deliveries: OwnerCancellationDeliveryRepository
    private lateinit var jdbc: JdbcTemplate
    private lateinit var transactions: TransactionTemplate
    private lateinit var owners: OwnerApplicationRepository
    private lateinit var registration: RegistrationGateway
    private val requests = mutableListOf<Pair<String, String>>()

    @Volatile private var acquireCode = 204

    @Volatile private var commitCode = 204

    @Volatile private var abortCode = 204

    @Volatile private var statusCode = 200

    @Volatile private var attendanceRepresentative = false

    @Volatile private var traineeId: Long? = null

    @Volatile private var deadline: String? = "2026-10-13T00:00:00Z"

    @Volatile private var expireAfterAcquire = false

    @Volatile private var changeAfterAcquire = false

    @Volatile private var acquired = false

    @Volatile private var entered = false

    @Volatile private var programAttendees = "[]"

    @BeforeEach
    fun start() {
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        jdbc = JdbcTemplate(source)
        jdbc.execute("DROP TABLE IF EXISTS tb_owner_cancellation_delivery")
        listOf("V14__owner_cancellation_delivery.sql", "V35__owner_cancellation_delivery_session.sql").forEach { migration ->
            jdbc.execute(ClassPathResource("db/migration/$migration").inputStream.bufferedReader().use { it.readText() })
        }
        deliveries = OwnerCancellationDeliveryRepository(jdbc, source)
        transactions = TransactionTemplate(DataSourceTransactionManager(source))
        owners = mock(OwnerApplicationRepository::class.java)
        `when`(owners.cancellationTarget(COMMAND, PARTY)).thenReturn(OwnerApplicationRepository.CancellationTarget("2", 2, 7, "CONFIRMED"))
        registration = mock(RegistrationGateway::class.java)
        for (type in ProgramSessionType.entries) {
            `when`(registration.programDefinition(EXPO.toString(), type, 7)).thenReturn(
                ProgramSessionDefinition(
                    EXPO.toString(),
                    type,
                    7,
                    UUID.randomUUID(),
                    1,
                    ProgramSessionPolicy(
                        UUID.randomUUID(),
                        setOf("TEACHER"),
                        5,
                        5,
                        NOW.minusSeconds(10),
                        NOW.plusSeconds(100),
                        false,
                        NOW.plusSeconds(100),
                        NOW.plusSeconds(100),
                    ),
                ),
            )
        }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            assertEquals("test-token", exchange.requestHeaders.getFirst("X-Internal-Token"))
            val path = exchange.requestURI.path
            val request = exchange.requestBody.bufferedReader().use { it.readText() }
            var code = 200
            val body =
                when {
                    path.endsWith("participant-sessions/resolve") -> {
                        if (acquired && expireAfterAcquire) code = 401
                        """{"expoId":"$EXPO","phoneNumber":"01012345678"}"""
                    }

                    path == "/internal/standard-participants/cancellation-status" -> {
                        """{"participantId":2,"representative":false,"entered":${entered || (acquired && changeAfterAcquire)},"traineeLinked":${traineeId != null},"linkedTraineeId":$traineeId}"""
                    }

                    path == "/internal/standard-participants/admin-cancellation-status" -> {
                        assertEquals("""{"expoId":"$EXPO","participantId":2}""", request)
                        """{"participantId":2,"entered":${entered || (acquired && changeAfterAcquire)},"traineeLinked":false,"linkedTraineeId":null}"""
                    }

                    path.endsWith("/participants/2/cancellation-status") -> {
                        code = statusCode
                        """{"participantId":2,"representative":$attendanceRepresentative,"entered":$entered,"programAttended":false,"traineeLinked":${traineeId != null},"linkedTraineeId":$traineeId,"guardState":null}"""
                    }

                    path.startsWith("/internal/program-attendances/") -> {
                        requests.add(exchange.requestMethod to path)
                        programAttendees
                    }

                    path.endsWith("preregister-sessions/7") -> {
                        """{"id":7,"expoId":"$EXPO","cancelEndedAt":${deadline?.let { "\"$it\"" } ?: "null"}}"""
                    }

                    path.contains("cancellation-guard") -> {
                        requests.add(exchange.requestMethod to path)
                        if (exchange.requestMethod == "PUT") {
                            assertEquals(if (path.contains("admin-")) "" else """{"phoneNumber":"01012345678"}""", request)
                            acquired = true
                        } else if (exchange.requestMethod == "POST") {
                            assertEquals("""{"revision":123}""", request)
                        } else {
                            assertEquals("", request)
                        }
                        code =
                            when (exchange.requestMethod) {
                                "PUT" -> acquireCode
                                "POST" -> commitCode
                                else -> abortCode
                            }
                        ""
                    }

                    else -> {
                        throw AssertionError("Unexpected path: $path")
                    }
                }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(code, if (code == 204) -1 else bytes.size.toLong())
            exchange.responseBody.use { if (code != 204) it.write(bytes) }
        }
        server.start()
        guard = restarted()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    @Test
    fun `local commit durably records remote commit and uses acquired operation`() {
        guard.acquire(COMMAND, 2, PARTY).use { permit ->
            transactions.executeWithoutResult { permit.committed(123) }
            assertEquals("COMMIT", requireNotNull(deliveries.find(COMMAND)).action)
            assertEquals(listOf("PUT"), requests.map { it.first })
        }
        assertEquals(listOf("PUT", "POST"), requests.map { it.first })
        assertEquals(requests[0].second + "/commit", requests[1].second)
        assertEquals(null, deliveries.find(COMMAND))
    }

    @Test
    fun `internal admin acquire uses admin guard and commits the cancellation revision`() {
        guard.acquireAdmin(COMMAND, 2, 7).use { permit ->
            transactions.executeWithoutResult { permit.committed(123) }
            assertEquals("COMMIT", requireNotNull(deliveries.find(COMMAND)).action)
        }
        assertEquals(listOf("PUT", "POST"), requests.map { it.first })
        assertTrue(requests[0].second.contains("/participants/2/admin-cancellation-guard/"))
        assertEquals(requests[0].second.replace("admin-cancellation-guard", "cancellation-guard") + "/commit", requests[1].second)
        assertEquals(null, deliveries.find(COMMAND))
    }

    @Test
    fun `internal admin acquire rejects entry and deadline first and aborts on status change`() {
        entered = true
        failure(HttpStatus.CONFLICT) { guard.acquireAdmin(COMMAND, 2, 7) }
        entered = false
        deadline = NOW.toString()
        failure(HttpStatus.CONFLICT) { guard.acquireAdmin(COMMAND, 2, 7) }
        assertEquals(0, requests.size)
        deadline = "2026-10-13T00:00:00Z"
        changeAfterAcquire = true
        failure(HttpStatus.CONFLICT) { guard.acquireAdmin(COMMAND, 2, 7) }
        assertEquals(listOf("PUT", "DELETE"), requests.map { it.first })
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM tb_owner_cancellation_delivery", Long::class.java))
        failure(HttpStatus.SERVICE_UNAVAILABLE) { restarted(attendanceEnabled = false).acquireAdmin(COMMAND, 2, 7) }
    }

    @Test
    fun `admin program acquire skips entry deadline and attendance guard and rejects only attendance of that program`() {
        entered = true
        deadline = NOW.toString()
        val command = COMMAND.copy(type = OwnerApplicationType.STANDARD)
        guard.acquireAdminProgram(command, 2, 7).use { permit ->
            assertEquals(Instant.MAX, permit.cancelEndedAt)
        }
        assertEquals(listOf("GET" to "/internal/program-attendances/standard/7"), requests)
        programAttendees = """[{"participantId":3,"entryTime":"09:00","leaveTime":null}]"""
        guard.acquireAdminProgram(command, 2, 7).close()
        programAttendees = """[{"participantId":2,"entryTime":"09:00","leaveTime":null}]"""
        failure(HttpStatus.CONFLICT) { guard.acquireAdminProgram(command, 2, 7) }
        assertTrue(requests.none { it.second.contains("cancellation-guard") })
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM tb_owner_cancellation_delivery", Long::class.java))
        failure(HttpStatus.SERVICE_UNAVAILABLE) { restarted(attendanceEnabled = false).acquireAdminProgram(command, 2, 7) }
    }

    @Test
    fun `rollback aborts rather than commits and completes cleanup after supplier acknowledgement`() {
        guard.acquire(COMMAND, 2, PARTY).use { permit ->
            assertThrows(IllegalStateException::class.java) {
                transactions.executeWithoutResult {
                    permit.committed(123)
                    throw IllegalStateException("local cancellation rolled back")
                }
            }
        }
        assertEquals(listOf("PUT", "DELETE"), requests.map { it.first })
        assertEquals(requests[0].second, requests[1].second)
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM tb_owner_cancellation_delivery", Long::class.java))
    }

    @Test
    fun `lost remote commit response recovers same operation after restart`() {
        commitCode = 503
        val permit = guard.acquire(COMMAND, 2, PARTY)
        transactions.executeWithoutResult { permit.committed(123) }
        failure(HttpStatus.SERVICE_UNAVAILABLE) { permit.close() }
        val pending = requireNotNull(deliveries.find(COMMAND))
        assertEquals("COMMIT", pending.action)
        assertEquals(123L, pending.cancellationRevision)
        commitCode = 204
        restarted().recover(COMMAND)
        assertEquals(listOf("PUT", "POST", "POST"), requests.map { it.first })
        assertEquals(requests[1], requests[2])
        assertEquals(null, deliveries.operation(pending.operationId))
    }

    @Test
    fun `attendance representative disagreement blocks before acquisition`() {
        attendanceRepresentative = true
        failure(HttpStatus.CONFLICT) { guard.acquire(COMMAND, 2, PARTY) }
        assertEquals(emptyList<Pair<String, String>>(), requests)
        assertEquals(null, deliveries.find(COMMAND))
    }

    @Test
    fun `superseded commit requires abort acknowledgement before journal cleanup`() {
        val permit = guard.acquire(COMMAND, 2, PARTY)
        transactions.executeWithoutResult { permit.committed(123) }
        commitCode = 409
        abortCode = 503
        failure(HttpStatus.SERVICE_UNAVAILABLE) { permit.close() }
        val pending = requireNotNull(deliveries.find(COMMAND))
        assertEquals("COMMIT", pending.action)
        abortCode = 204
        restarted().recover(COMMAND)
        assertEquals(listOf("PUT", "POST", "DELETE", "POST", "DELETE"), requests.map { it.first })
        assertEquals(null, deliveries.operation(pending.operationId))
    }

    @Test
    fun `committed operation conflict stays pending when abort also conflicts`() {
        val permit = guard.acquire(COMMAND, 2, PARTY)
        transactions.executeWithoutResult { permit.committed(123) }
        commitCode = 409
        abortCode = 409
        failure(HttpStatus.CONFLICT) { permit.close() }
        assertEquals("COMMIT", requireNotNull(deliveries.find(COMMAND)).action)
    }

    @Test
    fun `cleanup waits for uncertain local commit then follows its committed journal decision`() {
        val permit = guard.acquire(COMMAND, 2, PARTY)
        val executor = Executors.newSingleThreadExecutor()
        val monitoring = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
        var closing: Future<*>? = null
        try {
            transactions.executeWithoutResult {
                permit.committed(123)
                closing = executor.submit { permit.close() }
                val timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var waiting = false
                while (!waiting && System.nanoTime() < timeout) {
                    waiting =
                        requireNotNull(
                            monitoring.queryForObject(
                                "SELECT count(*) FROM pg_stat_activity WHERE query LIKE 'UPDATE tb_owner_cancellation_delivery%' " +
                                    "AND wait_event_type = 'Lock'",
                                Long::class.java,
                            ),
                        ) > 0
                    if (!waiting) Thread.sleep(10)
                }
                assertTrue(waiting, "Cleanup must wait on the local transaction's journal row lock")
            }
            requireNotNull(closing).get(10, TimeUnit.SECONDS)
            assertEquals(listOf("PUT", "POST"), requests.map { it.first })
            assertEquals(requests[0].second + "/commit", requests[1].second)
            assertEquals(null, deliveries.find(COMMAND))
        } finally {
            if (closing == null) permit.close()
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `uncertain acquire and failed abort retain original cleanup operation for recovery`() {
        acquireCode = 503
        abortCode = 503
        failure(HttpStatus.SERVICE_UNAVAILABLE) { guard.acquire(COMMAND, 2, PARTY) }
        assertEquals(listOf("PUT", "DELETE"), requests.map { it.first })
        assertEquals("ABORT", jdbc.queryForObject("SELECT action FROM tb_owner_cancellation_delivery", String::class.java))
        abortCode = 204
        jdbc.update("UPDATE tb_owner_cancellation_delivery SET next_attempt_at = now()")
        restarted().recoverPending()
        assertEquals(listOf("PUT", "DELETE", "DELETE"), requests.map { it.first })
        assertEquals(requests[0].second, requests[2].second)
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM tb_owner_cancellation_delivery", Long::class.java))
    }

    @Test
    fun `live permit serializes repeat cancellation until close`() {
        guard.acquire(COMMAND, 2, PARTY).use {
            failure(HttpStatus.CONFLICT) { restarted().acquire(COMMAND, 2, PARTY) }
            assertEquals(listOf("PUT"), requests.map { it.first })
        }
        assertEquals(listOf("PUT", "DELETE"), requests.map { it.first })
    }

    @Test
    fun `unset and equality deadlines reject before attendance mutation`() {
        deadline = null
        failure(HttpStatus.CONFLICT) { guard.acquire(COMMAND, 2, PARTY) }
        deadline = NOW.toString()
        failure(HttpStatus.CONFLICT) { guard.acquire(COMMAND, 2, PARTY) }
        assertEquals(0, requests.size)
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM tb_owner_cancellation_delivery", Long::class.java))
    }

    @Test
    fun `session expiry after fencing aborts before local cancellation`() {
        expireAfterAcquire = true
        failure(HttpStatus.UNAUTHORIZED) { guard.acquire(COMMAND, 2, PARTY) }
        assertEquals(listOf("PUT", "DELETE"), requests.map { it.first })
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM tb_owner_cancellation_delivery", Long::class.java))
    }

    @Test
    fun `changed authoritative status after fencing aborts before local cancellation`() {
        changeAfterAcquire = true
        failure(HttpStatus.CONFLICT) { guard.acquire(COMMAND, 2, PARTY) }
        assertEquals(listOf("PUT", "DELETE"), requests.map { it.first })
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM tb_owner_cancellation_delivery", Long::class.java))
    }

    @Test
    fun `already entered participant is rejected before acquiring attendance guard`() {
        entered = true
        failure(HttpStatus.CONFLICT) { guard.acquire(COMMAND, 2, PARTY) }
        assertEquals(0, requests.size)
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM tb_owner_cancellation_delivery", Long::class.java))
    }

    @Test
    fun `attendance reports entry or program attendance without committing cancellation`() {
        acquireCode = 409
        failure(HttpStatus.CONFLICT) { guard.acquire(COMMAND, 2, PARTY) }
        assertEquals(listOf("PUT", "DELETE"), requests.map { it.first })
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM tb_owner_cancellation_delivery", Long::class.java))
    }

    @Test
    fun `disabled durable attendance delivery cannot permit active cancellation`() {
        failure(HttpStatus.SERVICE_UNAVAILABLE) { restarted(false).acquire(COMMAND, 2, PARTY) }
        assertEquals(0, requests.size)
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM tb_owner_cancellation_delivery", Long::class.java))
    }

    @Test
    fun `standard and training program cancellation abort participant guard after local commit`() {
        for (type in listOf(OwnerApplicationType.STANDARD, OwnerApplicationType.TRAINING)) {
            val command = COMMAND.copy(type = type, applicationId = UUID.randomUUID().toString())
            traineeId = if (type == OwnerApplicationType.TRAINING) 502 else null
            `when`(owners.cancellationTarget(command, PARTY))
                .thenReturn(OwnerApplicationRepository.CancellationTarget(command.applicationId, 2, 7, "CONFIRMED", traineeId))
            guard.acquire(command, 2, PARTY).use { permit ->
                transactions.executeWithoutResult { permit.committed() }
            }
        }
        assertEquals(listOf("PUT", "DELETE", "PUT", "DELETE"), requests.map { it.first })
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM tb_owner_cancellation_delivery", Long::class.java))
    }

    @Test
    fun `legacy supplier without status capability cannot start cancellation`() {
        statusCode = 404
        failure(HttpStatus.SERVICE_UNAVAILABLE) { guard.acquire(COMMAND, 2, PARTY) }
        assertEquals(0, requests.size)
    }

    private fun restarted(attendanceEnabled: Boolean = true): ProductionOwnerCancellationGuard {
        val base = "http://127.0.0.1:${server.address.port}"
        val mapper = jacksonObjectMapper()
        return ProductionOwnerCancellationGuard(
            OwnerCancellationGateway(mapper, base, "test-token"),
            OwnerCancellationDeadlineGateway(mapper, base, "test-token", registration),
            AttendanceCancellationAdapter(mapper, base, "test-token"),
            owners,
            deliveries,
            Clock.fixed(NOW, ZoneOffset.UTC),
            AttendanceCancellationStatusGateway(mapper, base, "test-token"),
            AdminCancellationParticipantGateway(mapper, base, "test-token"),
            AdminAttendanceGateway(mapper, base, "test-token"),
            attendanceEnabled,
        )
    }

    private fun failure(
        expected: HttpStatus,
        action: () -> Unit,
    ) {
        assertEquals(expected, assertThrows(ResponseStatusException::class.java, action).statusCode)
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")

        private val EXPO = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000045")
        private val NOW = Instant.parse("2026-10-12T00:00:00Z")
        private val COMMAND = CancelOwnerApplicationCommand(EXPO, "opaque", OwnerApplicationType.PREREGISTER, "2")
        private val PARTY =
            OwnerApplicationParty(
                listOf(ManagedOwnerParticipant(1, "Owner", null, true), ManagedOwnerParticipant(2, "Companion", null, false)),
            )
    }
}
