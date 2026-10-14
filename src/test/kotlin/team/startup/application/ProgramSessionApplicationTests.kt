package team.startup.application

import com.sun.net.httpserver.HttpServer
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.ApplyProgramSessionCommand
import team.startup.application.domain.application.presentation.dto.ProgramFormSnapshot
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.ProgramWaitlistRepository
import team.startup.application.domain.application.repository.StandardProgramApplicationRepository
import team.startup.application.domain.application.repository.TrainingOperationRepository
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import team.startup.application.domain.application.service.ApplyProgramSessionService
import team.startup.application.domain.application.service.DeleteStandardProgramApplicationsService
import team.startup.application.domain.application.service.ProgramFormProvider
import team.startup.application.domain.application.service.impl.ApplyProgramSessionServiceImpl
import team.startup.application.domain.application.service.impl.ProgramFormAdapter
import team.startup.application.domain.application.service.impl.ProgramWaitlist
import team.startup.application.domain.application.service.impl.ProgramWaitlistScheduler
import team.startup.application.domain.application.service.impl.RegistrationGateway
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(properties = ["eureka.client.enabled=false"])
@AutoConfigureMockMvc
@Testcontainers
@Import(ProgramSessionApplicationTests.Forms::class)
class ProgramSessionApplicationTests {
    @Autowired private lateinit var service: ApplyProgramSessionService

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var context: ApplicationContext

    @Autowired private lateinit var transactions: TransactionTemplate

    @BeforeEach
    fun reset() {
        jdbc.execute(
            "TRUNCATE tb_program_session_event, tb_program_session_state, tb_program_change_receipt, " +
                "tb_program_session_history, tb_program_session_receipt, tb_program_session_application, " +
                "tb_standard_program_application, tb_training_program_application, tb_training_application_version, " +
                "tb_training_operation_receipt, tb_deleted_standard_program, tb_deleted_training_program, " +
                "tb_preregister_application, tb_preregister_session_state RESTART IDENTITY",
        )
        jdbc.update("INSERT INTO tb_preregister_session_state (expo_id, session_id, definition) VALUES (?, 1, '{}')", UUID.fromString(EXPO))
        jdbc.update(
            "INSERT INTO tb_preregister_application (expo_id, session_id, representative_id, participant_id, status) " +
                "VALUES (?, 1, 1, 1, 'CONFIRMED'), (?, 1, 1, 2, 'CONFIRMED')",
            UUID.fromString(EXPO),
            UUID.fromString(EXPO),
        )
        linkCalls.set(0)
        linkRequests.clear()
        occupation = "TEACHER"
        occupations = emptyMap()
        allowed = "[\"TEACHER\"]"
        channels = null
        closed = false
        expired = false
        badExpo = false
        pending = false
        formMode = "valid"
        formQuestions = QUESTIONS
        sessionResponse = null
        occupationResponse = null
        missingProgram = null
        noPolicyProgram = null
        definitionConflict = false
        batchCalls.set(0)
        checkTraineeLock = false
        lockChecks.set(0)
        capacity = 1
        waitingCapacity = 0
        revision = 1
        applicationEnd = Instant.parse("2100-01-01T00:00:00Z")
        promotionEnd = applicationEnd
        cancelEnd = applicationEnd
        formEnd = applicationEnd
        testClock.current = Instant.parse("2026-10-11T10:00:00Z")
        linkFailure = false
        linkCompletedAt = null
        delayedLinkParticipant = null
        title = "Session"
        unavailableLookup = null
    }

    @Test
    fun `public entry uses real Form contract and atomically stores answers snapshot and receipt`() {
        formMode = "real"
        val path = "/application/expos/$EXPO/program-sessions/STANDARD/1/applications"

        fun request(answer: String) =
            post(path)
                .with(csrf())
                .cookie(Cookie("PARTICIPANT_SESSION", "session"))
                .header("Idempotency-Key", "real-form")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"participantId":1,"formVersion":1,"answers":{"101":"$answer"}}""")
        mvc.perform(request("missing-option")).andExpect(status().isBadRequest)
        assertEquals(0L, count("tb_program_session_application"))
        assertEquals(0L, count("tb_program_session_receipt"))
        mvc.perform(request("a")).andExpect(status().isCreated)
        assertEquals(1L, count("tb_program_session_application"))
        assertEquals(1L, count("tb_program_session_receipt"))
        assertEquals(1L, count("tb_program_session_history"))
        val stored = jdbc.queryForObject("SELECT questions_snapshot::text FROM tb_program_session_application", String::class.java)
        assertEquals(mapper.readTree(REAL_FIELDS), mapper.readTree(stored))
        mvc.perform(request("a")).andExpect(status().isCreated)
        assertEquals(1L, count("tb_program_session_application"))
    }

    @Test
    fun `public program entry authenticates participant cookie validates request and protects csrf`() {
        val path = "/application/expos/$EXPO/program-sessions/STANDARD/1/applications"
        val body = """{"participantId":1,"formVersion":1,"answers":{"101":"a"}}"""

        fun request() = post(path).contentType(MediaType.APPLICATION_JSON).content(body).header("Idempotency-Key", "public")
        mvc
            .perform(request().cookie(Cookie("PARTICIPANT_SESSION", "session")))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("CSRF_REQUIRED"))
        mvc
            .perform(request().with(csrf()).with(user("admin")).header("X-Internal-Token", "test-token"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("SESSION_REQUIRED"))
        mvc
            .perform(request().with(csrf()).cookie(Cookie("PARTICIPANT_SESSION", "expired")))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"))
        mvc
            .perform(request().with(csrf()).cookie(Cookie("PARTICIPANT_SESSION", "session")))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
        mvc
            .perform(request().with(csrf()).cookie(Cookie("PARTICIPANT_SESSION", "renewed-session")))
            .andExpect(status().isCreated)
        assertEquals(1L, count("tb_program_session_application"))
        mvc
            .perform(request().content(body.replace("\"a\"", "\"b\"")).with(csrf()).cookie(Cookie("PARTICIPANT_SESSION", "session")))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("APPLICATION_CONFLICT"))
        mvc
            .perform(
                request()
                    .content(
                        body.replace("\"participantId\":1", "\"participantId\":0"),
                    ).with(csrf())
                    .cookie(Cookie("PARTICIPANT_SESSION", "session")),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_APPLICATION"))
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    fun `browser can fetch and submit csrf token without Spring authenticated user`() {
        val result = mvc.perform(get("/application/program-session-csrf")).andExpect(status().isOk).andReturn()
        val token = mapper.readTree(result.response.contentAsString)
        val csrfCookie = requireNotNull(result.response.getCookie("XSRF-TOKEN"))
        mvc
            .perform(
                post("/application/expos/$EXPO/program-sessions/TRAINING/1/applications")
                    .cookie(csrfCookie, Cookie("PARTICIPANT_SESSION", "session"))
                    .header(token.path("headerName").stringValue(), token.path("token").stringValue())
                    .header("Idempotency-Key", "browser")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"participantId":1,"formVersion":1,"answers":{"101":"a"}}"""),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.traineeId").value(501))
        assertEquals(1, linkCalls.get())
    }

    @Test
    fun `success stores immutable snapshots receipt history and existing applied lookup`() {
        val receipt = service.execute(command())
        val replay = service.execute(command().copy(sessionId = "renewed-session"))
        assertEquals(receipt, replay)
        assertEquals(1L, count("tb_program_session_application"))
        assertEquals(1L, count("tb_program_session_receipt"))
        assertEquals(1L, count("tb_program_session_history"))
        val stored = jdbc.queryForObject("SELECT questions_snapshot::text FROM tb_program_session_application", String::class.java)
        assertEquals(mapper.readTree(QUESTIONS), mapper.readTree(stored))
        mvc
            .perform(get("/internal/program-applications/standard/1/participants/1").header("X-Internal-Token", "test-token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.applied").value(true))
        assertThrows(ProgramApplicationConflictException::class.java) {
            service.execute(command().copy(answers = mapper.readTree("""{"101":"b"}""")))
        }
        assertEquals(0, linkCalls.get())
    }

    @Test
    fun `concurrent last seat different sessions and same key serialize in PostgreSQL`() {
        val seat = concurrent(command(), command(participant = 2))
        assertEquals(listOf(1, 1), listOf(seat.count { it == "ok" }, seat.count { it == "conflict" }))
        reset()
        val group = concurrent(command(), command(program = 2, key = "other"))
        assertEquals(1, group.count { it == "ok" })
        assertEquals(1, group.count { it == "conflict" })
        reset()
        assertEquals(listOf("ok", "ok"), concurrent(command(), command()))
        assertEquals(1L, count("tb_program_session_application"))
    }

    @Test
    fun `ownership stored occupation periods and form identity cannot be bypassed`() {
        assertEquals(
            404,
            assertThrows(ResponseStatusException::class.java) { service.execute(command(participant = 99)) }.statusCode.value(),
        )
        assertEquals(
            401,
            assertThrows(ResponseStatusException::class.java) {
                service.execute(command().copy(sessionId = "expired"))
            }.statusCode.value(),
        )
        occupation = "GENERAL"
        assertEquals(403, assertThrows(ResponseStatusException::class.java) { service.execute(command()) }.statusCode.value())
        occupation = "TEACHER"
        for (mode in listOf("version", "expo", "purpose", "period")) {
            formMode = mode
            assertThrows(ProgramApplicationConflictException::class.java) { service.execute(command()) }
        }
        formMode = "valid"
        closed = true
        assertThrows(ProgramApplicationConflictException::class.java) { service.execute(command()) }
        closed = false
        expired = true
        assertThrows(ProgramApplicationConflictException::class.java) { service.execute(command()) }
        expired = false
        pending = true
        assertEquals(409, assertThrows(ResponseStatusException::class.java) { service.execute(command()) }.statusCode.value())
        pending = false
        badExpo = true
        assertEquals(502, assertThrows(ResponseStatusException::class.java) { service.execute(command()) }.statusCode.value())
        assertEquals(0L, count("tb_program_session_application"))
        assertEquals(0, linkCalls.get())
    }

    @Test
    fun `training creates links only after acceptance and preserves QR and version compatibility`() {
        val receipt = service.execute(command(type = ProgramSessionType.TRAINING))
        assertEquals(501L, receipt.traineeId)
        assertEquals(receipt, service.execute(command(type = ProgramSessionType.TRAINING)))
        assertEquals(1, linkCalls.get())
        assertThrows(ProgramApplicationConflictException::class.java) {
            service.execute(command(type = ProgramSessionType.TRAINING, participant = 2))
        }
        assertEquals(1, linkCalls.get())
        mvc
            .perform(get("/internal/program-applications/training/1/trainees/501").header("X-Internal-Token", "test-token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.applied").value(true))
        mvc
            .perform(get("/internal/training-program-applications/trainee/501/version").header("X-Internal-Token", "test-token"))
            .andExpect(jsonPath("$.version").value(1))
        mvc
            .perform(
                put("/internal/training-program-applications/trainee/501")
                    .header("X-Internal-Token", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"trainee":{"id":501,"expoId":"$EXPO"},"programs":[]}"""),
            ).andExpect(status().isNoContent)
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM tb_program_session_application", String::class.java))
        assertEquals(1, linkCalls.get())
    }

    @Test
    fun `legacy HTTP cannot bypass policy or overbook new application`() {
        for (path in listOf("standard", "training")) {
            val person = if (path == "standard") "participant" else "trainee"
            val category = if (path == "training") ",\"category\":\"CHOICE\"" else ""
            mvc
                .perform(
                    post("/internal/$path-program-applications")
                        .header("X-Internal-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"$person":{"id":2,"expoId":"$EXPO"},"programs":[{"id":1,"expoId":"$EXPO"$category}]}"""),
                ).andExpect(status().isConflict)
        }
        service.execute(command())
        assertEquals(1L, count("tb_standard_program_application"))
    }

    @Test
    fun `DB prevents active duplicate and partial local writes roll back`() {
        service.execute(command())
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update(
                "INSERT INTO tb_program_session_application " +
                    "SELECT ?, expo_id, program_type, 2, group_id, participant_id, trainee_id, 'WAITING', " +
                    "definition_snapshot, form_id, form_version, questions_snapshot, answers_snapshot, created_at FROM tb_program_session_application",
                UUID.randomUUID(),
            )
        }
        reset()
        jdbc.execute("ALTER TABLE tb_program_session_history ADD CONSTRAINT test_reject_history CHECK (status <> 'CONFIRMED')")
        try {
            assertThrows(DataIntegrityViolationException::class.java) { service.execute(command()) }
            for (table in listOf(
                "tb_program_session_application",
                "tb_program_session_receipt",
                "tb_program_session_history",
                "tb_standard_program_application",
            )) {
                assertEquals(0L, count(table), table)
            }
        } finally {
            jdbc.execute("ALTER TABLE tb_program_session_history DROP CONSTRAINT test_reject_history")
        }
    }

    @Test
    fun `missing production Form adapter fails closed without local or remote creation`() {
        val unavailable =
            ApplyProgramSessionServiceImpl(
                context.getBean(ProgramSessionApplicationRepository::class.java),
                context.getBean(StandardProgramApplicationRepository::class.java),
                context.getBean(TrainingProgramApplicationRepository::class.java),
                context.getBean(TrainingOperationRepository::class.java),
                context.getBean(RegistrationGateway::class.java),
                DefaultListableBeanFactory().getBeanProvider(ProgramFormProvider::class.java),
                context.getBean(ProgramWaitlist::class.java),
                context.getBean(ProgramWaitlistRepository::class.java),
            )
        val error =
            assertThrows(ResponseStatusException::class.java) {
                transactions.execute { unavailable.execute(command(type = ProgramSessionType.TRAINING)) }
            }
        assertEquals(503, error.statusCode.value())
        assertEquals(0L, count("tb_program_session_application"))
        assertEquals(0, linkCalls.get())
    }

    @Test
    fun `waiting preregistrations are forbidden by schema and cancelled or absent are not eligible`() {
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update("UPDATE tb_preregister_application SET status = 'WAITING' WHERE participant_id = 1")
        }
        jdbc.update("UPDATE tb_preregister_application SET status = 'CANCELLED' WHERE participant_id = 1")
        val error = assertThrows(ResponseStatusException::class.java) { service.execute(command(type = ProgramSessionType.TRAINING)) }
        assertEquals(403, error.statusCode.value())
        jdbc.update("DELETE FROM tb_preregister_application WHERE participant_id = 1")
        assertEquals(403, assertThrows(ResponseStatusException::class.java) { service.execute(command()) }.statusCode.value())
        assertEquals(0, linkCalls.get())
        assertEquals(0L, count("tb_program_session_application"))
    }

    @Test
    fun `legacy group membership and malformed answers cannot create a second application`() {
        jdbc.update("INSERT INTO tb_standard_program_application (participant_id, standard_program_id) VALUES (1, 2)")
        assertThrows(ProgramApplicationConflictException::class.java) { service.execute(command()) }
        assertEquals(0L, count("tb_program_session_application"))
        reset()
        val invalid = command().copy(answers = mapper.readTree("""{"999":"unknown"}"""))
        assertEquals(400, assertThrows(ResponseStatusException::class.java) { service.execute(invalid) }.statusCode.value())
        assertEquals(0L, count("tb_standard_program_application"))
    }

    @Test
    fun `unknown trainee lock never deadlocks and retry preserves the remote link`() {
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val holder =
                pool.submit {
                    transactions.execute {
                        context.getBean(TrainingProgramApplicationRepository::class.java).lockTrainee(501)
                        locked.countDown()
                        assertTrue(release.await(30, TimeUnit.SECONDS))
                    }
                }
            assertTrue(locked.await(10, TimeUnit.SECONDS))
            assertThrows(ProgramApplicationConflictException::class.java) { service.execute(command(type = ProgramSessionType.TRAINING)) }
            assertEquals(0L, count("tb_training_program_application"))
            assertEquals(1, linkCalls.get())
            release.countDown()
            holder.get(10, TimeUnit.SECONDS)
            assertEquals(501L, service.execute(command(type = ProgramSessionType.TRAINING)).traineeId)
            assertEquals(2, linkCalls.get())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `concurrent legacy delete and new admission leave no applied relation`() {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val apply =
                pool.submit {
                    assertTrue(start.await(10, TimeUnit.SECONDS))
                    try {
                        service.execute(command())
                    } catch (_: ProgramApplicationConflictException) {
                    }
                }
            val delete =
                pool.submit {
                    assertTrue(start.await(10, TimeUnit.SECONDS))
                    context.getBean(DeleteStandardProgramApplicationsService::class.java).execute(1)
                }
            start.countDown()
            apply.get(30, TimeUnit.SECONDS)
            delete.get(30, TimeUnit.SECONDS)
            assertEquals(0L, count("tb_standard_program_application"))
            assertEquals(
                0L,
                jdbc.queryForObject("SELECT count(*) FROM tb_program_session_application WHERE status = 'CONFIRMED'", Long::class.java),
            )
        } finally {
            pool.shutdownNow()
        }
    }

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)

    @Test
    fun `malformed supplier fields become bad gateway rather than node conversion failures`() {
        for (response in listOf("{}", """{"expoId":7,"phoneNumber":"01012345678"}""")) {
            sessionResponse = response
            assertEquals(502, assertThrows(ResponseStatusException::class.java) { service.execute(command()) }.statusCode.value())
        }
        sessionResponse = null
        occupationResponse = "17"
        assertEquals(502, assertThrows(ResponseStatusException::class.java) { service.execute(command()) }.statusCode.value())
        assertEquals(0L, count("tb_program_session_application"))
    }

    @Test
    fun `removed legacy program does not block an unrelated admission`() {
        jdbc.update("INSERT INTO tb_standard_program_application (participant_id, standard_program_id) VALUES (1, 7)")
        missingProgram = 7
        service.execute(command())
        assertEquals(1L, count("tb_program_session_application"))
    }

    @Test
    fun `legacy add and replace cannot add a policyless sibling of an active group`() {
        service.execute(command())
        service.execute(command(type = ProgramSessionType.TRAINING, key = "training"))
        noPolicyProgram = 2
        mvc
            .perform(
                post("/internal/standard-program-applications")
                    .header("X-Internal-Token", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"participant":{"id":1,"expoId":"$EXPO"},"programs":[{"id":2,"expoId":"$EXPO"}]}"""),
            ).andExpect(status().isConflict)
        val body = """{"trainee":{"id":501,"expoId":"$EXPO"},"programs":[{"id":2,"expoId":"$EXPO","category":"CHOICE"}]}"""
        mvc
            .perform(
                post("/internal/training-program-applications")
                    .header("X-Internal-Token", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isConflict)
        val retained =
            """{"trainee":{"id":501,"expoId":"$EXPO"},"programs":[""" +
                """{"id":1,"expoId":"$EXPO","category":"CHOICE"},{"id":2,"expoId":"$EXPO","category":"CHOICE"}]}"""
        mvc
            .perform(
                put("/internal/training-program-applications/trainee/501")
                    .header("X-Internal-Token", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(retained),
            ).andExpect(status().isConflict)
        assertEquals(1L, count("tb_training_program_application"))
        assertEquals(1L, count("tb_standard_program_application"))
    }

    @Test
    fun `legacy training definitions use one batch call for multiple programs`() {
        noPolicyProgram = 0
        mvc
            .perform(
                post("/internal/training-program-applications")
                    .header("X-Internal-Token", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"trainee":{"id":501,"expoId":"$EXPO"},"programs":[{"id":1,"expoId":"$EXPO","category":"CHOICE"},{"id":2,"expoId":"$EXPO","category":"CHOICE"}]}""",
                    ),
            ).andExpect(status().isConflict)
        assertEquals(1, batchCalls.get())
        assertEquals(0L, count("tb_training_program_application"))
    }

    @Test
    fun `definition conflicts identify program changes`() {
        definitionConflict = true
        val error = assertThrows(ResponseStatusException::class.java) { service.execute(command()) }
        assertEquals(409, error.statusCode.value())
        assertEquals("프로그램 정책 변경 중입니다.", error.reason)
    }

    @Test
    fun `invalid UUID is rejected before suppliers and case variants replay the same receipt`() {
        val error = assertThrows(ResponseStatusException::class.java) { service.execute(command().copy(expoId = "not-a-uuid")) }
        assertEquals(400, error.statusCode.value())
        val receipt = service.execute(command())
        assertEquals(receipt, service.execute(command().copy(expoId = EXPO.uppercase())))
        assertEquals(1L, count("tb_program_session_receipt"))
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update(
                "INSERT INTO tb_program_session_application " +
                    "SELECT ?, UPPER(expo_id::text)::uuid, program_type, 2, group_id, participant_id, trainee_id, 'CONFIRMED', " +
                    "definition_snapshot, form_id, form_version, questions_snapshot, answers_snapshot, created_at " +
                    "FROM tb_program_session_application",
                UUID.randomUUID(),
            )
        }
    }

    @Test
    fun `existing training batch isolates deleted IDs without accepting missing requested programs`() {
        missingProgram = 7
        val gateway = context.getBean(RegistrationGateway::class.java)
        assertEquals(listOf(8L), gateway.programDefinitions(EXPO, ProgramSessionType.TRAINING, listOf(7, 8), existing = true).map { it.id })
        assertEquals(3, batchCalls.get())
        val error =
            assertThrows(ResponseStatusException::class.java) {
                gateway.programDefinitions(EXPO, ProgramSessionType.TRAINING, listOf(7, 8))
            }
        assertEquals(404, error.statusCode.value())
    }

    @Test
    fun `supplier lookup does not hold the legacy trainee advisory lock`() {
        noPolicyProgram = 2
        checkTraineeLock = true
        mvc
            .perform(
                post("/internal/training-program-applications")
                    .header("X-Internal-Token", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"trainee":{"id":501,"expoId":"$EXPO"},"programs":[{"id":2,"expoId":"$EXPO","category":"CHOICE"}]}"""),
            ).andExpect(status().isCreated)
        assertEquals(1, lockChecks.get())
    }

    @Test
    fun `idempotent retry preserves the original receipt after deletion`() {
        val receipt = service.execute(command())
        context.getBean(DeleteStandardProgramApplicationsService::class.java).execute(1)
        assertEquals(receipt, service.execute(command()))
        mvc
            .perform(get("/internal/program-applications/standard/1/participants/1").header("X-Internal-Token", "test-token"))
            .andExpect(jsonPath("$.applied").value(false))
    }

    @Test
    fun `waiting is bounded replayable excludes applied and blocks sibling sessions`() {
        waitingCapacity = 1
        val confirmed = service.execute(command())
        val waiting = service.execute(command(participant = 2))
        assertEquals("WAITING", waiting.status)
        assertEquals(waiting, service.execute(command(participant = 2)))
        assertEquals(2L, count("tb_program_session_event"))
        assertThrows(
            ProgramApplicationConflictException::class.java,
        ) { service.execute(command(program = 2, participant = 2, key = "sibling")) }
        addParticipant(3)
        assertThrows(ProgramApplicationConflictException::class.java) { service.execute(command(participant = 3)) }
        applied(2, false)
        cancel(confirmed.applicationId)
        applied(2, true)
        assertEquals(waiting, service.execute(command(participant = 2)))
        cancel(confirmed.applicationId)
        assertEquals(
            1L,
            jdbc.queryForObject(
                "SELECT count(*) FROM tb_program_session_history WHERE application_id = ? AND status = 'CONFIRMED'",
                Long::class.java,
                waiting.applicationId,
            ),
        )
        assertEquals(
            1L,
            jdbc.queryForObject(
                "SELECT count(*) FROM tb_program_session_event WHERE application_id = ? AND status = 'CONFIRMED'",
                Long::class.java,
                waiting.applicationId,
            ),
        )
    }

    @Test
    fun `FIFO uses server reception time then stable UUID for equal timestamps`() {
        waitingCapacity = 3
        addParticipant(3)
        addParticipant(4)
        val confirmed = service.execute(command())
        val entries = (2L..4L).map { service.execute(command(participant = it)) }
        assertEquals(
            entries.map { it.applicationId },
            jdbc.query(
                "SELECT id FROM tb_program_session_application WHERE status = 'WAITING' ORDER BY created_at, id",
                { rs, _ -> rs.getObject(1, UUID::class.java) },
            ),
        )
        jdbc.update("UPDATE tb_program_session_application SET created_at = '2026-10-11T10:00:00Z' WHERE status = 'WAITING'")
        val first =
            jdbc.queryForObject(
                "SELECT id FROM tb_program_session_application WHERE status = 'WAITING' ORDER BY created_at, id LIMIT 1",
                UUID::class.java,
            )
        cancel(confirmed.applicationId)
        assertEquals(
            "CONFIRMED",
            jdbc.queryForObject("SELECT status FROM tb_program_session_application WHERE id = ?", String::class.java, first),
        )
        assertEquals(
            2L,
            jdbc.queryForObject("SELECT count(*) FROM tb_program_session_application WHERE status = 'WAITING'", Long::class.java),
        )
        assertEquals(3, entries.size)
    }

    @Test
    fun `application and promotion deadline immediately before at and after cancel boundary`() {
        val boundary = Instant.parse("2026-10-12T10:00:00Z")
        for (deadline in listOf("application", "promotion")) {
            for (offset in listOf(-1L, 0L, 1L)) {
                reset()
                waitingCapacity = 1
                if (deadline == "application") applicationEnd = boundary else promotionEnd = boundary
                val confirmed = service.execute(command())
                val waiting = service.execute(command(participant = 2))
                testClock.current = boundary.plusNanos(offset)
                cancel(confirmed.applicationId)
                applied(waiting.participantId, offset < 0)
                reconcile()
                applied(waiting.participantId, offset < 0)
            }
        }
        reset()
        applicationEnd = boundary
        testClock.current = boundary
        assertThrows(ProgramApplicationConflictException::class.java) { service.execute(command()) }
    }

    @Test
    fun `closed cancellation suspends FIFO until approved reopen and capacity changes reconcile`() {
        waitingCapacity = 2
        val confirmed = service.execute(command())
        service.execute(command(participant = 2))
        change { it.put("closed", true) }
        cancel(confirmed.applicationId)
        applied(2, false)
        reconcile()
        applied(2, false)
        change { it.put("closed", false) }
        reconcile()
        applied(2, true)
        addParticipant(3)
        service.execute(command(participant = 3))
        change { it.put("capacity", 2) }
        reconcile()
        applied(3, true)
    }

    @Test
    fun `actual Expo change HTTP validates counts permanent receipt and pending admission fence`() {
        waitingCapacity = 1
        service.execute(command())
        service.execute(command(participant = 2))
        val previous = mapper.readTree(definition("1", "STANDARD"))
        for ((field, value) in listOf("waitingCapacity" to 0, "capacity" to 0)) {
            val next = previous.deepCopy().asObject()
            next.put("revision", 2)
            next.path("policy").asObject().put(field, value)
            val body = """{"mutations":[{"previous":$previous,"definition":$next}]}"""
            mvc
                .perform(
                    put(
                        "/internal/expos/$EXPO/program-changes/${UUID.randomUUID()}",
                    ).header("X-Internal-Token", "test-token").contentType(MediaType.APPLICATION_JSON).content(body),
                ).andExpect(if (field == "capacity") status().isBadRequest else status().isConflict)
        }
        val next = previous.deepCopy().asObject()
        next.put("revision", 2)
        next.path("policy").asObject().put("capacity", 2)
        val body = """{"mutations":[{"previous":$previous,"definition":$next}]}"""
        val path = "/internal/expos/$EXPO/program-changes/${UUID.randomUUID()}"
        mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized)
        repeat(2) {
            mvc
                .perform(
                    put(path).header("X-Internal-Token", "test-token").contentType(MediaType.APPLICATION_JSON).content(body),
                ).andExpect(status().isNoContent)
        }
        addParticipant(3)
        assertThrows(ProgramApplicationConflictException::class.java) { service.execute(command(participant = 3)) }
        applied(2, false)
        capacity = 2
        revision = 2
        reconcile()
        applied(2, true)
        assertEquals(1L, count("tb_program_change_receipt"))
        next.path("policy").asObject().put("capacity", 3)
        mvc
            .perform(
                put(
                    path,
                ).header(
                    "X-Internal-Token",
                    "test-token",
                ).contentType(MediaType.APPLICATION_JSON)
                    .content("""{"mutations":[{"previous":$previous,"definition":$next}]}"""),
            ).andExpect(status().isConflict)
    }

    @Test
    fun `training waiting creates User link only upon promotion and increments version`() {
        waitingCapacity = 1
        val confirmed = service.execute(command(type = ProgramSessionType.TRAINING))
        val waiting = service.execute(command(participant = 2, type = ProgramSessionType.TRAINING))
        assertEquals("WAITING", waiting.status)
        assertEquals(null, waiting.traineeId)
        assertEquals(1, linkCalls.get())
        mvc
            .perform(
                get("/internal/program-applications/training/1/trainees/502").header("X-Internal-Token", "test-token"),
            ).andExpect(jsonPath("$.applied").value(false))
        cancel(confirmed.applicationId)
        assertEquals(2, linkCalls.get())
        mvc
            .perform(
                get("/internal/program-applications/training/1/trainees/502").header("X-Internal-Token", "test-token"),
            ).andExpect(jsonPath("$.applied").value(true))
        assertEquals(
            1L,
            jdbc.queryForObject("SELECT version FROM tb_training_application_version WHERE trainee_id = 502", Long::class.java),
        )
    }

    @Test
    fun `training promotion and retry use saved training ID after live form question changes`() {
        waitingCapacity = 2
        addParticipant(3)
        val confirmed = service.execute(command(type = ProgramSessionType.TRAINING))
        val savedQuestions = mapper.readTree(QUESTIONS).asArray()
        savedQuestions
            .addObject()
            .put("id", 907)
            .put("formType", "SENTENCE")
            .put("dynamicFormType", "TRAINING_ID")
        formMode = "training-id"
        formQuestions = savedQuestions.toString()
        val headCommand =
            command(participant = 2, type = ProgramSessionType.TRAINING)
                .copy(answers = mapper.readTree("""{"101":"a","907":"INSTITUTE-48"}"""))
        val head = service.execute(headCommand)
        assertEquals("WAITING", head.status)
        assertEquals(null, head.traineeId)
        assertEquals(1, linkCalls.get())
        savedQuestions.get(1).asObject().put("id", 908)
        formQuestions = savedQuestions.toString()
        val tail = service.execute(command(participant = 3, type = ProgramSessionType.TRAINING))
        assertEquals("WAITING", tail.status)
        assertEquals(1, linkCalls.get())
        linkFailure = true
        cancel(confirmed.applicationId)
        val repository = context.getBean(ProgramWaitlistRepository::class.java)
        assertEquals("CANCELLED", repository.entry(confirmed.applicationId)?.status)
        assertEquals("WAITING", repository.entry(head.applicationId)?.status)
        assertEquals("WAITING", repository.entry(tail.applicationId)?.status)
        assertEquals(0L, count("tb_training_program_application"))
        assertEquals(4L, count("tb_program_session_event"))
        linkFailure = false
        repeat(2) { reconcile(ProgramSessionType.TRAINING) }
        val headRequests = linkRequests.map { mapper.readTree(it) }.filter { it.path("participantId").asLong() == 2L }
        assertEquals(2, headRequests.size)
        headRequests.forEach {
            assertEquals(EXPO, it.path("expoId").stringValue())
            assertEquals("INSTITUTE-48", it.path("trainingId").stringValue())
        }
        val promoted = requireNotNull(repository.entry(head.applicationId))
        assertEquals("CONFIRMED", promoted.status)
        assertEquals(502L, promoted.traineeId)
        assertEquals(
            907L,
            promoted.questions
                .get(1)
                .path("id")
                .asLong(),
        )
        assertEquals(headCommand.answers, promoted.answers)
        assertEquals(head, service.execute(headCommand))
        assertEquals(3, linkCalls.get())
        mvc
            .perform(get("/internal/program-applications/training/1/trainees/502").header("X-Internal-Token", "test-token"))
            .andExpect(jsonPath("$.applied").value(true))
        assertEquals(
            1L,
            jdbc.queryForObject("SELECT version FROM tb_training_application_version WHERE trainee_id = 502", Long::class.java),
        )
        assertEquals(1L, count("tb_training_program_application"))
        assertEquals(5L, count("tb_program_session_history"))
        assertEquals(5L, count("tb_program_session_event"))
        cancel(head.applicationId)
        assertEquals("CONFIRMED", repository.entry(tail.applicationId)?.status)
        assertEquals(503L, repository.entry(tail.applicationId)?.traineeId)
        val tailRequest = mapper.readTree(linkRequests.last())
        assertEquals(3L, tailRequest.path("participantId").asLong())
        assertTrue(tailRequest.has("trainingId") && tailRequest.path("trainingId").isNull)
        assertEquals(4, linkCalls.get())
        assertEquals(1L, count("tb_training_program_application"))
        assertEquals(7L, count("tb_program_session_history"))
        assertEquals(7L, count("tb_program_session_event"))
    }

    @Test
    fun `training promotion keeps null compatibility for legacy missing null and blank saved IDs`() {
        for (variant in listOf("legacy", "missing", "null", "blank")) {
            reset()
            waitingCapacity = 1
            val confirmed = service.execute(command(type = ProgramSessionType.TRAINING))
            if (variant != "legacy") {
                val questions = mapper.readTree(QUESTIONS).asArray()
                questions
                    .addObject()
                    .put("id", 907)
                    .put("formType", "SENTENCE")
                    .put("dynamicFormType", "TRAINING_ID")
                formMode = "training-id"
                formQuestions = questions.toString()
            }
            val answers = mapper.readTree("""{"101":"a"}""").asObject()
            if (variant == "null") answers.putNull("907")
            if (variant == "blank") answers.put("907", " ")
            val waiting = service.execute(command(participant = 2, type = ProgramSessionType.TRAINING).copy(answers = answers))
            assertEquals("WAITING", waiting.status)
            assertEquals(1, linkCalls.get())
            cancel(confirmed.applicationId)
            val request = mapper.readTree(linkRequests.last())
            assertEquals(2L, request.path("participantId").asLong())
            assertTrue(request.has("trainingId") && request.path("trainingId").isNull)
            val promoted = requireNotNull(context.getBean(ProgramWaitlistRepository::class.java).entry(waiting.applicationId))
            assertEquals("CONFIRMED", promoted.status)
            assertEquals(502L, promoted.traineeId)
            repeat(2) { reconcile(ProgramSessionType.TRAINING) }
            assertEquals(2, linkCalls.get())
            mvc
                .perform(get("/internal/program-applications/training/1/trainees/502").header("X-Internal-Token", "test-token"))
                .andExpect(jsonPath("$.applied").value(true))
            assertEquals(1L, count("tb_training_program_application"))
            assertEquals(4L, count("tb_program_session_history"))
            assertEquals(4L, count("tb_program_session_event"))
        }
    }

    @Test
    fun `concurrent double cancellation promotes one waiter and concurrent admissions respect queue bound`() {
        waitingCapacity = 1
        val confirmed = service.execute(command())
        service.execute(command(participant = 2))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { pool.submit { cancel(confirmed.applicationId) } }
            results.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1L, count("tb_standard_program_application"))
        assertEquals(4L, count("tb_program_session_history"))
        reset()
        waitingCapacity = 1
        addParticipant(3)
        val results = concurrent(command(), command(participant = 2), command(participant = 3))
        assertEquals(2, results.count { it == "ok" })
        assertEquals(1, results.count { it == "conflict" })
    }

    @Test
    fun `failed training promotion preserves cancellation and retries without duplicate history or event`() {
        waitingCapacity = 1
        val confirmed = service.execute(command(type = ProgramSessionType.TRAINING))
        val waiting = service.execute(command(participant = 2, type = ProgramSessionType.TRAINING))
        linkFailure = true
        mvc
            .perform(
                post(
                    "/internal/program-session-applications/${confirmed.applicationId}/cancellation",
                ).header("X-Internal-Token", "test-token"),
            ).andExpect(status().isNoContent)
        assertEquals(
            "CANCELLED",
            jdbc.queryForObject(
                "SELECT status FROM tb_program_session_application WHERE id = ?",
                String::class.java,
                confirmed.applicationId,
            ),
        )
        assertEquals(3L, count("tb_program_session_history"))
        assertEquals(3L, count("tb_program_session_event"))
        assertEquals(
            "WAITING",
            jdbc.queryForObject(
                "SELECT status FROM tb_program_session_application WHERE id = ?",
                String::class.java,
                waiting.applicationId,
            ),
        )
        linkFailure = false
        cancel(confirmed.applicationId)
        cancel(confirmed.applicationId)
        reconcile(ProgramSessionType.TRAINING)
        reconcile(ProgramSessionType.TRAINING)
        assertEquals(
            "CONFIRMED",
            jdbc.queryForObject(
                "SELECT status FROM tb_program_session_application WHERE id = ?",
                String::class.java,
                waiting.applicationId,
            ),
        )
        assertEquals(4L, count("tb_program_session_event"))
    }

    @Test
    fun `legacy training replacement cancellation automatically promotes under the same lock`() {
        waitingCapacity = 1
        service.execute(command(type = ProgramSessionType.TRAINING))
        service.execute(command(participant = 2, type = ProgramSessionType.TRAINING))
        mvc
            .perform(
                put(
                    "/internal/training-program-applications/trainee/501",
                ).header(
                    "X-Internal-Token",
                    "test-token",
                ).contentType(MediaType.APPLICATION_JSON)
                    .content("""{"trainee":{"id":501,"expoId":"$EXPO"},"programs":[]}"""),
            ).andExpect(status().isNoContent)
        mvc
            .perform(
                get("/internal/program-applications/training/1/trainees/502").header("X-Internal-Token", "test-token"),
            ).andExpect(jsonPath("$.applied").value(true))
        assertEquals(4L, count("tb_program_session_event"))
    }

    @Test
    fun `cancel ended boundary rejects mutation but cancelled replay remains accepted`() {
        val boundary = Instant.parse("2026-10-12T10:00:00Z")
        for (offset in listOf(-1L, 0L, 1L)) {
            reset()
            cancelEnd = boundary
            val confirmed = service.execute(command())
            testClock.current = boundary.plusNanos(offset)
            mvc
                .perform(
                    post(
                        "/internal/program-session-applications/${confirmed.applicationId}/cancellation",
                    ).header("X-Internal-Token", "test-token"),
                ).andExpect(
                    if (offset <
                        0
                    ) {
                        status().isNoContent
                    } else {
                        status().isConflict
                    },
                )
            if (offset < 0) {
                testClock.current = boundary.plusSeconds(1)
                cancel(confirmed.applicationId)
            }
        }
    }

    @Test
    fun `predecessor snapshots without new deadline fields remain cancellable and receipts immutable`() {
        val confirmed = service.execute(command())
        jdbc.update(
            "UPDATE tb_program_session_application SET definition_snapshot = jsonb_set(definition_snapshot, '{policy}', " +
                "(definition_snapshot->'policy') - 'cancelEndedAt' - 'promotionEndedAt')",
        )
        cancel(confirmed.applicationId)
        applied(1, false)
        assertEquals(confirmed, service.execute(command()))
        assertEquals(2L, count("tb_program_session_history"))
    }

    @Test
    fun `capacity reduction racing last confirmation cannot approve an overbooked definition`() {
        capacity = 2
        service.execute(command())
        val previous = mapper.readTree(definition("1", "STANDARD"))
        val next = previous.deepCopy().asObject()
        next.put("revision", 2)
        next.path("policy").asObject().put("capacity", 1)
        val body = """{"mutations":[{"previous":$previous,"definition":$next}]}"""
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val admission =
                pool.submit<String> {
                    assertTrue(start.await(10, TimeUnit.SECONDS))
                    try {
                        service.execute(command(participant = 2))
                        "confirmed"
                    } catch (_: ProgramApplicationConflictException) {
                        "conflict"
                    }
                }
            val approval =
                pool.submit<Int> {
                    assertTrue(start.await(10, TimeUnit.SECONDS))
                    mvc
                        .perform(
                            put("/internal/expos/$EXPO/program-changes/${UUID.randomUUID()}")
                                .header("X-Internal-Token", "test-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body),
                        ).andReturn()
                        .response.status
                }
            start.countDown()
            val accepted = admission.get(30, TimeUnit.SECONDS) == "confirmed"
            assertEquals(if (accepted) 409 else 204, approval.get(30, TimeUnit.SECONDS))
            assertEquals(if (accepted) 2L else 1L, count("tb_standard_program_application"))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `permanent head conflict holds FIFO without blocking cancellation replacement or new waiting`() {
        for (operation in listOf("cancel", "replace")) {
            reset()
            waitingCapacity = 3
            addParticipant(3)
            val confirmed = service.execute(command(type = ProgramSessionType.TRAINING))
            val head = service.execute(command(participant = 2, type = ProgramSessionType.TRAINING))
            jdbc.update("INSERT INTO tb_training_program_application (trainee_id, training_program_id) VALUES (502, 2)")
            if (operation == "cancel") {
                cancel(confirmed.applicationId)
            } else {
                mvc
                    .perform(
                        put("/internal/training-program-applications/trainee/501")
                            .header("X-Internal-Token", "test-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"trainee":{"id":501,"expoId":"$EXPO"},"programs":[]}"""),
                    ).andExpect(status().isNoContent)
            }
            val next = service.execute(command(participant = 3, type = ProgramSessionType.TRAINING))
            assertEquals("WAITING", next.status)
            assertEquals(
                "WAITING",
                jdbc.queryForObject(
                    "SELECT status FROM tb_program_session_application WHERE id = ?",
                    String::class.java,
                    head.applicationId,
                ),
            )
            assertEquals(
                0L,
                jdbc.queryForObject("SELECT count(*) FROM tb_training_program_application WHERE training_program_id = 1", Long::class.java),
            )
            cancel(head.applicationId)
            assertEquals(
                "CONFIRMED",
                jdbc.queryForObject(
                    "SELECT status FROM tb_program_session_application WHERE id = ?",
                    String::class.java,
                    next.applicationId,
                ),
            )
        }
    }

    @Test
    fun `policyless legacy standard training and replace accept updated Expo metadata`() {
        for (operation in listOf("standard", "training", "replace")) {
            reset()
            noPolicyProgram = 0
            val standard = operation == "standard"
            val route = if (standard) "standard" else "training"
            val person = if (standard) "participant" else "trainee"
            val category = if (standard) "" else ",\"category\":\"CHOICE\""
            val firstId = if (standard) 1 else 501

            fun body(id: Int) = """{"$person":{"id":$id,"expoId":"$EXPO"},"programs":[{"id":1,"expoId":"$EXPO"$category}]}"""
            mvc
                .perform(
                    post(
                        "/internal/$route-program-applications",
                    ).header("X-Internal-Token", "test-token").contentType(MediaType.APPLICATION_JSON).content(body(firstId)),
                ).andExpect(status().isCreated)
            revision = 2
            title = "Renamed"
            val request =
                if (operation ==
                    "replace"
                ) {
                    put("/internal/training-program-applications/trainee/501")
                } else {
                    post("/internal/$route-program-applications")
                }
            val id =
                if (operation == "replace") {
                    501
                } else if (standard) {
                    2
                } else {
                    502
                }
            mvc
                .perform(request.header("X-Internal-Token", "test-token").contentType(MediaType.APPLICATION_JSON).content(body(id)))
                .andExpect(if (operation == "replace") status().isNoContent else status().isCreated)
        }
    }

    @Test
    fun `scheduler retries failed FIFO head and retains undelivered events after promotion and cancellation`() {
        waitingCapacity = 2
        addParticipant(3)
        val confirmed = service.execute(command(type = ProgramSessionType.TRAINING))
        val head = service.execute(command(participant = 2, type = ProgramSessionType.TRAINING))
        val tail = service.execute(command(participant = 3, type = ProgramSessionType.TRAINING))
        linkFailure = true
        cancel(confirmed.applicationId)
        val scheduler = context.getBean(ProgramWaitlistScheduler::class.java)
        repeat(2) { scheduler.reconcile() }
        assertEquals(0L, count("tb_training_program_application"))
        assertEquals(4L, count("tb_program_session_event"))
        linkFailure = false
        repeat(2) { scheduler.reconcile() }
        assertEquals("CONFIRMED", context.getBean(ProgramWaitlistRepository::class.java).entry(head.applicationId)?.status)
        assertEquals("WAITING", context.getBean(ProgramWaitlistRepository::class.java).entry(tail.applicationId)?.status)
        assertEquals(1L, count("tb_training_program_application"))
        assertEquals(5L, count("tb_program_session_event"))
        cancel(head.applicationId)
        repeat(2) { scheduler.reconcile() }
        assertEquals(7L, count("tb_program_session_event"))
        assertEquals(
            0L,
            jdbc.queryForObject("SELECT count(*) FROM tb_program_session_event WHERE delivered_at IS NOT NULL", Long::class.java),
        )
    }

    @Test
    fun `persisted policy marshalling preserves scheduler cutoff and pending events`() {
        waitingCapacity = 1
        promotionEnd = Instant.parse("2026-10-12T10:00:00.123456Z")
        service.execute(command())
        service.execute(command(participant = 2))
        val stored = requireNotNull(jdbc.queryForObject("SELECT definition::text FROM tb_program_session_state", String::class.java))
        val decoded =
            mapper.readValue(
                stored,
                team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition::class.java,
            )
        assertEquals(promotionEnd, decoded.policy?.promotionEndedAt)
        assertEquals(mapper.readTree(stored), mapper.readTree(mapper.writeValueAsString(decoded)))
        val repository = context.getBean(ProgramWaitlistRepository::class.java)
        assertEquals(1, repository.keys(promotionEnd.minusNanos(1000)).size)
        assertTrue(repository.keys(promotionEnd).isEmpty())
        assertEquals(2L, count("tb_program_session_event"))
    }

    @Test
    fun `expired waiting stops polling but pending policy extension still reconciles`() {
        waitingCapacity = 1
        promotionEnd = Instant.parse("2026-10-12T10:00:00Z")
        service.execute(command(type = ProgramSessionType.TRAINING))
        service.execute(command(participant = 2, type = ProgramSessionType.TRAINING))
        testClock.current = promotionEnd
        val before = batchCalls.get()
        context.getBean(ProgramWaitlistScheduler::class.java).reconcile()
        assertEquals(before, batchCalls.get())
        assertEquals(
            1L,
            jdbc.queryForObject("SELECT count(*) FROM tb_program_session_application WHERE status = 'WAITING'", Long::class.java),
        )
        change(ProgramSessionType.TRAINING) {
            it.put("promotionEndedAt", promotionEnd.plusSeconds(3600).toString())
            it.put("capacity", 2)
        }
        context.getBean(ProgramWaitlistScheduler::class.java).reconcile()
        assertEquals(2L, count("tb_training_program_application"))
    }

    @Test
    fun `new waiting ends at promotion cutoff while vacant confirmed seats remain available`() {
        val boundary = Instant.parse("2026-10-12T10:00:00Z")
        for (offset in listOf(-1L, 0L, 1L)) {
            reset()
            waitingCapacity = 1
            promotionEnd = boundary
            service.execute(command())
            testClock.current = boundary.plusNanos(offset)
            if (offset < 0) {
                assertEquals("WAITING", service.execute(command(participant = 2)).status)
            } else {
                assertThrows(ProgramApplicationConflictException::class.java) { service.execute(command(participant = 2)) }
                assertEquals(1L, count("tb_program_session_application"))
            }
        }
        reset()
        waitingCapacity = 2
        promotionEnd = boundary
        testClock.current = boundary
        assertEquals("CONFIRMED", service.execute(command()).status)
        reset()
        waitingCapacity = 2
        promotionEnd = boundary
        val confirmed = service.execute(command())
        service.execute(command(participant = 2))
        addParticipant(3)
        testClock.current = boundary
        cancel(confirmed.applicationId)
        assertThrows(ProgramApplicationConflictException::class.java) { service.execute(command(participant = 3)) }
        applied(2, false)
    }

    @Test
    fun `waiting trainee lock contention does not undo another trainee cancellation`() {
        waitingCapacity = 1
        val confirmed = service.execute(command(type = ProgramSessionType.TRAINING))
        val waiting = service.execute(command(participant = 2, type = ProgramSessionType.TRAINING))
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val holder =
                pool.submit {
                    transactions.execute {
                        context.getBean(TrainingProgramApplicationRepository::class.java).lockTrainee(502)
                        locked.countDown()
                        assertTrue(release.await(30, TimeUnit.SECONDS))
                    }
                }
            assertTrue(locked.await(10, TimeUnit.SECONDS))
            cancel(confirmed.applicationId)
            assertEquals(
                "WAITING",
                jdbc.queryForObject(
                    "SELECT status FROM tb_program_session_application WHERE id = ?",
                    String::class.java,
                    waiting.applicationId,
                ),
            )
            assertEquals(0L, count("tb_training_program_application"))
            release.countDown()
            holder.get(10, TimeUnit.SECONDS)
            reconcile(ProgramSessionType.TRAINING)
            assertEquals(1L, count("tb_training_program_application"))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `Expo lookup failure for head preserves cancellation and retries after recovery`() {
        waitingCapacity = 1
        val confirmed = service.execute(command(type = ProgramSessionType.TRAINING))
        val waiting = service.execute(command(participant = 2, type = ProgramSessionType.TRAINING))
        jdbc.update("INSERT INTO tb_training_program_application (trainee_id, training_program_id) VALUES (502, 2)")
        unavailableLookup = 2
        cancel(confirmed.applicationId)
        assertEquals(
            "WAITING",
            jdbc.queryForObject(
                "SELECT status FROM tb_program_session_application WHERE id = ?",
                String::class.java,
                waiting.applicationId,
            ),
        )
        unavailableLookup = null
        jdbc.update("DELETE FROM tb_training_program_application WHERE trainee_id = 502 AND training_program_id = 2")
        reconcile(ProgramSessionType.TRAINING)
        assertEquals(
            "CONFIRMED",
            jdbc.queryForObject(
                "SELECT status FROM tb_program_session_application WHERE id = ?",
                String::class.java,
                waiting.applicationId,
            ),
        )
        assertEquals(4L, count("tb_program_session_event"))
    }

    @Test
    fun `promotion database failure still rolls back cancellation relation history and event`() {
        waitingCapacity = 1
        val confirmed = service.execute(command())
        val waiting = service.execute(command(participant = 2))
        jdbc.execute(
            "ALTER TABLE tb_program_session_history ADD CONSTRAINT test_promotion_failure CHECK (application_id <> '${waiting.applicationId}' OR status <> 'CONFIRMED')",
        )
        try {
            assertThrows(jakarta.servlet.ServletException::class.java) {
                mvc.perform(
                    post(
                        "/internal/program-session-applications/${confirmed.applicationId}/cancellation",
                    ).header("X-Internal-Token", "test-token"),
                )
            }
            assertEquals(
                "CONFIRMED",
                jdbc.queryForObject(
                    "SELECT status FROM tb_program_session_application WHERE id = ?",
                    String::class.java,
                    confirmed.applicationId,
                ),
            )
            assertEquals(
                "WAITING",
                jdbc.queryForObject(
                    "SELECT status FROM tb_program_session_application WHERE id = ?",
                    String::class.java,
                    waiting.applicationId,
                ),
            )
            applied(1, true)
            applied(2, false)
            assertEquals(2L, count("tb_program_session_history"))
            assertEquals(2L, count("tb_program_session_event"))
        } finally {
            jdbc.execute("ALTER TABLE tb_program_session_history DROP CONSTRAINT test_promotion_failure")
        }
        cancel(confirmed.applicationId)
        applied(2, true)
    }

    @Test
    fun `large capacity increase processes bounded FIFO batches and converges without duplicate links`() {
        waitingCapacity = 20
        service.execute(command(type = ProgramSessionType.TRAINING))
        val waiting =
            (2L..15L).map { id ->
                if (id > 2) addParticipant(id)
                service.execute(command(participant = id, type = ProgramSessionType.TRAINING))
            }
        change(ProgramSessionType.TRAINING) { it.put("capacity", 15) }
        reconcile(ProgramSessionType.TRAINING)
        val firstBatch = requireNotNull(count("tb_training_program_application"))
        assertTrue(firstBatch in 2L..14L)
        val confirmedParticipants =
            jdbc.query(
                "SELECT participant_id FROM tb_program_session_application WHERE status = 'CONFIRMED' ORDER BY created_at, id",
                { rs, _ -> rs.getLong(1) },
            )
        assertEquals((1L..firstBatch).toList(), confirmedParticipants)
        repeat(14) { reconcile(ProgramSessionType.TRAINING) }
        assertEquals(15L, count("tb_training_program_application"))
        assertEquals(15, linkCalls.get())
        assertEquals(29L, count("tb_program_session_event"))
        assertEquals(waiting.first(), service.execute(command(participant = 2, type = ProgramSessionType.TRAINING)))
    }

    @Test
    fun `policyless metadata refresh cannot bypass pending policy activation`() {
        noPolicyProgram = 0
        mvc
            .perform(
                post("/internal/standard-program-applications")
                    .header("X-Internal-Token", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"participant":{"id":1,"expoId":"$EXPO"},"programs":[{"id":1,"expoId":"$EXPO"}]}"""),
            ).andExpect(status().isCreated)
        val previous = mapper.readTree(definition("1", "STANDARD"))
        noPolicyProgram = null
        waitingCapacity = 1
        val next = mapper.readTree(definition("1", "STANDARD")).asObject()
        next.put("revision", 2)
        mvc
            .perform(
                put(
                    "/internal/expos/$EXPO/program-changes/${UUID.randomUUID()}",
                ).header("X-Internal-Token", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"mutations":[{"previous":$previous,"definition":$next}]}"""),
            ).andExpect(status().isNoContent)
        noPolicyProgram = 0
        mvc
            .perform(
                post("/internal/standard-program-applications")
                    .header("X-Internal-Token", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"participant":{"id":2,"expoId":"$EXPO"},"programs":[{"id":1,"expoId":"$EXPO"}]}"""),
            ).andExpect(status().isConflict)
        noPolicyProgram = null
        revision = 2
        assertEquals("WAITING", service.execute(command(participant = 2)).status)
        assertEquals(1L, count("tb_standard_program_application"))
    }

    @Test
    fun `User response crossing either promotion deadline retains FIFO and cancellation for retry`() {
        val boundary = Instant.parse("2026-10-12T10:00:00Z")
        for (deadline in listOf("application", "promotion")) {
            for (offset in listOf(-1L, 0L, 1L)) {
                reset()
                waitingCapacity = 2
                addParticipant(3)
                if (deadline == "application") applicationEnd = boundary else promotionEnd = boundary
                val confirmed = service.execute(command(type = ProgramSessionType.TRAINING))
                val head = service.execute(command(participant = 2, type = ProgramSessionType.TRAINING))
                val tail = service.execute(command(participant = 3, type = ProgramSessionType.TRAINING))
                linkCompletedAt = boundary.plusNanos(offset)
                cancel(confirmed.applicationId)
                val repository = context.getBean(ProgramWaitlistRepository::class.java)
                assertEquals("CANCELLED", repository.entry(confirmed.applicationId)?.status)
                assertEquals(if (offset < 0) "CONFIRMED" else "WAITING", repository.entry(head.applicationId)?.status)
                assertEquals("WAITING", repository.entry(tail.applicationId)?.status)
                assertEquals(if (offset < 0) 1L else 0L, count("tb_training_program_application"))
                assertEquals(if (offset < 0) 5L else 4L, count("tb_program_session_history"))
                assertEquals(if (offset < 0) 5L else 4L, count("tb_program_session_event"))
                repeat(2) { reconcile(ProgramSessionType.TRAINING) }
                assertEquals("WAITING", repository.entry(tail.applicationId)?.status)
                linkCompletedAt = null
                change(ProgramSessionType.TRAINING) {
                    it.put("applicationEndedAt", boundary.plusSeconds(3600).toString())
                    it.put("promotionEndedAt", boundary.plusSeconds(3600).toString())
                }
                applicationEnd = boundary.plusSeconds(3600)
                repeat(2) { reconcile(ProgramSessionType.TRAINING) }
                assertEquals("CONFIRMED", repository.entry(head.applicationId)?.status)
                assertEquals("WAITING", repository.entry(tail.applicationId)?.status)
                assertEquals(1L, count("tb_training_program_application"))
                assertEquals(5L, count("tb_program_session_event"))
                assertEquals(head, service.execute(command(participant = 2, type = ProgramSessionType.TRAINING)))
            }
        }
    }

    @Test
    fun `new HTTP admission rechecks deadline after deferred FIFO User lookup`() {
        val boundary = Instant.parse("2026-10-12T10:00:00Z")
        for (deadline in listOf("application", "promotion")) {
            reset()
            waitingCapacity = 3
            addParticipant(3)
            if (deadline == "application") applicationEnd = boundary else promotionEnd = boundary
            service.execute(command(type = ProgramSessionType.TRAINING))
            val head = service.execute(command(participant = 2, type = ProgramSessionType.TRAINING))
            change(ProgramSessionType.TRAINING) { it.put("capacity", 2) }
            linkCompletedAt = boundary
            mvc
                .perform(
                    post("/application/expos/$EXPO/program-sessions/TRAINING/1/applications")
                        .with(csrf())
                        .cookie(Cookie("PARTICIPANT_SESSION", "session"))
                        .header("Idempotency-Key", "late-admission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"participantId":3,"formVersion":1,"answers":{"101":"a"}}"""),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("APPLICATION_CONFLICT"))
            assertEquals("WAITING", context.getBean(ProgramWaitlistRepository::class.java).entry(head.applicationId)?.status)
            assertEquals(2L, count("tb_program_session_application"))
            assertEquals(2L, count("tb_program_session_receipt"))
            assertEquals(2L, count("tb_program_session_history"))
            assertEquals(2L, count("tb_program_session_event"))
        }
    }

    @Test
    fun `late applicant rejection commits already due FIFO promotions across application and form deadlines`() {
        val boundary = Instant.parse("2026-10-12T10:00:00Z")
        for (deadline in listOf("application", "form")) {
            reset()
            waitingCapacity = 2
            addParticipant(3)
            addParticipant(4)
            if (deadline == "application") applicationEnd = boundary else formEnd = boundary
            service.execute(command(type = ProgramSessionType.TRAINING))
            val head = service.execute(command(participant = 2, type = ProgramSessionType.TRAINING))
            val tail = service.execute(command(participant = 3, type = ProgramSessionType.TRAINING))
            change(ProgramSessionType.TRAINING) { it.put("capacity", 4) }
            delayedLinkParticipant = 4
            linkCompletedAt = boundary
            mvc
                .perform(
                    post("/application/expos/$EXPO/program-sessions/TRAINING/1/applications")
                        .with(csrf())
                        .cookie(Cookie("PARTICIPANT_SESSION", "session"))
                        .header("Idempotency-Key", "late-applicant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"participantId":4,"formVersion":1,"answers":{"101":"a"}}"""),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("APPLICATION_CONFLICT"))
            val repository = context.getBean(ProgramWaitlistRepository::class.java)
            assertEquals("CONFIRMED", repository.entry(head.applicationId)?.status)
            assertEquals("CONFIRMED", repository.entry(tail.applicationId)?.status)
            assertEquals(502L, repository.entry(head.applicationId)?.traineeId)
            assertEquals(503L, repository.entry(tail.applicationId)?.traineeId)
            assertEquals(listOf(1L, 2L, 3L, 4L), linkRequests.map { mapper.readTree(it).path("participantId").asLong() })
            repeat(2) { reconcile(ProgramSessionType.TRAINING) }
            assertEquals(4, linkCalls.get())
            assertEquals(3L, count("tb_training_program_application"))
            assertEquals(3L, count("tb_program_session_application"))
            assertEquals(3L, count("tb_program_session_receipt"))
            assertEquals(5L, count("tb_program_session_history"))
            assertEquals(5L, count("tb_program_session_event"))
            assertEquals(
                0L,
                jdbc.queryForObject("SELECT count(*) FROM tb_training_application_version WHERE trainee_id = 504", Long::class.java),
            )
            assertEquals(head, service.execute(command(participant = 2, type = ProgramSessionType.TRAINING)))
            mvc
                .perform(get("/internal/program-applications/training/1/trainees/502").header("X-Internal-Token", "test-token"))
                .andExpect(jsonPath("$.applied").value(true))
        }
    }

    @Test
    fun `legacy applicant conflict rolls back earlier FIFO promotions`() {
        waitingCapacity = 2
        addParticipant(3)
        addParticipant(4)
        service.execute(command(type = ProgramSessionType.TRAINING))
        val head = service.execute(command(participant = 2, type = ProgramSessionType.TRAINING))
        val tail = service.execute(command(participant = 3, type = ProgramSessionType.TRAINING))
        change(ProgramSessionType.TRAINING) { it.put("capacity", 4) }
        jdbc.update("INSERT INTO tb_training_program_application (trainee_id, training_program_id) VALUES (504, 2)")

        assertThrows(ProgramApplicationConflictException::class.java) {
            service.execute(command(participant = 4, type = ProgramSessionType.TRAINING))
        }

        val repository = context.getBean(ProgramWaitlistRepository::class.java)
        assertEquals("WAITING", repository.entry(head.applicationId)?.status)
        assertEquals("WAITING", repository.entry(tail.applicationId)?.status)
        assertEquals(2L, count("tb_training_program_application"))
        assertEquals(3L, count("tb_program_session_application"))
        assertEquals(3L, count("tb_program_session_receipt"))
        assertEquals(3L, count("tb_program_session_history"))
        assertEquals(3L, count("tb_program_session_event"))
    }

    @Test
    fun `concurrent cancellation promotion and policy approval converge with FIFO and one transition each`() {
        for (type in ProgramSessionType.entries) {
            reset()
            waitingCapacity = 2
            addParticipant(3)
            val confirmed = service.execute(command(type = type))
            val head = service.execute(command(participant = 2, type = type))
            val tail = service.execute(command(participant = 3, type = type))
            val previous = mapper.readTree(definition("1", type.name))
            val next = previous.deepCopy().asObject()
            next.put("revision", 2)
            next.path("policy").asObject().put("capacity", 2)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(3)
            try {
                val cancellations =
                    (1..2).map {
                        pool.submit<Int> {
                            assertTrue(start.await(10, TimeUnit.SECONDS))
                            mvc
                                .perform(
                                    post("/internal/program-session-applications/${confirmed.applicationId}/cancellation")
                                        .header("X-Internal-Token", "test-token"),
                                ).andReturn()
                                .response.status
                        }
                    }
                val approval =
                    pool.submit<Int> {
                        assertTrue(start.await(10, TimeUnit.SECONDS))
                        mvc
                            .perform(
                                put("/internal/expos/$EXPO/program-changes/${UUID.randomUUID()}")
                                    .header("X-Internal-Token", "test-token")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""{"mutations":[{"previous":$previous,"definition":$next}]}"""),
                            ).andReturn()
                            .response.status
                    }
                start.countDown()
                assertEquals(204, approval.get(30, TimeUnit.SECONDS))
                cancellations.forEach { assertTrue(it.get(30, TimeUnit.SECONDS) in listOf(204, 409)) }
            } finally {
                pool.shutdownNow()
            }
            val repository = context.getBean(ProgramWaitlistRepository::class.java)
            if (repository.entry(tail.applicationId)?.status == "CONFIRMED") {
                assertEquals("CONFIRMED", repository.entry(head.applicationId)?.status)
            }
            capacity = 2
            revision = 2
            repeat(2) { cancel(confirmed.applicationId) }
            repeat(2) { reconcile(type) }
            assertEquals("CANCELLED", repository.entry(confirmed.applicationId)?.status)
            assertEquals("CONFIRMED", repository.entry(head.applicationId)?.status)
            assertEquals("CONFIRMED", repository.entry(tail.applicationId)?.status)
            val table = if (type == ProgramSessionType.STANDARD) "tb_standard_program_application" else "tb_training_program_application"
            assertEquals(2L, count(table))
            assertEquals(6L, count("tb_program_session_history"))
            assertEquals(6L, count("tb_program_session_event"))
            assertThrows(ProgramApplicationConflictException::class.java) {
                service.execute(command(program = 2, participant = 2, key = "promoted-sibling", type = type))
            }
        }
    }

    @Test
    fun `channels keep separate seats and waiting and promote FIFO without borrowing another channel's vacancy`() {
        useChannels()
        (3L..5L).forEach(::addParticipant)
        occupations = mapOf(1L to "TEACHER", 2L to "TEACHER", 3L to "GENERAL", 4L to "GENERAL", 5L to "TEACHER")
        val teacher = service.execute(command())
        val teacherWaiting = service.execute(command(participant = 2))
        val general = service.execute(command(participant = 3))
        assertThrows(ProgramApplicationConflictException::class.java) { service.execute(command(participant = 5)) }
        val generalWaiting = service.execute(command(participant = 4))
        val receipts = listOf(teacher, teacherWaiting, general, generalWaiting)
        assertEquals(listOf("CONFIRMED", "WAITING", "CONFIRMED", "WAITING"), receipts.map { it.status })
        assertEquals(listOf("교원", "교원", "일반", "일반"), receipts.map { column("channel", it.applicationId) })
        assertEquals(teacherWaiting, service.execute(command(participant = 2)))
        cancel(general.applicationId)
        assertEquals("CONFIRMED", column("status", generalWaiting.applicationId))
        assertEquals("WAITING", column("status", teacherWaiting.applicationId))
        cancel(teacher.applicationId)
        assertEquals("CONFIRMED", column("status", teacherWaiting.applicationId))
    }

    @Test
    fun `channel policy changes never reclassify existing applications and shrink each channel only to its own rows`() {
        useChannels()
        service.execute(command())
        service.execute(command(participant = 2))
        val teacherWaiting = { p: tools.jackson.databind.node.ObjectNode -> p.path("channels")[0].asObject() }
        assertEquals(409, propose { it.remove("channels") })
        assertEquals(409, propose { teacherWaiting(it).put("name", "교사") })
        assertEquals(
            409,
            propose {
                teacherWaiting(it).put("waitingCapacity", 0)
                it.path("channels")[1].asObject().put("waitingCapacity", 2)
            },
        )
        assertEquals(400, propose { teacherWaiting(it).put("capacity", 2) })
        assertEquals(400, propose { teacherWaiting(it).put("allowedOccupations", "TEACHER") })
        change {
            teacherWaiting(it).put("capacity", 2)
            it.put("capacity", 3)
        }
        reconcile()
        assertEquals(
            0L,
            jdbc.queryForObject("SELECT count(*) FROM tb_program_session_application WHERE status = 'WAITING'", Long::class.java),
        )
    }

    @Test
    fun `channels are introduced only once the session has no applications`() {
        val first = service.execute(command())
        val introduce = { p: tools.jackson.databind.node.ObjectNode ->
            p.set("allowedOccupations", mapper.readTree("""["TEACHER","GENERAL"]"""))
            p.put("capacity", 2)
            p.put("waitingCapacity", 2)
            p.set("channels", mapper.readTree(CHANNELS))
            Unit
        }
        assertEquals(409, propose(introduce))
        cancel(first.applicationId)
        change(update = introduce)
        occupations = mapOf(2L to "GENERAL")
        assertEquals("일반", column("channel", service.execute(command(participant = 2)).applicationId))
    }

    @Test
    fun `channel sessions stop promotion after the deadline and reject a channel policy that breaks Expo's contract`() {
        useChannels()
        addParticipant(3)
        occupations = mapOf(1L to "TEACHER", 2L to "TEACHER", 3L to "TEACHER")
        val boundary = Instant.parse("2026-10-12T10:00:00Z")
        promotionEnd = boundary
        val confirmed = service.execute(command())
        val waiting = service.execute(command(participant = 2))
        testClock.current = boundary
        cancel(confirmed.applicationId)
        reconcile()
        assertEquals("WAITING", column("status", waiting.applicationId))
        channels = """[{"name":"교원","allowedOccupations":["TEACHER"],"capacity":2,"waitingCapacity":2}]"""
        assertEquals(
            502,
            assertThrows(ResponseStatusException::class.java) { service.execute(command(participant = 3)) }.statusCode.value(),
        )
    }

    private fun useChannels() {
        allowed = """["TEACHER","GENERAL"]"""
        capacity = 2
        waitingCapacity = 2
        channels = CHANNELS
    }

    private fun column(
        name: String,
        id: UUID,
    ): String? = jdbc.queryForObject("SELECT $name FROM tb_program_session_application WHERE id = ?", String::class.java, id)

    private fun propose(update: (tools.jackson.databind.node.ObjectNode) -> Unit): Int {
        val previous = mapper.readTree(definition("1", "STANDARD"))
        val next = previous.deepCopy().asObject()
        next.put("revision", revision + 1)
        update(next.path("policy").asObject())
        return mvc
            .perform(
                put("/internal/expos/$EXPO/program-changes/${UUID.randomUUID()}")
                    .header("X-Internal-Token", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"mutations":[{"previous":$previous,"definition":$next}]}"""),
            ).andReturn()
            .response.status
    }

    private fun addParticipant(id: Long) {
        jdbc.update(
            "INSERT INTO tb_preregister_application (expo_id, session_id, representative_id, participant_id, status) VALUES (?, 1, 1, ?, 'CONFIRMED')",
            UUID.fromString(EXPO),
            id,
        )
    }

    private fun applied(
        participant: Long,
        value: Boolean,
    ) {
        mvc
            .perform(
                get("/internal/program-applications/standard/1/participants/$participant").header("X-Internal-Token", "test-token"),
            ).andExpect(jsonPath("$.applied").value(value))
    }

    private fun cancel(id: UUID) {
        mvc
            .perform(
                post("/internal/program-session-applications/$id/cancellation").header("X-Internal-Token", "test-token"),
            ).andExpect(status().isNoContent)
    }

    private fun reconcile(type: ProgramSessionType = ProgramSessionType.STANDARD) {
        mvc
            .perform(
                post("/internal/expos/$EXPO/program-sessions/$type/1/reconciliation").header("X-Internal-Token", "test-token"),
            ).andExpect(status().isNoContent)
    }

    private fun change(
        type: ProgramSessionType = ProgramSessionType.STANDARD,
        update: (tools.jackson.databind.node.ObjectNode) -> Unit,
    ) {
        val previous = mapper.readTree(definition("1", type.name))
        val next = previous.deepCopy().asObject()
        next.put("revision", revision + 1)
        update(next.path("policy").asObject())
        mvc
            .perform(
                put(
                    "/internal/expos/$EXPO/program-changes/${UUID.randomUUID()}",
                ).header(
                    "X-Internal-Token",
                    "test-token",
                ).contentType(MediaType.APPLICATION_JSON)
                    .content("""{"mutations":[{"previous":$previous,"definition":$next}]}"""),
            ).andExpect(status().isNoContent)
        revision++
        closed = next.path("policy").path("closed").asBoolean()
        capacity = next.path("policy").path("capacity").asInt()
        waitingCapacity = next.path("policy").path("waitingCapacity").asInt()
        promotionEnd = Instant.parse(next.path("policy").path("promotionEndedAt").stringValue())
        allowed = next.path("policy").path("allowedOccupations").toString()
        channels = next.path("policy").get("channels")?.toString()
    }

    private fun command(
        program: Long = 1,
        participant: Long = 1,
        key: String = "key",
        type: ProgramSessionType = ProgramSessionType.STANDARD,
    ) = ApplyProgramSessionCommand(EXPO, type, program, participant, 1, mapper.readTree("""{"101":"a"}"""), "session", key)

    private fun concurrent(vararg commands: ApplyProgramSessionCommand): List<String> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(commands.size)
        try {
            val results =
                commands.map { command ->
                    pool.submit<String> {
                        assertTrue(start.await(10, TimeUnit.SECONDS))
                        try {
                            service.execute(command)
                            "ok"
                        } catch (_: ProgramApplicationConflictException) {
                            "conflict"
                        }
                    }
                }
            start.countDown()
            return results.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    @TestConfiguration
    class Forms {
        @Bean
        @Primary
        fun testClock(): Clock = testClock

        @Bean
        fun programForms(
            mapper: ObjectMapper,
            gateway: RegistrationGateway,
        ): ProgramFormProvider =
            object : ProgramFormProvider {
                override fun loadAndValidate(
                    expoId: String,
                    formId: UUID,
                    version: Long,
                    answers: JsonNode,
                ): ProgramFormSnapshot {
                    if (formMode == "real") return ProgramFormAdapter(gateway).loadAndValidate(expoId, formId, version, answers)
                    if ((answers.size() != 1 && (formMode != "training-id" || answers.size() != 2)) ||
                        answers.get("101")?.stringValue() !in setOf("a", "b")
                    ) {
                        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid fake form answer")
                    }
                    return ProgramFormSnapshot(
                        if (formMode == "expo") "other-expo" else expoId,
                        formId,
                        if (formMode == "purpose") "REGISTRATION" else "PROGRAM",
                        if (formMode == "version") version + 1 else version,
                        Instant.parse("2020-01-01T00:00:00Z"),
                        if (formMode == "period") Instant.parse("2021-01-01T00:00:00Z") else formEnd,
                        mapper.readTree(formQuestions),
                    )
                }
            }
    }

    companion object {
        private const val EXPO = "aaaaaaaa-0000-0000-0000-000000000043"
        private const val FORM = "00000000-0000-0000-0000-000000000001"
        private const val GROUP = "00000000-0000-0000-0000-000000000002"
        private const val CHANNELS =
            """[{"name":"교원","allowedOccupations":["TEACHER"],"capacity":1,"waitingCapacity":1},""" +
                """{"name":"일반","allowedOccupations":["GENERAL"],"capacity":1,"waitingCapacity":1}]"""
        private const val REAL_FIELDS =
            """[{"id":101,"title":"Question","formType":"DROPDOWN","requiredStatus":true,""" +
                """"jsonData":{"a":"First","b":"Second"},"otherJson":null,"dynamicFormType":"DEFAULT"}]"""
        private const val QUESTIONS =
            """[{"id":101,"title":"Question","options":[""" +
                """{"id":"a","label":"First"},{"id":"b","label":"Second"}]}]"""
        private val linkCalls = AtomicInteger()
        private val linkRequests = ConcurrentLinkedQueue<String>()
        private val batchCalls = AtomicInteger()
        private val lockChecks = AtomicInteger()
        private val testClock =
            object : Clock() {
                @Volatile var current: Instant = Instant.parse("2026-10-11T10:00:00Z")

                override fun instant(): Instant = current

                override fun getZone(): ZoneId = ZoneOffset.UTC

                override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
            }

        @Volatile private var capacity = 1

        @Volatile private var waitingCapacity = 0

        @Volatile private var revision = 1L

        @Volatile private var applicationEnd = Instant.parse("2100-01-01T00:00:00Z")

        @Volatile private var promotionEnd = applicationEnd

        @Volatile private var cancelEnd = applicationEnd

        @Volatile private var formEnd = applicationEnd

        @Volatile private var linkFailure = false

        @Volatile private var linkCompletedAt: Instant? = null

        @Volatile private var delayedLinkParticipant: Long? = null

        @Volatile private var title = "Session"

        @Volatile private var unavailableLookup: Long? = null

        @Volatile private var checkTraineeLock = false

        @Volatile private var sessionResponse: String? = null

        @Volatile private var occupationResponse: String? = null

        @Volatile private var missingProgram: Long? = null

        @Volatile private var noPolicyProgram: Long? = null

        @Volatile private var definitionConflict = false

        @Volatile private var occupation = "TEACHER"

        @Volatile private var occupations = mapOf<Long, String>()

        @Volatile private var allowed = "[\"TEACHER\"]"

        @Volatile private var channels: String? = null

        @Volatile private var closed = false

        @Volatile private var expired = false

        @Volatile private var badExpo = false

        @Volatile private var pending = false

        @Volatile private var formMode = "valid"

        @Volatile private var formQuestions = QUESTIONS

        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    val body = exchange.requestBody.bufferedReader().use { it.readText() }
                    val path = exchange.requestURI.path
                    var status = 200
                    val response =
                        when {
                            exchange.requestHeaders.getFirst("X-Internal-Token") != "test-token" -> {
                                status = 401
                                "{}"
                            }

                            path == "/internal/forms/by-id/$FORM" -> {
                                """{"id":"$FORM","expoId":"$EXPO","purpose":"PROGRAM","version":1,"participantType":null,""" +
                                    """"startDate":"2020-01-01T00:00:00Z","endDate":"2100-01-01T00:00:00Z","dynamicForm":$REAL_FIELDS}"""
                            }

                            path.endsWith("participant-sessions/resolve") -> {
                                if (body.contains("expired")) {
                                    status = 401
                                    """{"code":"SESSION_EXPIRED"}"""
                                } else {
                                    sessionResponse
                                        ?: """{"expoId":"$EXPO","phoneNumber":"01012345678","expiresAt":"2100-01-01T00:00:00"}"""
                                }
                            }

                            path.endsWith("managed/select") -> {
                                if (body.contains("[99]")) {
                                    status = 404
                                    "{}"
                                } else {
                                    val id =
                                        ObjectMapper()
                                            .readTree(body)
                                            .path("participantIds")
                                            .get(0)
                                            .asLong()
                                    """[{"participantId":$id,"name":"Person","occupation":${occupationResponse ?: "\"${occupations[id] ?: occupation}\""},"representative":true}]"""
                                }
                            }

                            path.endsWith("resolve-or-create-by-participant") -> {
                                linkCalls.incrementAndGet()
                                linkRequests.add(body)
                                if (linkFailure) status = 503
                                val participantId = ObjectMapper().readTree(body).path("participantId").asLong()
                                if (delayedLinkParticipant == null || delayedLinkParticipant == participantId) {
                                    linkCompletedAt?.let { testClock.current = it }
                                }
                                val id = participantId + 500
                                """{"traineeId":$id,"created":true}"""
                            }

                            path.contains("standard-programs/") -> {
                                val id = path.substringAfterLast('/').toLong()
                                when {
                                    definitionConflict -> {
                                        status = 409
                                        "{}"
                                    }

                                    missingProgram == id -> {
                                        status = 404
                                        "{}"
                                    }

                                    else -> {
                                        definition(id.toString(), "STANDARD")
                                    }
                                }
                            }

                            path.endsWith("training-programs/batch") -> {
                                batchCalls.incrementAndGet()
                                if (checkTraineeLock) {
                                    DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                                        connection.autoCommit = false
                                        connection.createStatement().use { statement ->
                                            statement.executeQuery("SELECT pg_try_advisory_xact_lock(-501::bigint)").use { result ->
                                                if (!result.next() || !result.getBoolean(1)) {
                                                    status = 503
                                                } else {
                                                    lockChecks.incrementAndGet()
                                                }
                                            }
                                        }
                                        connection.rollback()
                                    }
                                }
                                val array = ObjectMapper().readTree(body).path("programIds")
                                val ids = (0 until array.size()).map { array.get(it).asLong() }
                                if (unavailableLookup != null && unavailableLookup in ids) status = 503
                                when {
                                    status == 503 -> {
                                        "{}"
                                    }

                                    definitionConflict -> {
                                        status = 409
                                        "{}"
                                    }

                                    ids.any { it == missingProgram } -> {
                                        status = 404
                                        "{}"
                                    }

                                    else -> {
                                        ids.joinToString(prefix = "[", postfix = "]") { definition(it.toString(), "TRAINING") }
                                    }
                                }
                            }

                            else -> {
                                status = 404
                                "{}"
                            }
                        }
                    val bytes = response.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                start()
            }

        private fun definition(
            id: String,
            type: String,
        ): String {
            val response =
                """{"expoId":"${if (badExpo) "other" else EXPO}","programType":"$type","id":$id,"title":"$title","groupId":"$GROUP","revision":$revision,"pendingRevision":${if (pending) 2 else "null"},"policy":{"formId":"$FORM","allowedOccupations":$allowed,"capacity":$capacity,"waitingCapacity":$waitingCapacity,"applicationStartedAt":"2020-01-01T00:00:00Z","applicationEndedAt":"${if (expired) "2021-01-01T00:00:00Z" else applicationEnd}","cancelEndedAt":"$cancelEnd","promotionEndedAt":"$promotionEnd","closed":$closed${channels?.let {
                    ",\"channels\":$it"
                } ?: ""}}}"""
            if (noPolicyProgram == id.toLong() || noPolicyProgram == 0L) {
                val json = ObjectMapper().readTree(response).asObject()
                json.putNull("policy")
                return json.toString()
            }
            return response
        }

        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")

        @DynamicPropertySource @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("application.program-sessions.enabled") { true }
            registry.add("application.program-sessions.reconcile-delay") { 3600000 }
            registry.add("application.registration.expo-internal-token") { "test-token" }
            registry.add("application.registration.user-internal-token") { "test-token" }
            registry.add("application.registration.form-internal-token") { "test-token" }
            registry.add("application.registration.form-url") { "http://127.0.0.1:${server.address.port}" }
            registry.add("application.registration.expo-url") { "http://127.0.0.1:${server.address.port}" }
            registry.add("application.registration.user-url") { "http://127.0.0.1:${server.address.port}" }
        }
    }
}
