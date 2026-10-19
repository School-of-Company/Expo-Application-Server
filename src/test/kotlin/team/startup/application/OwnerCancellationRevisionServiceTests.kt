package team.startup.application

import com.sun.net.httpserver.HttpServer
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.presentation.dto.ApplyPreregisterSessionCommand
import team.startup.application.domain.application.presentation.dto.PreregisterSessionDefinition
import team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition
import team.startup.application.domain.application.presentation.dto.ProgramSessionPolicy
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.service.ApplyPreregisterSessionService
import team.startup.application.domain.application.service.impl.SessionAttendanceDispatcher
import team.startup.application.domain.application.service.impl.SessionAttendanceOutbox
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "application.attendance.enabled=true",
        "application.attendance.poll-delay-ms=3600000",
        "application.owner.cancellation-recovery-delay-ms=3600000",
        "application.program-sessions.enabled=true",
        "application.program-sessions.reconcile-delay=3600000",
    ],
)
@AutoConfigureMockMvc
@Testcontainers
class OwnerCancellationRevisionServiceTests {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var transactions: TransactionTemplate

    @Autowired private lateinit var preregister: PreregisterSessionRepository

    @Autowired private lateinit var apply: ApplyPreregisterSessionService

    @Autowired private lateinit var outbox: SessionAttendanceOutbox

    @Autowired private lateinit var dispatcher: SessionAttendanceDispatcher

    @BeforeEach
    fun reset() {
        jdbc.execute(
            "TRUNCATE tb_owner_cancellation_delivery, tb_program_session_event, tb_program_session_history, tb_program_session_receipt, " +
                "tb_program_session_application, tb_program_session_state, tb_program_change_receipt, tb_standard_program_application, " +
                "tb_training_program_application, tb_training_application_version, tb_training_operation_receipt, " +
                "tb_preregister_application, tb_preregister_transition, tb_preregister_request, tb_preregister_reservation, " +
                "tb_preregister_session_state, tb_preregister_session_change, tb_preregister_deleted_expo, " +
                "tb_session_attendance_outbox RESTART IDENTITY CASCADE",
        )
        transactions.executeWithoutResult {
            preregister.saveDefinition(SESSION)
            preregister.insert(EXPO, 1, 1, 1, "CONFIRMED")
            preregister.insert(EXPO, 1, 1, 2, "CONFIRMED")
        }
        requests.clear()
        guards.clear()
        cancellationRevisions.clear()
        assignments.clear()
        aborted.clear()
        linkedTraineeId = null
        statusCode = 200
        commitCode = 204
        commitGate.set(null)
    }

    @AfterEach
    fun release() {
        commitGate.getAndSet(null)?.second?.countDown()
    }

    @Test
    fun `companion and representative cancellation store exact outbox revision and repeat only once`() {
        assertEquals(409, cancel("PREREGISTER", "1"))
        assertEquals(204, cancel("PREREGISTER", "2"))
        val revision =
            requireNotNull(
                jdbc.queryForObject("SELECT id * 2 + 1 FROM tb_session_attendance_outbox WHERE participant_id = 2", Long::class.java),
            )
        assertEquals(revision, MAPPER.readTree(requests.last { it.path.endsWith("/commit") }.body).path("revision").asLong())
        assertEquals(204, cancel("PREREGISTER", "2"))
        assertEquals(204, cancel("PREREGISTER", "1"))
        assertEquals(0L, count("tb_owner_cancellation_delivery"))
        assertEquals(2L, count("tb_session_attendance_outbox"))
        assertEquals(4L, count("tb_preregister_transition"))
        assertEquals("CANCELLED", state(1))
        assertEquals("CANCELLED", state(2))
    }

    @Test
    fun `standard cancellation aborts fence after real local deletion and retains preregistration`() {
        val id = program(ProgramSessionType.STANDARD)
        assertEquals(204, cancel("STANDARD", id.toString()))
        assertEquals(0L, count("tb_standard_program_application"))
        assertEquals(
            "CANCELLED",
            jdbc.queryForObject("SELECT status FROM tb_program_session_application WHERE id = ?", String::class.java, id),
        )
        assertEquals(2L, count("tb_program_session_history"))
        assertEquals("CONFIRMED", state(2))
        assertEquals(listOf("PUT", "DELETE"), guardRequests().map { it.method })
        assertEquals(0L, count("tb_owner_cancellation_delivery"))
        assertEquals(0L, count("tb_session_attendance_outbox"))
    }

    @Test
    fun `training cancellation retains trainee snapshot and increments version once before abort`() {
        val id = program(ProgramSessionType.TRAINING)
        assertEquals(204, cancel("TRAINING", id.toString()))
        assertEquals(204, cancel("TRAINING", id.toString()))
        assertEquals(0L, count("tb_training_program_application"))
        assertEquals(
            2L,
            jdbc.queryForObject("SELECT version FROM tb_training_application_version WHERE trainee_id = 502", Long::class.java),
        )
        assertEquals(502L, jdbc.queryForObject("SELECT trainee_id FROM tb_program_session_application WHERE id = ?", Long::class.java, id))
        assertEquals(2L, count("tb_program_session_history"))
        assertEquals(listOf("PUT", "DELETE"), guardRequests().map { it.method })
        assertEquals("CONFIRMED", state(2))
    }

    @Test
    fun `failed postcommit response replays original operation and cancellation revision without local duplication`() {
        commitCode = 503
        assertEquals(503, cancel("PREREGISTER", "2"))
        assertEquals("CANCELLED", state(2))
        assertEquals(1L, count("tb_owner_cancellation_delivery"))
        commitCode = 204
        assertEquals(204, cancel("PREREGISTER", "2"))
        val commits = guardRequests().filter { it.method == "POST" }
        assertEquals(2, commits.size)
        assertEquals(commits[0], commits[1])
        assertEquals(3L, count("tb_preregister_transition"))
        assertEquals(1L, count("tb_session_attendance_outbox"))
        assertEquals(0L, count("tb_owner_cancellation_delivery"))
    }

    @Test
    fun `old queued assignment after commit cannot release cancellation under merged revision contract`() {
        transactions.executeWithoutResult { outbox.confirmed(EXPO, 2, 1) }
        assertEquals(204, cancel("PREREGISTER", "2"))
        assertTrue(dispatcher.dispatchNext())
        assertTrue(dispatcher.dispatchNext())
        assertFalse(dispatcher.dispatchNext())
        assertEquals(5L to null, assignments[2])
        assertEquals("COMMITTED", guards[2]?.first)
    }

    @Test
    fun `new assignment during held fence wins over late commit under merged revision contract`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        commitGate.set(entered to release)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val cancellation = executor.submit<Int> { cancel("PREREGISTER", "2") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            apply.execute(ApplyPreregisterSessionCommand(EXPO, 1, "reapply", 1, listOf(2)))
            repeat(2) { assertTrue(dispatcher.dispatchNext()) }
            assertEquals(4L to 1L, assignments[2])
            assertEquals("HELD", guards[2]?.first)
            release.countDown()
            assertEquals(204, cancellation.get(10, TimeUnit.SECONDS))
            assertEquals(4L to 1L, assignments[2])
            assertEquals(null, guards[2])
            assertEquals(204, cancel("PREREGISTER", "2"))
            assertEquals(
                1L,
                jdbc.queryForObject(
                    "SELECT count(*) FROM tb_preregister_application WHERE participant_id = 2 AND status = 'CONFIRMED'",
                    Long::class.java,
                ),
            )
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `lost commit response then newer assignment reconciles superseded operation without repeating cancellation`() {
        commitCode = 503
        assertEquals(503, cancel("PREREGISTER", "2"))
        apply.execute(ApplyPreregisterSessionCommand(EXPO, 1, "reapply", 1, listOf(2)))
        repeat(2) { assertTrue(dispatcher.dispatchNext()) }
        assertEquals(4L to 1L, assignments[2])
        assertEquals(null, guards[2])
        commitCode = 204
        assertEquals(204, cancel("PREREGISTER", "2"))
        assertEquals(listOf("PUT", "POST", "POST", "DELETE"), guardRequests().map { it.method })
        assertEquals(0L, count("tb_owner_cancellation_delivery"))
        assertEquals("CANCELLED", state(2))
        assertEquals(4L to 1L, assignments[2])
        assertEquals(
            1L,
            jdbc.queryForObject(
                "SELECT count(*) FROM tb_preregister_application WHERE participant_id = 2 AND status = 'CONFIRMED'",
                Long::class.java,
            ),
        )
    }

    @Test
    fun `legacy supplier without read only status blocks before acquire and local writes`() {
        statusCode = 404
        assertEquals(503, cancel("PREREGISTER", "2"))
        assertTrue(guardRequests().isEmpty())
        assertEquals("CONFIRMED", state(2))
        assertEquals(0L, count("tb_owner_cancellation_delivery"))
        assertEquals(0L, count("tb_session_attendance_outbox"))
    }

    @Test
    fun `local active program rejection aborts acquired fence without cancelling preregistration`() {
        program(ProgramSessionType.STANDARD)
        assertEquals(409, cancel("PREREGISTER", "2"))
        assertEquals(listOf("PUT", "DELETE"), guardRequests().map { it.method })
        assertEquals("CONFIRMED", state(2))
        assertEquals(0L, count("tb_owner_cancellation_delivery"))
        assertEquals(0L, count("tb_session_attendance_outbox"))
    }

    private fun cancel(
        type: String,
        id: String,
    ): Int =
        mvc
            .perform(
                post("/application/expos/$EXPO/my-applications/$type/$id/cancellation")
                    .with(csrf())
                    .cookie(Cookie("PARTICIPANT_SESSION", "owner")),
            ).andReturn()
            .response.status

    private fun program(type: ProgramSessionType): UUID {
        val id = UUID.randomUUID()
        val definition = PROGRAM.copy(programType = type)
        val trainee = if (type == ProgramSessionType.TRAINING) 502L else null
        linkedTraineeId = trainee
        transactions.executeWithoutResult {
            jdbc.update(
                "INSERT INTO tb_program_session_application " +
                    "(id,expo_id,program_type,program_id,group_id,participant_id,trainee_id,status," +
                    "definition_snapshot,form_id,form_version,questions_snapshot,answers_snapshot) " +
                    "VALUES (?,?,?,1,?,2,?,'CONFIRMED',?::jsonb,?,1,'[]','{}')",
                id,
                EXPO,
                type.name,
                definition.groupId,
                trainee,
                MAPPER.writeValueAsString(definition),
                FORM,
            )
            jdbc.update("INSERT INTO tb_program_session_history (application_id,status) VALUES (?,'CONFIRMED')", id)
            if (trainee == null) {
                jdbc.update("INSERT INTO tb_standard_program_application (participant_id,standard_program_id) VALUES (2,1)")
            } else {
                jdbc.update("INSERT INTO tb_training_program_application (trainee_id,training_program_id) VALUES (502,1)")
                jdbc.update("INSERT INTO tb_training_application_version (trainee_id,version) VALUES (502,1)")
            }
        }
        return id
    }

    private fun state(id: Long): String? =
        jdbc.queryForObject("SELECT status FROM tb_preregister_application WHERE id = ?", String::class.java, id)

    private fun count(table: String): Long? = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)

    private fun guardRequests(): List<Request> = requests.filter { it.path.contains("cancellation-guard") }

    private data class Request(
        val method: String,
        val path: String,
        val body: String,
    )

    companion object {
        private val EXPO = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000045")
        private val FORM = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000046")
        private val START = Instant.parse("2020-01-01T00:00:00Z")
        private val END = Instant.parse("2100-01-01T00:00:00Z")
        private val MAPPER = ObjectMapper()
        private val SESSION = PreregisterSessionDefinition(1, EXPO, "Session", START, END, "Place", 5, false, 1, END)
        private val PROGRAM =
            ProgramSessionDefinition(
                EXPO.toString(),
                ProgramSessionType.STANDARD,
                1,
                FORM,
                1,
                ProgramSessionPolicy(FORM, setOf("TEACHER"), 1, 5, START, END, false, END, END),
            )
        private val requests = CopyOnWriteArrayList<Request>()
        private val guards = ConcurrentHashMap<Long, Pair<String, UUID>>()
        private val cancellationRevisions = ConcurrentHashMap<UUID, Long>()
        private val assignments = ConcurrentHashMap<Long, Pair<Long, Long?>>()
        private val aborted = ConcurrentHashMap.newKeySet<UUID>()
        private val commitGate = AtomicReference<Pair<CountDownLatch, CountDownLatch>?>()
        private val remoteLock = Any()

        @Volatile private var linkedTraineeId: Long? = null

        @Volatile private var statusCode = 200

        @Volatile private var commitCode = 204
        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
                createContext("/") { exchange ->
                    val path = exchange.requestURI.path
                    val body = exchange.requestBody.bufferedReader().use { it.readText() }
                    var code = if (exchange.requestHeaders.getFirst("X-Internal-Token") == "test-token") 200 else 401
                    var response = "{}"
                    if (code == 200) {
                        when {
                            path.endsWith("participant-sessions/resolve") -> {
                                response = """{"expoId":"$EXPO","phoneNumber":"01012345678","expiresAt":"2100-01-01T00:00:00"}"""
                            }

                            path.endsWith("/managed") -> {
                                response =
                                    """
                                    {"registered":true,"participants":[
                                    {"participantId":1,"name":"Owner","occupation":"TEACHER","representative":true},
                                    {"participantId":2,"name":"Companion","occupation":null,"representative":false}]}
                                    """.trimIndent()
                            }

                            path.endsWith("standard-participants/cancellation-status") -> {
                                val id = MAPPER.readTree(body).path("participantId").asLong()
                                val trainee = if (id == 2L) linkedTraineeId else null
                                response =
                                    """
                                    {"participantId":$id,"representative":${id == 1L},"entered":false,
                                    "traineeLinked":${trainee != null},"linkedTraineeId":$trainee}
                                    """.trimIndent()
                            }

                            path.contains("/preregister-sessions/") -> {
                                response = MAPPER.writeValueAsString(SESSION)
                            }

                            path.contains("/standard-programs/") -> {
                                response = MAPPER.writeValueAsString(PROGRAM)
                            }

                            path.endsWith("training-programs/batch") -> {
                                response =
                                    MAPPER.writeValueAsString(listOf(PROGRAM.copy(programType = ProgramSessionType.TRAINING)))
                            }

                            path.endsWith("/cancellation-status") -> {
                                val id = path.split('/')[5].toLong()
                                val trainee = if (id == 2L) linkedTraineeId else null
                                code = statusCode
                                response =
                                    """
                                    {"participantId":$id,"representative":${id == 1L},"entered":false,"programAttended":false,
                                    "traineeLinked":${trainee != null},"linkedTraineeId":$trainee,
                                    "guardState":${guards[id]?.first?.let { "\"$it\"" } ?: "null"}}
                                    """.trimIndent()
                            }

                            path.contains("/cancellation-guard/") -> {
                                requests.add(Request(exchange.requestMethod, path, body))
                                val id = path.split('/')[5].toLong()
                                val operation = UUID.fromString(path.split('/')[7])
                                if (exchange.requestMethod ==
                                    "POST"
                                ) {
                                    commitGate.getAndSet(null)?.let { (entered, release) ->
                                        entered.countDown()
                                        assertTrue(release.await(10, TimeUnit.SECONDS))
                                    }
                                }
                                code =
                                    synchronized(remoteLock) {
                                        when (exchange.requestMethod) {
                                            "PUT" -> {
                                                if (operation in aborted ||
                                                    (guards[id] != null && guards[id]?.second != operation)
                                                ) {
                                                    409
                                                } else {
                                                    guards[id] = "HELD" to operation
                                                    204
                                                }
                                            }

                                            "DELETE" -> {
                                                if (guards[id] == ("COMMITTED" to operation)) {
                                                    409
                                                } else {
                                                    aborted.add(operation)
                                                    if (guards[id] == ("HELD" to operation)) guards.remove(id)
                                                    204
                                                }
                                            }

                                            else -> {
                                                val revision = MAPPER.readTree(body).path("revision").asLong()
                                                if (guards[id]?.second != operation) {
                                                    409
                                                } else if (guards[id]?.first == "COMMITTED") {
                                                    commitCode
                                                } else {
                                                    cancellationRevisions[operation] = revision
                                                    val active = assignments[id]
                                                    if (active != null && active.first > revision && active.second != null) {
                                                        aborted.add(operation)
                                                        guards.remove(id)
                                                    } else {
                                                        guards[id] = "COMMITTED" to operation
                                                    }
                                                    commitCode
                                                }
                                            }
                                        }
                                    }
                            }

                            path.endsWith("/preregister-session") -> {
                                requests.add(Request(exchange.requestMethod, exchange.requestURI.toString(), body))
                                val id = path.split('/')[5].toLong()
                                val revision =
                                    if (exchange.requestMethod ==
                                        "DELETE"
                                    ) {
                                        exchange.requestURI.query
                                            .substringAfter("revision=")
                                            .substringBefore('&')
                                            .toLong()
                                    } else {
                                        MAPPER.readTree(body).path("revision").asLong()
                                    }
                                val session =
                                    if (exchange.requestMethod ==
                                        "DELETE"
                                    ) {
                                        null
                                    } else {
                                        MAPPER.readTree(body).path("sessionId").asLong()
                                    }
                                synchronized(remoteLock) {
                                    if ((assignments[id]?.first ?: 0) < revision) {
                                        assignments[id] = revision to session
                                        val guard = guards[id]
                                        if (session != null && guard?.first == "COMMITTED" &&
                                            revision > requireNotNull(cancellationRevisions[guard.second])
                                        ) {
                                            guards.remove(id)
                                        }
                                    }
                                }
                                code = 204
                            }

                            else -> {
                                code = 404
                            }
                        }
                    }
                    val bytes = response.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(code, if (code == 204) -1 else bytes.size.toLong())
                    exchange.responseBody.use { if (code != 204) it.write(bytes) }
                }
                start()
            }

        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")

        @DynamicPropertySource @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("application.registration.expo-url") { "http://127.0.0.1:${server.address.port}" }
            registry.add("application.registration.user-url") { "http://127.0.0.1:${server.address.port}" }
            registry.add("application.attendance.url") { "http://127.0.0.1:${server.address.port}" }
            registry.add("application.registration.expo-internal-token") { "test-token" }
            registry.add("application.registration.user-internal-token") { "test-token" }
            registry.add("application.attendance.internal-token") { "test-token" }
        }
    }
}
