package team.startup.application

import com.sun.net.httpserver.HttpServer
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.postgresql.PGConnection
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.ApplyProgramSessionCommand
import team.startup.application.domain.application.presentation.dto.ApplyStudentGuardianBatchCommand
import team.startup.application.domain.application.presentation.dto.ApplyTrainingProgramsCommand
import team.startup.application.domain.application.presentation.dto.CancelOwnerApplicationCommand
import team.startup.application.domain.application.presentation.dto.CancelProgramSessionCommand
import team.startup.application.domain.application.presentation.dto.CancelStudentGuardianApplicationCommand
import team.startup.application.domain.application.presentation.dto.OwnerApplicationParty
import team.startup.application.domain.application.presentation.dto.OwnerApplicationType
import team.startup.application.domain.application.presentation.dto.ProgramFormSnapshot
import team.startup.application.domain.application.presentation.dto.ProgramGuardianPolicy
import team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition
import team.startup.application.domain.application.presentation.dto.ProgramSessionKey
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.presentation.dto.PromoteAdminApplicationCommand
import team.startup.application.domain.application.presentation.dto.StudentGuardianBatchRequest
import team.startup.application.domain.application.presentation.dto.StudentGuardianEligibilityQuery
import team.startup.application.domain.application.presentation.dto.StudentGuardianLink
import team.startup.application.domain.application.presentation.dto.StudentGuardianSubmission
import team.startup.application.domain.application.presentation.dto.TraineeReference
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.ProgramWaitlistRepository
import team.startup.application.domain.application.repository.PromotionOfferRepository
import team.startup.application.domain.application.repository.StandardProgramApplicationRepository
import team.startup.application.domain.application.repository.TrainingOperationRepository
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import team.startup.application.domain.application.service.ApplyOnsiteProgramSessionService
import team.startup.application.domain.application.service.ApplyOnsiteStudentGuardianBatchService
import team.startup.application.domain.application.service.ApplyProgramSessionService
import team.startup.application.domain.application.service.ApplyStudentGuardianBatchService
import team.startup.application.domain.application.service.CancelOwnerApplicationService
import team.startup.application.domain.application.service.CancelProgramSessionService
import team.startup.application.domain.application.service.CancelStudentGuardianApplicationService
import team.startup.application.domain.application.service.DeleteStandardProgramApplicationsService
import team.startup.application.domain.application.service.OwnerCancellationGuard
import team.startup.application.domain.application.service.ProgramFormProvider
import team.startup.application.domain.application.service.PromoteAdminApplicationService
import team.startup.application.domain.application.service.ReplaceTrainingProgramsService
import team.startup.application.domain.application.service.StudentGuardianEligibilityProvider
import team.startup.application.domain.application.service.impl.ProgramFormAdapter
import team.startup.application.domain.application.service.impl.ProgramSessionAdmission
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

    @Autowired private lateinit var onsiteService: ApplyOnsiteProgramSessionService

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var context: ApplicationContext

    @Autowired private lateinit var transactions: TransactionTemplate

    @Autowired private lateinit var batchService: ApplyStudentGuardianBatchService

    @Autowired private lateinit var onsiteBatchService: ApplyOnsiteStudentGuardianBatchService

    @Autowired private lateinit var batchCancellation: CancelStudentGuardianApplicationService

    @BeforeEach
    fun reset() {
        jdbc.execute(
            "TRUNCATE tb_promotion_offer, tb_program_guardian_link, tb_program_guardian_member, tb_program_guardian_batch, " +
                "tb_legacy_trainee_program, tb_legacy_trainee_attribution, tb_legacy_trainee_attribution_operation, " +
                "tb_program_session_event, tb_program_session_state, tb_program_change_receipt, " +
                "tb_program_session_history, tb_program_session_receipt, tb_program_session_application, " +
                "tb_standard_program_application, tb_training_program_application, tb_training_application_version, " +
                "tb_training_operation_receipt, tb_deleted_standard_program, tb_deleted_training_program, " +
                "tb_preregister_application, tb_preregister_session_state RESTART IDENTITY",
        )
        jdbc.update(
            "INSERT INTO tb_preregister_session_state (expo_id, session_id, definition) VALUES (?, 1, '{\"startedAt\":\"2000-01-01T00:00:00Z\",\"endedAt\":\"2100-01-01T00:00:00Z\"}')",
            UUID.fromString(EXPO),
        )
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
        managedIds = listOf(1, 2)
        fieldIds = emptySet()
        guardianPolicy = false
        protectedParticipants = emptySet()
        allowed = "[\"TEACHER\"]"
        channels = null
        admissionCapacities = null
        promotionPolicy = null
        promoteAccessStatus = 200
        closed = false
        expired = false
        badExpo = false
        pending = false
        formMode = "valid"
        formQuestions = QUESTIONS
        realFields = REAL_FIELDS
        groups = emptyMap()
        windows = emptyMap()
        sessionResponse = null
        occupationResponse = null
        missingProgram = null
        noPolicyProgram = null
        definitionConflict = false
        batchCalls.set(0)
        checkTraineeLock = false
        lockChecks.set(0)
        formCallsInTransaction.set(0)
        capacity = 1
        waitingCapacity = 0
        revision = 1
        applicationEnd = Instant.parse("2100-01-01T00:00:00Z")
        promotionEnd = applicationEnd
        cancelEnd = applicationEnd
        formEnd = applicationEnd
        testClock.current = Instant.parse("2026-10-11T10:00:00Z")
        linkFailure = false
        linkRejections = emptyMap()
        linkCompletedAt = null
        delayedLinkParticipant = null
        title = "Session"
        unavailableLookup = null
        places = emptyMap()
        attributionCalls.clear()
        attributionStatus = 200
        attributionMalformed = false
        attributionParticipant = null
        attributionAfterUser = null
        attributionAccessStatus = 200
        attributionActor = 42
    }

    @Test
    fun `admin change signal follows committed apply cancel and promotion but not rejected applications`() {
        // #162: 관리자 SSE는 V28 트리거의 커밋 후 NOTIFY만 따른다. 실제 신청·취소·승급 경로로 확인한다.
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute("LISTEN application_admin_changes") }
            val notifications = connection.unwrap(PGConnection::class.java)

            fun signals(): Int {
                Thread.sleep(200)
                return notifications.getNotifications(200).orEmpty().count { it.parameter == EXPO }
            }
            signals()
            waitingCapacity = 1
            val confirmed = service.execute(command())
            assertEquals(1, signals())
            assertEquals("WAITING", service.execute(command(participant = 2)).status)
            assertEquals(1, signals())
            assertEquals(confirmed, service.execute(command()))
            assertEquals(0, signals())
            // 사전등록 확정 행 추가도 관리자 집계 변경이다.
            addParticipant(3)
            assertEquals(1, signals())
            assertThrows(ProgramApplicationConflictException::class.java) { service.execute(command(participant = 3)) }
            assertEquals(0, signals())
            // 취소와 대기 승급은 한 트랜잭션이라 신호 하나로 합쳐진다.
            cancel(confirmed.applicationId)
            assertEquals(1, signals())
            cancel(confirmed.applicationId)
            assertEquals(0, signals())
        }
    }

    @Test
    fun `sessions observed before the Expo V37 split keep accepting applications and reading participants`() {
        // #163: Expo V37은 기존 정책을 승인 요청 없이 사전 정원 = 전체 정원, 현장 0으로 바꿨다.
        capacity = 2
        assertEquals("CONFIRMED", service.execute(command()).status)
        val stored = "SELECT definition->'policy'->>'preRegistrationCapacity' FROM tb_program_session_state"
        assertEquals(null, jdbc.queryForObject(stored, String::class.java))
        admissionCapacities = "\"preRegistrationCapacity\":2,\"onsiteCapacity\":0"
        assertEquals("CONFIRMED", service.execute(command(participant = 2)).status)
        admissionCapacities = "\"preRegistrationCapacity\":1,\"onsiteCapacity\":1"
        assertThrows(
            ProgramApplicationConflictException::class.java,
        ) { service.execute(command(program = 1, participant = 2, key = "split")) }
    }

    @Test
    fun `standard and training venues survive snapshots and approved revision changes without rewriting history`() {
        for (type in ProgramSessionType.entries) {
            reset()
            capacity = 2
            places = mapOf("1" to "Room A", "2" to "Room B")
            val first = service.execute(command(type = type))
            val sibling = service.execute(command(program = 2, participant = 2, type = type))

            fun snapshot(id: UUID) =
                jdbc.queryForObject(
                    "SELECT definition_snapshot->>'place' FROM tb_program_session_application WHERE id = ?",
                    String::class.java,
                    id,
                )
            assertEquals("Room A", snapshot(first.applicationId))
            assertEquals("Room B", snapshot(sibling.applicationId))
            addParticipant(3)
            places = places + ("1" to "Room C")
            assertThrows(ProgramApplicationConflictException::class.java) { service.execute(command(participant = 3, type = type)) }
            places = places + ("1" to "Room A")
            val previous = mapper.readTree(definition("1", type.name))
            val next = previous.deepCopy().asObject()
            next.put("revision", 2)
            next.put("place", "Room C")
            mvc
                .perform(
                    put("/internal/expos/$EXPO/program-changes/${UUID.randomUUID()}")
                        .header("X-Internal-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"mutations":[{"previous":$previous,"definition":$next}]}"""),
                ).andExpect(status().isNoContent)
            places = places + ("1" to "Room C")
            revision = 2
            val latest = service.execute(command(participant = 3, type = type))
            assertEquals("Room C", snapshot(latest.applicationId))
            assertEquals("Room A", snapshot(first.applicationId))
            assertEquals(first, service.execute(command(type = type)))
        }
    }

    @Test
    fun `legacy definitions omit place and supplier venue length uses Unicode code points`() {
        service.execute(command())
        assertEquals(
            false,
            jdbc.queryForObject(
                "SELECT jsonb_exists(definition_snapshot, 'place') FROM tb_program_session_application",
                Boolean::class.java,
            ),
        )
        for (type in ProgramSessionType.entries) {
            reset()
            places = mapOf("1" to "😀".repeat(255))
            service.execute(command(type = type))
            assertEquals(
                "😀".repeat(255),
                jdbc.queryForObject("SELECT definition_snapshot->>'place' FROM tb_program_session_application", String::class.java),
            )
            for (invalid in listOf("", "   ", " Room ", "😀".repeat(256))) {
                places = mapOf("1" to invalid)
                assertEquals(
                    HttpStatus.BAD_GATEWAY,
                    assertThrows(ResponseStatusException::class.java) {
                        service.execute(command(participant = 2, type = type))
                    }.statusCode,
                )
            }
        }
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
    fun `training admission accepts Expo space separated session times`() {
        mvc
            .perform(
                post("/application/expos/$EXPO/program-sessions/TRAINING/1/applications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"participantId":1,"formVersion":1,"answers":{"101":"a"}}""")
                    .header("Idempotency-Key", "training-space-time")
                    .with(csrf())
                    .cookie(Cookie("PARTICIPANT_SESSION", "session")),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
        assertEquals(1L, count("tb_program_session_application"))
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
    fun `eligible teacher companion sharing the representative phone applies to training through the real form contract`() {
        // #122: representative 1 is GENERAL; companions 9 and 10 are teachers reached through the representative's session phone.
        addParticipant(9)
        addParticipant(10)
        occupations = mapOf(1L to "GENERAL", 9L to "TEACHER", 10L to "TEACHER")
        capacity = 2
        formMode = "real"
        realFields =
            """[{"id":30,"title":"동의","formType":"CHECKBOX","requiredStatus":true,""" +
            """"jsonData":{},"otherJson":null,"dynamicFormType":"DEFAULT"}]"""

        fun apply(
            participant: Long,
            key: String,
            program: Long = 1,
        ) = mvc.perform(
            post("/application/expos/$EXPO/program-sessions/TRAINING/$program/applications")
                .with(csrf())
                .cookie(Cookie("PARTICIPANT_SESSION", "session"))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"participantId":$participant,"formVersion":1,"answers":{"30":true}}"""),
        )

        val created =
            apply(9, "teacher")
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.participantId").value(9))
                .andExpect(jsonPath("$.traineeId").value(509))
                .andReturn()
        val applicationId = mapper.readTree(created.response.contentAsString).path("applicationId").stringValue()
        assertEquals(mapper.readTree("""{"expoId":"$EXPO","participantId":9,"trainingId":null}"""), mapper.readTree(linkRequests.single()))
        apply(9, "teacher").andExpect(status().isCreated).andExpect(jsonPath("$.applicationId").value(applicationId))
        assertEquals(1, linkCalls.get())
        apply(9, "teacher-again").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("APPLICATION_CONFLICT"))
        apply(1, "general").andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("PARTICIPANT_INELIGIBLE"))
        apply(10, "companion").andExpect(status().isCreated).andExpect(jsonPath("$.traineeId").value(510))
        assertEquals(
            listOf(509L, 510L),
            jdbc.queryForList(
                "SELECT trainee_id FROM tb_training_program_application WHERE training_program_id = 1 ORDER BY trainee_id",
                Long::class.java,
            ),
        )
        assertEquals(
            applicationId,
            jdbc.queryForObject("SELECT id::text FROM tb_program_session_application WHERE trainee_id = 509", String::class.java),
        )
        mvc
            .perform(get("/internal/program-applications/training/1/trainees/509").header("X-Internal-Token", "test-token"))
            .andExpect(jsonPath("$.applied").value(true))

        groups = mapOf("2" to "00000000-0000-0000-0000-000000000003")
        apply(9, "other-training", program = 2).andExpect(status().isCreated).andExpect(jsonPath("$.traineeId").value(509))
        assertEquals(
            2L,
            jdbc.queryForObject("SELECT count(*) FROM tb_training_program_application WHERE trainee_id = 509", Long::class.java),
        )

        addParticipant(11)
        occupations = occupations + (11L to "TEACHER")
        apply(11, "full").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("APPLICATION_CONFLICT"))
        cancel(UUID.fromString(applicationId))
        mvc
            .perform(get("/internal/program-applications/training/1/trainees/509").header("X-Internal-Token", "test-token"))
            .andExpect(jsonPath("$.applied").value(false))
        closed = true
        apply(11, "closed").andExpect(status().isConflict)
        assertEquals(3, linkCalls.get())
    }

    @Test
    fun `User trainee link conflict is distinguishable from local conflicts and writes nothing`() {
        linkRejections = mapOf(1L to 409)
        val error = assertThrows(ResponseStatusException::class.java) { service.execute(command(type = ProgramSessionType.TRAINING)) }
        assertEquals(HttpStatus.CONFLICT, error.statusCode)
        assertEquals("연수자 연결이 거부되었습니다.", error.reason)
        assertEquals(0L, count("tb_program_session_application"))
        assertEquals(0L, count("tb_training_program_application"))
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

    @ParameterizedTest
    @CsvSource("STANDARD,false", "STANDARD,true", "TRAINING,false", "TRAINING,true")
    fun `event failure rolls back admission and the same key retries without duplicate seats`(
        type: ProgramSessionType,
        waiting: Boolean,
    ) {
        waitingCapacity = 1
        if (waiting) service.execute(command(type = type))
        val before = if (waiting) 1L else 0L
        val request = command(type = type, participant = 2, key = "event-retry")
        val expectedStatus = if (waiting) "WAITING" else "CONFIRMED"
        val tables =
            listOf(
                "tb_program_session_application",
                "tb_program_session_receipt",
                "tb_program_session_history",
                "tb_program_session_event",
            )
        val relation = if (type == ProgramSessionType.STANDARD) "tb_standard_program_application" else "tb_training_program_application"
        jdbc.execute("ALTER TABLE tb_program_session_event ADD CONSTRAINT test_admission_event_failure CHECK (status <> '$expectedStatus')")
        try {
            assertThrows(DataIntegrityViolationException::class.java) { service.execute(request) }
            (tables + relation).forEach { assertEquals(before, count(it), it) }
            assertEquals(
                0L,
                jdbc.queryForObject("SELECT count(*) FROM tb_training_application_version WHERE trainee_id = 502", Long::class.java),
            )
        } finally {
            jdbc.execute("ALTER TABLE tb_program_session_event DROP CONSTRAINT test_admission_event_failure")
        }
        val receipt = service.execute(request)
        assertEquals(expectedStatus, receipt.status)
        assertEquals(receipt, service.execute(request.copy(sessionId = "renewed-session")))
        tables.forEach { assertEquals(before + 1, count(it), it) }
        assertEquals(if (waiting) before else 1L, count(relation))
        assertEquals(
            mapper.readTree("""{"101":"a"}"""),
            mapper.readTree(
                jdbc.queryForObject(
                    "SELECT answers_snapshot::text FROM tb_program_session_application WHERE id = ?",
                    String::class.java,
                    receipt.applicationId,
                ),
            ),
        )
    }

    @Test
    fun `missing production Form adapter fails closed without local or remote creation`() {
        val unavailable =
            ProgramSessionAdmission(
                context.getBean(ProgramSessionApplicationRepository::class.java),
                context.getBean(StandardProgramApplicationRepository::class.java),
                context.getBean(TrainingProgramApplicationRepository::class.java),
                context.getBean(TrainingOperationRepository::class.java),
                context.getBean(RegistrationGateway::class.java),
                DefaultListableBeanFactory().getBeanProvider(ProgramFormProvider::class.java),
                context.getBean(ProgramWaitlist::class.java),
                context.getBean(ProgramWaitlistRepository::class.java),
                context.getBeanProvider(StudentGuardianEligibilityProvider::class.java),
                transactions,
            )
        val error =
            assertThrows(ResponseStatusException::class.java) {
                transactions.execute { unavailable.admit(command(type = ProgramSessionType.TRAINING), onsite = false) }
            }
        assertEquals(503, error.statusCode.value())
        assertEquals(0L, count("tb_program_session_application"))
        assertEquals(0, linkCalls.get())
    }

    @ParameterizedTest
    @CsvSource("STANDARD", "TRAINING")
    fun `remote reads run before the admission transaction and a held program lock fails fast with retry`(type: ProgramSessionType) {
        // #212: Expo·Form 조회 중에는 DB 커넥션·락을 쥐지 않고, 프로그램 락 대기는 커넥션을 쥔 채 무한히 줄 서지 않는다.
        assertEquals("CONFIRMED", service.execute(command(type = type)).status)
        assertEquals(0, formCallsInTransaction.get())
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { holder ->
            holder.autoCommit = false
            holder.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(1)") }
            val pool = Executors.newSingleThreadExecutor()
            try {
                val started = System.nanoTime()
                val blocked =
                    pool.submit<Any> {
                        runCatching { service.execute(command(participant = 2, type = type)) }.exceptionOrNull()
                            ?: Unit
                    }
                val error = blocked.get(15, TimeUnit.SECONDS)
                assertTrue(error is ProgramApplicationConflictException && error.message.orEmpty().contains("재시도"), error.toString())
                assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 10)
            } finally {
                holder.rollback()
                pool.shutdownNow()
            }
        }
        assertEquals(1L, count("tb_program_session_application"))
        assertEquals(0, formCallsInTransaction.get())
    }

    @ParameterizedTest
    @CsvSource("STANDARD", "TRAINING")
    fun `two students and a new guardian use three seats with separate answers and one replayable batch`(type: ProgramSessionType) {
        enableGuardianPolicy(3, 0)
        val request = guardianBatch(1, 2, type = type)
        val receipt = batchService.execute(request)
        assertEquals(3, receipt.applications.size)
        assertTrue(receipt.applications.all { it.application.status == "CONFIRMED" && !it.reused })
        assertEquals(
            3L,
            count(
                if (type ==
                    ProgramSessionType.STANDARD
                ) {
                    "tb_standard_program_application"
                } else {
                    "tb_training_program_application"
                },
            ),
        )
        assertEquals(2L, count("tb_program_guardian_link"))
        assertEquals(3L, count("tb_program_session_event"))
        assertEquals(receipt, batchService.execute(request.copy(sessionId = "renewed-session")))
        assertEquals(1L, count("tb_program_guardian_batch"))
        assertEquals(3L, count("tb_program_session_receipt"))
        assertThrows(ProgramApplicationConflictException::class.java) {
            batchService.execute(request.copy(request = request.request.copy(links = request.request.links.reversed())))
        }
    }

    @Test
    fun `a three person batch waits together behind two vacant seats and promotes without a single overtaking it`() {
        enableGuardianPolicy(2, 4)
        val receipt = batchService.execute(guardianBatch(1, 2))
        assertTrue(receipt.applications.all { it.application.status == "WAITING" })
        assertEquals(0L, count("tb_standard_program_application"))
        occupations = occupations + (4L to "ELEMENTARY_STUDENT")
        guardianPolicy = false
        val single = service.execute(command(participant = 4))
        assertEquals("WAITING", single.status)
        guardianPolicy = true
        change { it.put("capacity", 3) }
        reconcile()
        receipt.applications.forEach { applied(it.application.participantId, true) }
        applied(4, false)
        assertEquals(3L, count("tb_standard_program_application"))
        reconcile()
        assertEquals(7L, count("tb_program_session_event"))
    }

    @Test
    fun `batch without three seats or waiting capacity rejects all people before local writes`() {
        enableGuardianPolicy(2, 0)
        assertThrows(ProgramApplicationConflictException::class.java) { batchService.execute(guardianBatch(1, 2)) }
        assertEquals(0L, count("tb_program_session_application"))
        assertEquals(0L, count("tb_program_guardian_batch"))
        assertEquals(0L, count("tb_program_guardian_link"))
    }

    @Test
    fun `reused confirmed guardian keeps its form and seat while an extra student waits and promotes`() {
        enableGuardianPolicy(2, 1)
        val original = batchService.execute(guardianBatch(1))
        val guardian = original.applications.single { it.application.participantId == 3L }.application
        val extra = batchService.execute(guardianBatch(2, key = "extra", reuse = true))
        assertEquals(guardian, extra.applications.single { it.reused }.application)
        assertEquals(
            "WAITING",
            extra.applications
                .single { !it.reused }
                .application.status,
        )
        assertEquals(3L, count("tb_program_session_application"))
        assertEquals(2L, count("tb_standard_program_application"))
        cancelGuardianStudent(
            original.applications
                .single { it.application.participantId == 1L }
                .application.applicationId,
        )
        applied(1, false)
        applied(2, true)
        applied(3, true)
        assertEquals(2L, count("tb_standard_program_application"))
        cancelGuardianStudent(
            extra.applications
                .single { !it.reused }
                .application.applicationId,
        )
        assertEquals(0L, count("tb_standard_program_application"))
        assertEquals(
            3L,
            jdbc.queryForObject("SELECT count(*) FROM tb_program_session_application WHERE status = 'CANCELLED'", Long::class.java),
        )
    }

    @Test
    fun `guardian only cancellation and attendance protected final cancellation cannot leave orphan students`() {
        enableGuardianPolicy(3, 0)
        val result = batchService.execute(guardianBatch(1, 2))
        val guardian =
            result.applications
                .single { it.application.participantId == 3L }
                .application.applicationId
        assertThrows(ProgramApplicationConflictException::class.java) { cancelGuardianStudent(guardian) }
        cancelGuardianStudent(
            result.applications
                .single { it.application.participantId == 1L }
                .application.applicationId,
        )
        assertEquals(2L, count("tb_standard_program_application"))
        protectedParticipants = setOf(3)
        assertThrows(ResponseStatusException::class.java) {
            cancelGuardianStudent(
                result.applications
                    .single { it.application.participantId == 2L }
                    .application.applicationId,
            )
        }
        assertEquals(2L, count("tb_standard_program_application"))
        protectedParticipants = emptySet()
        cancelGuardianStudent(
            result.applications
                .single { it.application.participantId == 2L }
                .application.applicationId,
        )
        assertEquals(0L, count("tb_standard_program_application"))
        // The guardian cascade records the same session representative (1), never the student or guardian target.
        assertEquals(
            listOf("1:USER:null:1", "2:USER:null:1", "3:USER:null:1"),
            jdbc.query(
                "SELECT a.participant_id, h.actor_type, h.actor_id, h.actor_participant_id FROM tb_program_session_history h " +
                    "JOIN tb_program_session_application a ON a.id = h.application_id " +
                    "WHERE h.status = 'CANCELLED' ORDER BY a.participant_id",
            ) { rs, _ -> "${rs.getLong(1)}:${rs.getString(2)}:${rs.getString(3)}:${rs.getString(4)}" },
        )
    }

    @Test
    fun `bad form wrong party standalone guardian and unavailable eligibility never partially accept a batch`() {
        enableGuardianPolicy(3, 0)
        val request = guardianBatch(1, 2)
        val badForm = request.request.participants.map { if (it.participantId == 2L) it.copy(answers = mapper.readTree("{}")) else it }
        assertThrows(ResponseStatusException::class.java) {
            batchService.execute(request.copy(request = request.request.copy(participants = badForm)))
        }
        managedIds = listOf(1, 3)
        assertThrows(ResponseStatusException::class.java) { batchService.execute(request) }
        managedIds = listOf(1, 2, 3)
        assertThrows(ResponseStatusException::class.java) {
            batchService.execute(
                request.copy(
                    request = StudentGuardianBatchRequest(listOf(StudentGuardianSubmission(3, 1, mapper.readTree("{}"))), emptyList()),
                ),
            )
        }
        guardianPolicy = false
        assertEquals(503, assertThrows(ResponseStatusException::class.java) { batchService.execute(request) }.statusCode.value())
        assertEquals(0L, count("tb_program_session_application"))
        assertEquals(0L, count("tb_program_guardian_link"))
    }

    @Test
    fun `batch event failure rolls back links seats and receipt then retries the same key`() {
        enableGuardianPolicy(3, 0)
        jdbc.execute("ALTER TABLE tb_program_session_event ADD CONSTRAINT test_guardian_event_failure CHECK (status <> 'CONFIRMED')")
        val request = guardianBatch(1, 2)
        try {
            assertThrows(DataIntegrityViolationException::class.java) { batchService.execute(request) }
            listOf(
                "tb_program_guardian_batch",
                "tb_program_guardian_member",
                "tb_program_guardian_link",
                "tb_program_session_application",
                "tb_program_session_receipt",
                "tb_standard_program_application",
            ).forEach {
                assertEquals(0L, count(it), it)
            }
        } finally {
            jdbc.execute("ALTER TABLE tb_program_session_event DROP CONSTRAINT test_guardian_event_failure")
        }
        assertEquals(3, batchService.execute(request).applications.size)
    }

    @Test
    fun `legacy training replacement cannot remove a guardian without linked students`() {
        enableGuardianPolicy(3, 0)
        batchService.execute(guardianBatch(1, 2, type = ProgramSessionType.TRAINING))
        assertThrows(ProgramApplicationConflictException::class.java) {
            context.getBean(ReplaceTrainingProgramsService::class.java).execute(
                ApplyTrainingProgramsCommand(TraineeReference(503, EXPO), emptyList(), UUID.randomUUID(), 1),
            )
        }
        assertEquals(3L, count("tb_training_program_application"))
        assertEquals(
            3L,
            jdbc.queryForObject("SELECT count(*) FROM tb_program_session_application WHERE status = 'CONFIRMED'", Long::class.java),
        )
    }

    @Test
    fun `failed last student cancellation rolls back guardian cancellation seats and both events`() {
        enableGuardianPolicy(2, 0)
        val receipt = batchService.execute(guardianBatch(1))
        val student =
            receipt.applications
                .single { it.application.participantId == 1L }
                .application.applicationId
        val guardian =
            receipt.applications
                .single { it.application.participantId == 3L }
                .application.applicationId
        jdbc.execute(
            "ALTER TABLE tb_program_session_history ADD CONSTRAINT test_guardian_cancel_failure CHECK (application_id <> '$guardian' OR status <> 'CANCELLED')",
        )
        try {
            assertThrows(DataIntegrityViolationException::class.java) { cancelGuardianStudent(student) }
            assertEquals(2L, count("tb_standard_program_application"))
            assertEquals(2L, count("tb_program_session_history"))
            assertEquals(2L, count("tb_program_session_event"))
            assertEquals(
                2L,
                jdbc.queryForObject("SELECT count(*) FROM tb_program_session_application WHERE status = 'CONFIRMED'", Long::class.java),
            )
        } finally {
            jdbc.execute("ALTER TABLE tb_program_session_history DROP CONSTRAINT test_guardian_cancel_failure")
        }
        cancelGuardianStudent(student)
        assertEquals(0L, count("tb_standard_program_application"))
    }

    @Test
    fun `failed batch promotion rolls back every promoted member and the cancellation that released seats`() {
        enableGuardianPolicy(3, 3)
        guardianPolicy = false
        val single = service.execute(command(participant = 4))
        guardianPolicy = true
        val receipt = batchService.execute(guardianBatch(1, 2))
        val guardian =
            receipt.applications
                .single { it.application.participantId == 3L }
                .application.applicationId
        jdbc.execute(
            "ALTER TABLE tb_program_session_history ADD CONSTRAINT test_guardian_promotion_failure CHECK (application_id <> '$guardian' OR status <> 'CONFIRMED')",
        )
        try {
            assertThrows(DataIntegrityViolationException::class.java) { cancel(single.applicationId) }
            assertEquals(1L, count("tb_standard_program_application"))
            assertEquals(
                3L,
                jdbc.queryForObject("SELECT count(*) FROM tb_program_session_application WHERE status = 'WAITING'", Long::class.java),
            )
            assertEquals(4L, count("tb_program_session_history"))
            assertEquals(4L, count("tb_program_session_event"))
        } finally {
            jdbc.execute("ALTER TABLE tb_program_session_history DROP CONSTRAINT test_guardian_promotion_failure")
        }
        cancel(single.applicationId)
        assertEquals(3L, count("tb_standard_program_application"))
    }

    @Test
    fun `concurrent additional students reuse one guardian and cannot double charge its seat`() {
        enableGuardianPolicy(3, 1)
        batchService.execute(guardianBatch(1))
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results =
                listOf(2L, 4L).map { id ->
                    pool.submit<String> {
                        assertTrue(start.await(10, TimeUnit.SECONDS))
                        batchService
                            .execute(
                                guardianBatch(id, key = "add-$id", reuse = true),
                            ).applications
                            .single { !it.reused }
                            .application.status
                    }
                }
            start.countDown()
            assertEquals(setOf("CONFIRMED", "WAITING"), results.map { it.get(30, TimeUnit.SECONDS) }.toSet())
        } finally {
            pool.shutdownNow()
        }
        assertEquals(3L, count("tb_standard_program_application"))
        assertEquals(4L, count("tb_program_session_application"))
        assertEquals(3L, count("tb_program_guardian_link"))
    }

    @Test
    fun `batch public route requires csrf and a participant session`() {
        val path = "/application/expos/$EXPO/program-sessions/STANDARD/1/application-batches"
        val body = mapper.writeValueAsString(guardianBatch(1, 2).request)
        mvc
            .perform(
                post(
                    path,
                ).cookie(
                    Cookie("PARTICIPANT_SESSION", "session"),
                ).header("Idempotency-Key", "http")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isForbidden)
        mvc
            .perform(
                post(path)
                    .with(csrf())
                    .header("Idempotency-Key", "http")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isUnauthorized)
        enableGuardianPolicy(3, 0)
        mvc
            .perform(
                post(
                    path,
                ).with(
                    csrf(),
                ).cookie(
                    Cookie("PARTICIPANT_SESSION", "session"),
                ).header("Idempotency-Key", "http")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isCreated)
    }

    @Test
    fun `waiting guardian is not reused as confirmed and promotion revalidates the whole group`() {
        enableGuardianPolicy(1, 3)
        val first = batchService.execute(guardianBatch(1))
        assertTrue(first.applications.all { it.application.status == "WAITING" })
        assertThrows(
            ProgramApplicationConflictException::class.java,
        ) { batchService.execute(guardianBatch(2, key = "extra", reuse = true)) }
        guardianPolicy = false
        change { it.put("capacity", 2) }
        reconcile()
        assertEquals(0L, count("tb_standard_program_application"))
        guardianPolicy = true
        reconcile()
        assertEquals(2L, count("tb_standard_program_application"))
    }

    @Test
    fun `single cancellation cannot bypass guardian links and owner cancellation takes the guardian route`() {
        enableGuardianPolicy(2, 0)
        val first = batchService.execute(guardianBatch(1))
        val student =
            first.applications
                .single { it.application.participantId == 1L }
                .application.applicationId
        // 소유자·관리자 취소가 공유하는 단건 취소는 연계 신청을 직접 취소하지 않는다.
        assertThrows(ProgramApplicationConflictException::class.java) {
            context.getBean(CancelProgramSessionService::class.java).execute(CancelProgramSessionCommand(student))
        }
        assertEquals(2L, count("tb_standard_program_application"))
        cancel(student)
        assertEquals(0L, count("tb_standard_program_application"))
        reset()
        assertEquals("CONFIRMED", service.execute(command()).status)
    }

    @Test
    fun `concurrent cancellation and extra student never leave an active guardian without students`() {
        enableGuardianPolicy(3, 1)
        val first = batchService.execute(guardianBatch(1))
        val student =
            first.applications
                .single { it.application.participantId == 1L }
                .application.applicationId
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val cancellation =
                pool.submit<String> {
                    assertTrue(start.await(10, TimeUnit.SECONDS))
                    try {
                        cancelGuardianStudent(student)
                        "cancelled"
                    } catch (_: ProgramApplicationConflictException) {
                        "retry"
                    }
                }
            val addition =
                pool.submit<String> {
                    assertTrue(start.await(10, TimeUnit.SECONDS))
                    try {
                        batchService.execute(guardianBatch(2, key = "race", reuse = true))
                        "added"
                    } catch (exception: ResponseStatusException) {
                        assertEquals(400, exception.statusCode.value())
                        "guardian-cancelled"
                    }
                }
            start.countDown()
            val cancelled = cancellation.get(30, TimeUnit.SECONDS)
            val added = addition.get(30, TimeUnit.SECONDS)
            assertTrue(cancelled == "cancelled" || added == "added")
        } finally {
            pool.shutdownNow()
        }
        assertEquals(
            0L,
            jdbc.queryForObject(
                "SELECT count(*) FROM tb_program_guardian_link l " +
                    "JOIN tb_program_session_application s ON s.id = l.student_application_id " +
                    "JOIN tb_program_session_application g ON g.id = l.guardian_application_id " +
                    "WHERE s.status <> 'CANCELLED' AND g.status = 'CANCELLED'",
                Long::class.java,
            ),
        )
        assertEquals(
            0L,
            jdbc.queryForObject(
                "SELECT count(*) FROM tb_program_guardian_member m JOIN tb_program_session_application g ON g.id = m.application_id " +
                    "WHERE m.role = 'GUARDIAN' AND g.status <> 'CANCELLED' AND NOT EXISTS " +
                    "(SELECT 1 FROM tb_program_guardian_link l JOIN tb_program_session_application s ON s.id = l.student_application_id " +
                    "WHERE l.guardian_application_id = g.id AND s.status <> 'CANCELLED')",
                Long::class.java,
            ),
        )
    }

    private fun enableGuardianPolicy(
        seats: Int,
        queue: Int,
    ) {
        guardianPolicy = true
        capacity = seats
        waitingCapacity = queue
        managedIds = listOf(1, 2, 3, 4, 5)
        listOf(3L, 4L, 5L).forEach(::addParticipant)
        occupations =
            mapOf(
                1L to "ELEMENTARY_STUDENT",
                2L to "ELEMENTARY_STUDENT",
                3L to "PARENT",
                4L to "ELEMENTARY_STUDENT",
                5L to "ELEMENTARY_STUDENT",
            )
        allowed = "[\"ELEMENTARY_STUDENT\",\"PARENT\"]"
    }

    @Test
    fun `merged batch admission rejects a conflicting guardian without accepting any students`() {
        enableGuardianPolicy(3, 3)
        groups = mapOf("2" to UUID.randomUUID().toString())
        val window = mapper.readTree(definition("1", "STANDARD"))
        windows = mapOf(("TRAINING" to 2L) to (window.path("startedAt").stringValue() to window.path("endedAt").stringValue()))
        guardianPolicy = false
        service.execute(command(program = 2, participant = 3, type = ProgramSessionType.TRAINING, key = "guardian-other"))
        guardianPolicy = true
        val error = assertThrows(ProgramApplicationConflictException::class.java) { batchService.execute(guardianBatch(1, 2)) }
        assertEquals("TIME_CONFLICT", error.ineligibleReason?.name)
        assertEquals(0L, count("tb_program_guardian_batch"))
        assertEquals(0L, count("tb_standard_program_application"))
        assertEquals(1L, count("tb_program_session_application"))
    }

    @Test
    fun `merged batch promotion keeps all members waiting when a non head student has a time conflict`() {
        enableGuardianPolicy(1, 3)
        groups = mapOf("2" to UUID.randomUUID().toString())
        val batch = batchService.execute(guardianBatch(1, 2))
        guardianPolicy = false
        service.execute(command(program = 2, participant = 2, type = ProgramSessionType.TRAINING, key = "student-other"))
        guardianPolicy = true
        val window = mapper.readTree(definition("1", "STANDARD"))
        windows = mapOf(("TRAINING" to 2L) to (window.path("startedAt").stringValue() to window.path("endedAt").stringValue()))
        jdbc.update(
            "UPDATE tb_program_session_state SET definition = CAST(? AS jsonb) WHERE program_type = 'TRAINING' AND program_id = 2",
            definition("2", "TRAINING"),
        )
        change { it.put("capacity", 3) }
        val events = count("tb_program_session_event")
        reconcile()
        batch.applications.forEach { assertEquals("WAITING", column("status", it.application.applicationId)) }
        assertEquals(0L, count("tb_standard_program_application"))
        assertEquals(events, count("tb_program_session_event"))
    }

    @Test
    fun `permanent User rejection cancels the waiting batch head while an outage keeps it and the next waiter follows`() {
        for (failure in listOf(409, 503)) {
            reset()
            enableGuardianPolicy(2, 4)
            guardianPolicy = false
            val first = service.execute(command(participant = 4, type = ProgramSessionType.TRAINING, key = "first"))
            val second = service.execute(command(participant = 5, type = ProgramSessionType.TRAINING, key = "second"))
            guardianPolicy = true
            val batch = batchService.execute(guardianBatch(1, type = ProgramSessionType.TRAINING))
            guardianPolicy = false
            val next = service.execute(command(participant = 2, type = ProgramSessionType.TRAINING, key = "next"))
            guardianPolicy = true
            assertEquals("WAITING", next.status)
            if (failure == 409) linkRejections = mapOf(1L to 409) else linkFailure = true
            cancel(first.applicationId)
            cancel(second.applicationId)
            reconcile(ProgramSessionType.TRAINING)
            val expected = if (failure == 409) "CANCELLED" else "WAITING"
            batch.applications.forEach { assertEquals(expected, column("status", it.application.applicationId)) }
            assertEquals(if (failure == 409) "CONFIRMED" else "WAITING", column("status", next.applicationId))
            if (failure == 409) {
                batch.applications.forEach {
                    assertEquals(
                        1L,
                        jdbc.queryForObject(
                            "SELECT count(*) FROM tb_program_session_history WHERE application_id = ? AND status = 'CANCELLED' " +
                                "AND actor_type = 'SYSTEM'",
                            Long::class.java,
                            it.application.applicationId,
                        ),
                    )
                    assertEquals(
                        1L,
                        jdbc.queryForObject(
                            "SELECT count(*) FROM tb_program_session_event WHERE application_id = ? AND status = 'CANCELLED'",
                            Long::class.java,
                            it.application.applicationId,
                        ),
                    )
                }
            } else {
                linkFailure = false
                reconcile(ProgramSessionType.TRAINING)
                batch.applications.forEach { assertEquals("CONFIRMED", column("status", it.application.applicationId)) }
                assertEquals("WAITING", column("status", next.applicationId))
            }
        }
    }

    private fun guardianBatch(
        vararg students: Long,
        key: String = "batch",
        reuse: Boolean = false,
        type: ProgramSessionType = ProgramSessionType.STANDARD,
    ): ApplyStudentGuardianBatchCommand =
        ApplyStudentGuardianBatchCommand(
            ProgramSessionKey(UUID.fromString(EXPO), type, 1),
            "session",
            key,
            StudentGuardianBatchRequest(
                students.map { StudentGuardianSubmission(it, 1, mapper.readTree("""{"101":"a"}""")) } +
                    StudentGuardianSubmission(3, if (reuse) null else 1, if (reuse) null else mapper.readTree("""{"101":"b"}""")),
                students.map { StudentGuardianLink(it, 3) },
            ),
        )

    private fun cancelGuardianStudent(id: UUID) {
        batchCancellation.execute(CancelStudentGuardianApplicationCommand(UUID.fromString(EXPO), id, sessionId = "session"))
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
    fun `program eligibility requires a confirmed preregister session overlapping the program time`() {
        // 회차 A 10-12, B 13-15 KST. 프로그램 시간은 KST 로컬, 회차 시간은 저장된 정의의 UTC Instant다(#197).
        val sessions =
            mapOf(
                1L to ("2030-01-01T01:00:00Z" to "2030-01-01T03:00:00Z"),
                2L to ("2030-01-01T04:00:00Z" to "2030-01-01T06:00:00Z"),
            )

        fun attempt(
            session: Long,
            from: String,
            to: String,
        ): String? {
            reset()
            jdbc.execute("TRUNCATE tb_preregister_application, tb_preregister_session_state RESTART IDENTITY")
            sessions.forEach { (id, window) ->
                jdbc.update(
                    "INSERT INTO tb_preregister_session_state (expo_id, session_id, definition) VALUES (?, ?, ?)",
                    UUID.fromString(EXPO),
                    id,
                    """{"startedAt":"${window.first}","endedAt":"${window.second}"}""",
                )
            }
            jdbc.update(
                "INSERT INTO tb_preregister_application (expo_id, session_id, representative_id, participant_id, status) " +
                    "VALUES (?, ?, 1, 1, 'CONFIRMED')",
                UUID.fromString(EXPO),
                session,
            )
            windows = mapOf(("STANDARD" to 1L) to ("2030-01-01T$from:00" to "2030-01-01T$to:00"))
            return try {
                service.execute(command())
                null
            } catch (ex: ResponseStatusException) {
                assertEquals(403, ex.statusCode.value())
                assertEquals(0L, count("tb_program_session_application"))
                ex.reason
            }
        }
        assertEquals(null, attempt(1, "10:30", "11:30"))
        assertEquals(null, attempt(1, "11:30", "12:30"))
        assertEquals(null, attempt(2, "12:30", "13:30"))
        assertEquals("SESSION_TIME_MISMATCH", attempt(1, "12:10", "12:50"))
        assertEquals("SESSION_TIME_MISMATCH", attempt(2, "12:10", "12:50"))
        assertEquals("SESSION_TIME_MISMATCH", attempt(1, "12:00", "13:00"))
        assertEquals("SESSION_TIME_MISMATCH", attempt(2, "10:30", "11:30"))
        jdbc.update("UPDATE tb_preregister_application SET status = 'CANCELLED'")
        assertEquals(
            "PREREGISTRATION_NOT_CONFIRMED",
            assertThrows(ResponseStatusException::class.java) { service.execute(command()) }.reason,
        )
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
        assertEquals(
            // Owner cancellation records the session representative; promotion stays SYSTEM without a user actor.
            listOf(
                "USER:null:CONFIRMED:true:null",
                "USER:null:WAITING:true:null",
                "USER:CONFIRMED:CANCELLED:true:1",
                "SYSTEM:WAITING:CONFIRMED:true:null",
            ),
            jdbc.query(
                "SELECT actor_type, previous_status, status, previous_status_recorded, actor_participant_id " +
                    "FROM tb_program_session_history ORDER BY id",
            ) { rs, _ -> "${rs.getString(1)}:${rs.getString(2)}:${rs.getString(3)}:${rs.getBoolean(4)}:${rs.getString(5)}" },
        )
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

    @ParameterizedTest
    @CsvSource("STANDARD,false", "TRAINING,false", "STANDARD,true", "TRAINING,true")
    fun `stale waiting from cancelled preregistration does not block new confirmation`(
        type: ProgramSessionType,
        channelSeats: Boolean,
    ) {
        if (channelSeats) useChannels() else waitingCapacity = 1
        val confirmed = service.execute(command(type = type))
        val stale = service.execute(command(participant = 2, type = type))
        assertEquals("WAITING", stale.status)
        jdbc.update(
            "UPDATE tb_preregister_application SET status = 'CANCELLED' WHERE expo_id = ? AND participant_id = 2",
            UUID.fromString(EXPO),
        )
        cancel(confirmed.applicationId)
        addParticipant(3)
        val current = mapper.readValue(definition("1", type.name), ProgramSessionDefinition::class.java)
        val repository = context.getBean(ProgramWaitlistRepository::class.java)
        assertTrue(repository.waiting(current, 10).isEmpty())
        assertEquals("CONFIRMED", service.execute(command(participant = 3, type = type)).status)
        assertEquals(0, repository.waitingCount(current))
        if (channelSeats) assertEquals(0, repository.waitingCount(current, "교원"))
        assertEquals("WAITING", column("status", stale.applicationId))
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
        assertEquals(2, linkCalls.get())
        mvc
            .perform(
                get("/internal/program-applications/training/1/trainees/502").header("X-Internal-Token", "test-token"),
            ).andExpect(jsonPath("$.applied").value(false))
        cancel(confirmed.applicationId)
        assertEquals(3, linkCalls.get())
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
        assertEquals(2, linkCalls.get())
        savedQuestions.get(1).asObject().put("id", 908)
        formQuestions = savedQuestions.toString()
        val tail = service.execute(command(participant = 3, type = ProgramSessionType.TRAINING))
        assertEquals("WAITING", tail.status)
        assertEquals(3, linkCalls.get())
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
        assertEquals(3, headRequests.size)
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
        assertEquals(5, linkCalls.get())
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
        assertEquals(6, linkCalls.get())
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
            assertEquals(2, linkCalls.get())
            cancel(confirmed.applicationId)
            val request = mapper.readTree(linkRequests.last())
            assertEquals(2L, request.path("participantId").asLong())
            assertTrue(request.has("trainingId") && request.path("trainingId").isNull)
            val promoted = requireNotNull(context.getBean(ProgramWaitlistRepository::class.java).entry(waiting.applicationId))
            assertEquals("CONFIRMED", promoted.status)
            assertEquals(502L, promoted.traineeId)
            repeat(2) { reconcile(ProgramSessionType.TRAINING) }
            assertEquals(3, linkCalls.get())
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
        cancel(confirmed.applicationId)
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
            assertEquals(if (offset < 0) 204 else 409, cancelStatus(confirmed.applicationId))
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
    fun `legacy group conflict rejects training waiting so the next waiter is promoted once`() {
        waitingCapacity = 2
        addParticipant(3)
        val confirmed = service.execute(command(type = ProgramSessionType.TRAINING))
        jdbc.update("INSERT INTO tb_training_program_application (trainee_id, training_program_id) VALUES (502, 2)")
        assertThrows(ProgramApplicationConflictException::class.java) {
            service.execute(command(participant = 2, type = ProgramSessionType.TRAINING))
        }
        assertEquals(
            0L,
            jdbc.queryForObject("SELECT count(*) FROM tb_program_session_application WHERE participant_id = 2", Long::class.java),
        )
        val next = service.execute(command(participant = 3, type = ProgramSessionType.TRAINING))
        assertEquals("WAITING", next.status)
        cancel(confirmed.applicationId)
        reconcile(ProgramSessionType.TRAINING)
        assertEquals("CONFIRMED", column("status", next.applicationId))
        assertEquals(
            1L,
            jdbc.queryForObject(
                "SELECT count(*) FROM tb_program_session_history WHERE application_id = ? AND status = 'CONFIRMED'",
                Long::class.java,
                next.applicationId,
            ),
        )
        assertEquals(
            1L,
            jdbc.queryForObject("SELECT count(*) FROM tb_training_program_application WHERE training_program_id = 1", Long::class.java),
        )
    }

    @Test
    fun `legacy add and replace reject a sibling of the group where the trainee participant is waiting`() {
        groups = mapOf("3" to UUID.randomUUID().toString())
        waitingCapacity = 1
        service.execute(command(program = 3, type = ProgramSessionType.TRAINING, key = "other"))
        service.execute(command(participant = 2, type = ProgramSessionType.TRAINING))
        val waiting = service.execute(command(type = ProgramSessionType.TRAINING, key = "waiting"))
        assertEquals("WAITING", waiting.status)
        noPolicyProgram = 2
        mvc
            .perform(
                post("/internal/training-program-applications")
                    .header("X-Internal-Token", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"trainee":{"id":501,"expoId":"$EXPO"},"programs":[{"id":2,"expoId":"$EXPO","category":"CHOICE"}]}"""),
            ).andExpect(status().isConflict)
        val replaced =
            """{"trainee":{"id":501,"expoId":"$EXPO"},"programs":[""" +
                """{"id":3,"expoId":"$EXPO","category":"CHOICE"},{"id":2,"expoId":"$EXPO","category":"CHOICE"}]}"""
        mvc
            .perform(
                put("/internal/training-program-applications/trainee/501")
                    .header("X-Internal-Token", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(replaced),
            ).andExpect(status().isConflict)
        assertEquals(2L, count("tb_training_program_application"))
        assertEquals("WAITING", column("status", waiting.applicationId))
    }

    @Test
    fun `User outage holds the FIFO head and promotes in order after recovery`() {
        waitingCapacity = 2
        addParticipant(3)
        val confirmed = service.execute(command(type = ProgramSessionType.TRAINING))
        val head = service.execute(command(participant = 2, type = ProgramSessionType.TRAINING))
        val next = service.execute(command(participant = 3, type = ProgramSessionType.TRAINING))
        linkFailure = true
        cancel(confirmed.applicationId)
        reconcile(ProgramSessionType.TRAINING)
        assertEquals("WAITING", column("status", head.applicationId))
        assertEquals("WAITING", column("status", next.applicationId))
        linkFailure = false
        reconcile(ProgramSessionType.TRAINING)
        assertEquals("CONFIRMED", column("status", head.applicationId))
        assertEquals("WAITING", column("status", next.applicationId))
        cancel(head.applicationId)
        assertEquals("CONFIRMED", column("status", next.applicationId))
    }

    @Test
    fun `permanent User rejection cancels only the head and the next waiter keeps FIFO`() {
        for (rejection in listOf(404, 409)) {
            reset()
            waitingCapacity = 2
            addParticipant(3)
            val confirmed = service.execute(command(type = ProgramSessionType.TRAINING))
            val head = service.execute(command(participant = 2, type = ProgramSessionType.TRAINING))
            val next = service.execute(command(participant = 3, type = ProgramSessionType.TRAINING))
            linkRejections = mapOf(2L to rejection)
            cancel(confirmed.applicationId)
            assertEquals("CANCELLED", column("status", head.applicationId))
            assertEquals("WAITING", column("status", next.applicationId))
            reconcile(ProgramSessionType.TRAINING)
            assertEquals("CONFIRMED", column("status", next.applicationId))
            assertEquals("CANCELLED", column("status", head.applicationId))
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
            assertThrows(DataIntegrityViolationException::class.java) { cancel(confirmed.applicationId) }
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
        assertEquals(29, linkCalls.get())
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
            assertEquals(listOf(1L, 2L, 3L, 2L, 3L, 4L), linkRequests.map { mapper.readTree(it).path("participantId").asLong() })
            repeat(2) { reconcile(ProgramSessionType.TRAINING) }
            assertEquals(6, linkCalls.get())
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
                            cancelStatus(confirmed.applicationId)
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

    @Test
    fun `back to back sessions are allowed after normalizing KST and offset times`() {
        groups = mapOf("2" to UUID.randomUUID().toString())
        windows =
            mapOf(
                ("STANDARD" to 1L) to ("2030-01-01T09:00:00" to "2030-01-01T10:00:00"),
                ("TRAINING" to 2L) to ("2030-01-01T01:00:00Z" to "2030-01-01T02:00:00Z"),
            )
        assertEquals("CONFIRMED", service.execute(command()).status)
        assertEquals("CONFIRMED", service.execute(command(program = 2, type = ProgramSessionType.TRAINING, key = "adjacent")).status)
        assertEquals(2L, count("tb_program_session_application"))
    }

    @Test
    fun `time conflicts serialize different groups across standard and training and cancelled applications release the window`() {
        val window = "2030-01-01T09:00:00+09:00" to "2030-01-01T10:00:00+09:00"
        windows = mapOf(("STANDARD" to 1L) to window, ("TRAINING" to 2L) to window)
        groups = mapOf("2" to UUID.randomUUID().toString())
        val results = concurrent(command(), command(program = 2, type = ProgramSessionType.TRAINING, key = "training"))
        assertEquals(1, results.count { it == "ok" })
        assertEquals(1, results.count { it == "conflict" })
        val existing = jdbc.queryForObject("SELECT id FROM tb_program_session_application", UUID::class.java)!!
        val next = if (results[0] == "ok") command(program = 2, type = ProgramSessionType.TRAINING, key = "next") else command(key = "next")
        val error = assertThrows(ProgramApplicationConflictException::class.java) { service.execute(next) }
        assertEquals("TIME_CONFLICT", error.ineligibleReason?.name)
        val ledger = context.getBean(ProgramSessionApplicationRepository::class.java)
        val target =
            mapper.readValue(
                definition(next.programId.toString(), next.programType.name),
                team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition::class.java,
            )
        assertEquals(false, ledger.hasTimeConflict(target, 2))
        assertEquals(false, ledger.hasTimeConflict(target.copy(expoId = UUID.randomUUID().toString()), 1))
        cancel(existing)
        assertEquals("CONFIRMED", service.execute(next).status)
    }

    @Test
    fun `waiting applications block overlapping submissions and promotion leaves conflicting head waiting without taking a seat`() {
        waitingCapacity = 2
        groups = mapOf("2" to UUID.randomUUID().toString())
        val confirmed = service.execute(command())
        val waiting = service.execute(command(participant = 2))
        val window = mapper.readTree(definition("1", "STANDARD"))
        windows = mapOf(("TRAINING" to 2L) to (window.path("startedAt").stringValue() to window.path("endedAt").stringValue()))
        val conflict =
            assertThrows(ProgramApplicationConflictException::class.java) {
                service.execute(command(program = 2, participant = 2, type = ProgramSessionType.TRAINING, key = "overlap"))
            }
        assertEquals("TIME_CONFLICT", conflict.ineligibleReason?.name)
        windows = emptyMap()
        val other = service.execute(command(program = 2, participant = 2, type = ProgramSessionType.TRAINING, key = "later"))
        windows = mapOf(("TRAINING" to 2L) to (window.path("startedAt").stringValue() to window.path("endedAt").stringValue()))
        jdbc.update(
            "UPDATE tb_program_session_state SET definition = CAST(? AS jsonb) WHERE program_type = 'TRAINING' AND program_id = 2",
            definition("2", "TRAINING"),
        )
        cancel(confirmed.applicationId)
        assertEquals("WAITING", column("status", waiting.applicationId))
        assertEquals(0L, count("tb_standard_program_application"))
        cancel(other.applicationId)
        reconcile()
        assertEquals("CONFIRMED", column("status", waiting.applicationId))
        assertEquals(1L, count("tb_standard_program_application"))
    }

    @Test
    fun `schedule changes cannot introduce an overlap for an active participant`() {
        groups = mapOf("2" to UUID.randomUUID().toString())
        service.execute(command())
        service.execute(command(program = 2, type = ProgramSessionType.TRAINING, key = "later"))
        val first = mapper.readTree(definition("1", "STANDARD"))
        val previous = mapper.readTree(definition("2", "TRAINING"))
        val next =
            previous.deepCopy().asObject().apply {
                put("revision", 2)
                put("startedAt", first.path("startedAt").stringValue())
                put("endedAt", first.path("endedAt").stringValue())
            }
        mvc
            .perform(
                put("/internal/expos/$EXPO/program-changes/${UUID.randomUUID()}")
                    .header("X-Internal-Token", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"mutations":[{"previous":$previous,"definition":$next}]}"""),
            ).andExpect(status().isConflict)
        assertEquals(0L, count("tb_program_change_receipt"))
    }

    @Test
    fun `legacy attribution adopts only identity and preserves seats receipts snapshots and User actor`() {
        seedLegacyAttribution()
        val id = UUID.randomUUID()
        attribute(id).andExpect(status().isOk).andExpect(jsonPath("$.changed").value(true))
        assertEquals(1L, count("tb_legacy_trainee_attribution"))
        assertEquals(1L, count("tb_legacy_trainee_program"))
        assertEquals(1L, count("tb_training_program_application"))
        assertEquals(0L, count("tb_program_session_application"))
        assertEquals(0L, count("tb_program_session_receipt"))
        assertEquals(0L, count("tb_program_session_event"))
        assertEquals(42, mapper.readTree(attributionCalls.single()).path("actorAdminId").asInt())
        assertEquals("원본 자료와 운영자 확인", mapper.readTree(attributionCalls.single()).path("reason").asString())
        attribute(id).andExpect(status().isOk).andExpect(jsonPath("$.changed").value(true))
        assertEquals(1, attributionCalls.size)
        attribute(UUID.randomUUID()).andExpect(status().isOk).andExpect(jsonPath("$.changed").value(false))
        assertEquals(1L, count("tb_legacy_trainee_program"))
        assertEquals(1L, count("tb_training_program_application"))
    }

    @Test
    fun `legacy attribution requires cancel authority and cannot trust supplied admin headers`() {
        for (denial in listOf(401, 403, 404)) {
            attributionAccessStatus = denial
            attribute(UUID.randomUUID()).andExpect(status().`is`(denial))
            attribute(UUID.randomUUID(), revoke = true).andExpect(status().`is`(denial))
        }
        assertEquals(0, attributionCalls.size)
        assertEquals(0L, count("tb_legacy_trainee_attribution_operation"))
        attributionAccessStatus = 200
        attributionActor = 73
        attribute(UUID.randomUUID()).andExpect(status().isOk)
        assertEquals(73, mapper.readTree(attributionCalls.single()).path("actorAdminId").asInt())
    }

    @Test
    fun `legacy attribution validates IDs reason and operation header before User writes`() {
        val path = "/application/admin/expos/$EXPO/legacy-trainee-attributions"
        for (body in listOf(
            """{"traineeId":0,"participantId":1,"reason":"proof"}""",
            """{"traineeId":700,"participantId":0,"reason":"proof"}""",
            """{"traineeId":700,"participantId":1,"reason":" "}""",
            """{"traineeId":700,"participantId":1}""",
            mapper.writeValueAsString(mapOf("traineeId" to 700, "participantId" to 1, "reason" to "x".repeat(501))),
        )) {
            mvc
                .perform(
                    post(path)
                        .header("Authorization", "Bearer admin")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(status().isBadRequest)
        }
        mvc
            .perform(
                post(path)
                    .header("Authorization", "Bearer admin")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"traineeId":700,"participantId":1,"reason":"proof"}"""),
            ).andExpect(status().isBadRequest)
        assertEquals(0, attributionCalls.size)
        assertEquals(0L, count("tb_legacy_trainee_attribution_operation"))
    }

    @Test
    fun `legacy attribution rejects operation ID reuse for other IDs reasons actions or actors`() {
        val id = UUID.randomUUID()
        attribute(id).andExpect(status().isOk)
        attribute(id, participant = 2).andExpect(status().isConflict)
        attribute(id, revoke = true).andExpect(status().isConflict)
        attributionActor = 43
        attribute(id).andExpect(status().isConflict)
        assertEquals(1, attributionCalls.size)
    }

    @Test
    fun `legacy attribution User rejection writes no projection and preserves conflict status`() {
        for (code in listOf(400, 403, 404, 409)) {
            attributionStatus = code
            val id = UUID.randomUUID()
            attribute(id).andExpect(status().`is`(code))
            attribute(id).andExpect(status().`is`(code))
            mvc
                .perform(
                    get("/application/admin/expos/$EXPO/legacy-trainee-attributions/operations/$id")
                        .header("Authorization", "Bearer admin"),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.state").value("REJECTED"))
        }
        assertEquals(4, attributionCalls.size)
        assertEquals(0L, count("tb_legacy_trainee_attribution"))
    }

    @Test
    fun `legacy attribution response loss holds gates and same operation repairs committed User link`() {
        seedLegacyAttribution()
        val id = UUID.randomUUID()
        attributionMalformed = true
        attribute(id).andExpect(status().isBadGateway)
        assertEquals(1L, attributionParticipant)
        assertEquals(0L, count("tb_legacy_trainee_attribution"))
        mvc
            .perform(
                get("/application/admin/expos/$EXPO/legacy-trainee-attributions/operations/$id")
                    .header("Authorization", "Bearer admin"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.state").value("PENDING"))
            .andExpect(jsonPath("$.errorStatus").value(502))
            .andExpect(jsonPath("$.reason").doesNotExist())
        val error = assertThrows(ProgramApplicationConflictException::class.java) { service.execute(command()) }
        assertEquals("TIME_CONFLICT", error.ineligibleReason?.name)
        attribute(UUID.randomUUID(), revoke = true).andExpect(status().isConflict)
        attributionMalformed = false
        windows = mapOf(("TRAINING" to 2L) to ("invalid" to "invalid"))
        attribute(id).andExpect(status().isBadGateway)
        assertEquals(
            "PENDING",
            jdbc.queryForObject("SELECT state FROM tb_legacy_trainee_attribution_operation WHERE id = ?", String::class.java, id),
        )
        windows = emptyMap()
        attribute(id).andExpect(status().isOk).andExpect(jsonPath("$.changed").value(false))
        assertEquals(1L, count("tb_legacy_trainee_attribution"))
        assertEquals(1L, count("tb_training_program_application"))
    }

    @Test
    fun `legacy attribution local commit failure retains durable operation and retries without duplicate seats`() {
        seedLegacyAttribution()
        val id = UUID.randomUUID()
        attributionAfterUser = {
            jdbc.execute("ALTER TABLE tb_legacy_trainee_program ADD CONSTRAINT test_attribution_failure CHECK (program_id < 0)")
        }
        try {
            attribute(id).andExpect(status().isServiceUnavailable)
            assertEquals(1L, attributionParticipant)
            assertEquals(0L, count("tb_legacy_trainee_attribution"))
            assertEquals(
                "PENDING",
                jdbc.queryForObject("SELECT state FROM tb_legacy_trainee_attribution_operation WHERE id = ?", String::class.java, id),
            )
        } finally {
            attributionAfterUser = null
            jdbc.execute("ALTER TABLE tb_legacy_trainee_program DROP CONSTRAINT IF EXISTS test_attribution_failure")
        }
        attribute(id).andExpect(status().isOk).andExpect(jsonPath("$.changed").value(false))
        assertEquals(1L, count("tb_training_program_application"))
        assertEquals(1L, count("tb_legacy_trainee_program"))
    }

    @Test
    fun `legacy attribution adopted training blocks overlapping standard while leaving companions independent`() {
        seedLegacyAttribution()
        val overlap = "2030-01-01T09:00:00+09:00" to "2030-01-01T10:00:00+09:00"
        windows = mapOf(("STANDARD" to 1L) to overlap, ("TRAINING" to 2L) to overlap)
        attribute(UUID.randomUUID()).andExpect(status().isOk)
        val error = assertThrows(ProgramApplicationConflictException::class.java) { service.execute(command()) }
        assertEquals("TIME_CONFLICT", error.ineligibleReason?.name)
        assertEquals("CONFIRMED", service.execute(command(participant = 2)).status)
        assertEquals(1L, count("tb_training_program_application"))
    }

    @Test
    fun `legacy attribution revoke removes only adopted ownership and keeps original application and standard history`() {
        seedLegacyAttribution()
        val receipt = service.execute(command())
        attribute(UUID.randomUUID()).andExpect(status().isOk)
        val revokeId = UUID.randomUUID()
        attribute(revokeId, revoke = true).andExpect(status().isOk)
        attribute(revokeId, revoke = true).andExpect(status().isOk)
        assertEquals(0L, count("tb_legacy_trainee_attribution"))
        assertEquals(0L, count("tb_legacy_trainee_program"))
        assertEquals(1L, count("tb_training_program_application"))
        assertEquals("CONFIRMED", column("status", receipt.applicationId))
        assertEquals(1L, count("tb_program_session_event"))
        assertEquals(1L, count("tb_program_session_receipt"))
        attribute(UUID.randomUUID(), participant = 2).andExpect(status().isOk)
        assertEquals(2L, jdbc.queryForObject("SELECT participant_id FROM tb_legacy_trainee_attribution", Long::class.java))
    }

    @Test
    fun `legacy attribution revoke response loss preserves gate until cleanup resumes even when User changed is false`() {
        seedLegacyAttribution()
        attribute(UUID.randomUUID()).andExpect(status().isOk)
        val id = UUID.randomUUID()
        attributionMalformed = true
        attribute(id, revoke = true).andExpect(status().isBadGateway)
        assertEquals(null, attributionParticipant)
        assertEquals(1L, count("tb_legacy_trainee_attribution"))
        attributionMalformed = false
        attribute(id, revoke = true).andExpect(status().isOk).andExpect(jsonPath("$.changed").value(false))
        assertEquals(0L, count("tb_legacy_trainee_attribution"))
        assertEquals(1L, count("tb_training_program_application"))
    }

    @Test
    fun `legacy attribution FIFO promotion waits for conflict and resumes after revoke without returning legacy seat`() {
        waitingCapacity = 1
        val confirmed = service.execute(command())
        val waiting = service.execute(command(participant = 2))
        seedLegacyAttribution()
        val first = mapper.readTree(definition("1", "STANDARD"))
        windows = mapOf(("TRAINING" to 2L) to (first.path("startedAt").asString() to first.path("endedAt").asString()))
        attribute(UUID.randomUUID(), participant = 2).andExpect(status().isOk)
        cancel(confirmed.applicationId)
        assertEquals("WAITING", column("status", waiting.applicationId))
        assertEquals(0L, count("tb_standard_program_application"))
        attribute(UUID.randomUUID(), participant = 2, revoke = true).andExpect(status().isOk)
        reconcile()
        assertEquals("CONFIRMED", column("status", waiting.applicationId))
        assertEquals(1L, count("tb_training_program_application"))
    }

    @Test
    fun `legacy attribution schedule validation includes adopted trainees with no session receipt`() {
        seedLegacyAttribution()
        service.execute(command())
        attribute(UUID.randomUUID()).andExpect(status().isOk)
        val first = mapper.readTree(definition("1", "STANDARD"))
        val previous = mapper.readTree(definition("2", "TRAINING"))
        val next =
            previous.deepCopy().asObject().apply {
                put("revision", 2)
                put("startedAt", first.path("startedAt").asString())
                put("endedAt", first.path("endedAt").asString())
            }
        mvc
            .perform(
                put("/internal/expos/$EXPO/program-changes/${UUID.randomUUID()}")
                    .header("X-Internal-Token", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"mutations":[{"previous":$previous,"definition":$next}]}"""),
            ).andExpect(status().isConflict)
        assertEquals(0L, count("tb_program_change_receipt"))
    }

    @Test
    fun `legacy attribution legacy cancellation removes time occupancy while retaining attribution for future applications`() {
        seedLegacyAttribution()
        val overlap = "2030-01-01T09:00:00+09:00" to "2030-01-01T10:00:00+09:00"
        windows = mapOf(("STANDARD" to 1L) to overlap, ("TRAINING" to 2L) to overlap)
        attribute(UUID.randomUUID()).andExpect(status().isOk)
        mvc
            .perform(
                put("/internal/training-program-applications/trainee/700")
                    .header("X-Internal-Token", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"trainee":{"id":700,"expoId":"$EXPO"},"programs":[]}"""),
            ).andExpect(status().isNoContent)
        assertEquals(0L, count("tb_training_program_application"))
        assertEquals(1L, count("tb_legacy_trainee_attribution"))
        assertEquals("CONFIRMED", service.execute(command()).status)
    }

    @Test
    fun `legacy attribution status hides missing and other expo operations and never returns verification reason`() {
        val id = UUID.randomUUID()
        attribute(id).andExpect(status().isOk)
        for ((owner, operation) in listOf(EXPO to UUID.randomUUID(), UUID.randomUUID().toString() to id)) {
            mvc
                .perform(
                    get("/application/admin/expos/$owner/legacy-trainee-attributions/operations/$operation")
                        .header("Authorization", "Bearer admin"),
                ).andExpect(status().isNotFound)
        }
        mvc
            .perform(
                get("/application/admin/expos/$EXPO/legacy-trainee-attributions/operations/$id")
                    .header("Authorization", "Bearer admin"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.state").value("APPLIED"))
            .andExpect(jsonPath("$.reason").doesNotExist())
            .andExpect(jsonPath("$.authorization").doesNotExist())
    }

    @ParameterizedTest
    @CsvSource("INVALID,502", "MISSING_TIME,503", "DELETED,503", "UNAVAILABLE,503")
    fun `legacy attribution pre User validation failure releases gates and records FAILED`(
        failure: String,
        code: Int,
    ) {
        seedLegacyAttribution()
        val id = UUID.randomUUID()
        when (failure) {
            "INVALID" -> windows = mapOf(("TRAINING" to 2L) to ("invalid" to "invalid"))
            "MISSING_TIME" -> windows = mapOf(("TRAINING" to 2L) to ("" to ""))
            "DELETED" -> missingProgram = 2
            "UNAVAILABLE" -> unavailableLookup = 2
        }
        attribute(id).andExpect(status().`is`(code))
        assertEquals(0, attributionCalls.size)
        assertEquals(
            "FAILED",
            jdbc.queryForObject("SELECT state FROM tb_legacy_trainee_attribution_operation WHERE id = ?", String::class.java, id),
        )
        assertEquals(
            0,
            jdbc.queryForObject("SELECT attempts FROM tb_legacy_trainee_attribution_operation WHERE id = ?", Int::class.java, id),
        )
        attribute(id).andExpect(status().`is`(code))
        windows = emptyMap()
        missingProgram = null
        unavailableLookup = null
        change { it.put("capacity", capacity + 1) }
        assertEquals("CONFIRMED", service.execute(command()).status)
        attribute(UUID.randomUUID()).andExpect(status().isOk)
    }

    @Test
    fun `legacy attribution revoke rejects active trainee ledger entries until cancelled`() {
        seedLegacyAttribution()
        attribute(UUID.randomUUID()).andExpect(status().isOk)
        val receipt = service.execute(command(program = 3, type = ProgramSessionType.TRAINING))
        for (state in listOf("CONFIRMED", "WAITING")) {
            jdbc.update("UPDATE tb_program_session_application SET status = ? WHERE id = ?", state, receipt.applicationId)
            attribute(UUID.randomUUID(), revoke = true).andExpect(status().isConflict)
            assertEquals(1L, attributionParticipant)
            assertEquals(1L, count("tb_legacy_trainee_attribution"))
        }
        jdbc.update("UPDATE tb_program_session_application SET status = 'CONFIRMED' WHERE id = ?", receipt.applicationId)
        cancel(receipt.applicationId)
        attribute(UUID.randomUUID(), revoke = true).andExpect(status().isOk)
        assertEquals(null, attributionParticipant)
    }

    @Test
    fun `legacy attribution time conflicts refresh policyless training after Expo time change`() {
        seedLegacyAttribution()
        noPolicyProgram = 2
        val old = "2030-01-01T10:00:00+09:00" to "2030-01-01T11:00:00+09:00"
        val current = "2030-01-01T13:00:00+09:00" to "2030-01-01T14:00:00+09:00"
        windows = mapOf(("TRAINING" to 2L) to old)
        attribute(UUID.randomUUID()).andExpect(status().isOk)
        windows = mapOf(("TRAINING" to 2L) to current, ("STANDARD" to 1L) to current)
        val error = assertThrows(ProgramApplicationConflictException::class.java) { service.execute(command()) }
        assertEquals("TIME_CONFLICT", error.ineligibleReason?.name)
        windows = windows + (("STANDARD" to 1L) to old)
        assertEquals("CONFIRMED", service.execute(command()).status)
    }

    @Test
    fun `legacy attribution unavailable current times defer promotion without rolling back cancellation`() {
        capacity = 1
        waitingCapacity = 1
        val confirmed = service.execute(command(participant = 2))
        seedLegacyAttribution()
        attribute(UUID.randomUUID()).andExpect(status().isOk)
        val waiting = service.execute(command())
        assertEquals("WAITING", waiting.status)
        unavailableLookup = 2
        cancel(confirmed.applicationId)
        assertEquals("CANCELLED", column("status", confirmed.applicationId))
        assertEquals("WAITING", column("status", waiting.applicationId))
        unavailableLookup = null
        reconcile()
        assertEquals("CONFIRMED", column("status", waiting.applicationId))
    }

    private fun seedLegacyAttribution() {
        jdbc.update("INSERT INTO tb_training_program_application (trainee_id, training_program_id) VALUES (700, 2)")
        groups = mapOf("2" to UUID.randomUUID().toString())
    }

    @Test
    fun `legacy attribution preexisting conflicts do not prevent a removal only legacy replacement`() {
        seedLegacyAttribution()
        jdbc.update("INSERT INTO tb_training_program_application (trainee_id, training_program_id) VALUES (700, 3)")
        groups = groups + ("3" to UUID.randomUUID().toString())
        val first = mapper.readTree(definition("1", "STANDARD"))
        val overlap = first.path("startedAt").asString() to first.path("endedAt").asString()
        windows = mapOf(("TRAINING" to 2L) to overlap, ("TRAINING" to 3L) to overlap)
        service.execute(command())
        attribute(UUID.randomUUID()).andExpect(status().isOk)
        mvc
            .perform(
                put("/internal/training-program-applications/trainee/700")
                    .header("X-Internal-Token", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"trainee":{"id":700,"expoId":"$EXPO"},"programs":[{"id":3,"expoId":"$EXPO","category":"CHOICE"}]}"""),
            ).andExpect(status().isNoContent)
        assertEquals(1L, count("tb_training_program_application"))
        assertEquals(3L, jdbc.queryForObject("SELECT training_program_id FROM tb_training_program_application", Long::class.java))
    }

    @Test
    fun `legacy attribution training add and replace check standard conflicts and preserve new projection`() {
        seedLegacyAttribution()
        service.execute(command())
        attribute(UUID.randomUUID()).andExpect(status().isOk)
        val first = mapper.readTree(definition("1", "STANDARD"))
        groups = groups + ("3" to UUID.randomUUID().toString())
        noPolicyProgram = 0
        windows = mapOf(("TRAINING" to 3L) to (first.path("startedAt").asString() to first.path("endedAt").asString()))
        val body = """{"trainee":{"id":700,"expoId":"$EXPO"},"programs":[{"id":3,"expoId":"$EXPO","category":"CHOICE"}]}"""
        mvc
            .perform(
                post("/internal/training-program-applications")
                    .header("X-Internal-Token", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isConflict)
        mvc
            .perform(
                put("/internal/training-program-applications/trainee/700")
                    .header("X-Internal-Token", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isConflict)
        assertEquals(1L, count("tb_training_program_application"))
        windows = emptyMap()
        mvc
            .perform(
                post("/internal/training-program-applications")
                    .header("X-Internal-Token", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isCreated)
        mvc
            .perform(
                put("/internal/training-program-applications/trainee/700")
                    .header("X-Internal-Token", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isNoContent)
        assertEquals(1L, count("tb_training_program_application"))
        val ledger = context.getBean(ProgramSessionApplicationRepository::class.java)
        val target =
            mapper.readValue(
                definition("3", "TRAINING"),
                team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition::class.java,
            )
        assertEquals(true, ledger.hasTimeConflict(target.copy(programType = ProgramSessionType.STANDARD, id = 4), 1))
        val removed =
            mapper.readValue(
                definition("2", "TRAINING"),
                team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition::class.java,
            )
        assertEquals(false, ledger.hasTimeConflict(removed.copy(programType = ProgramSessionType.STANDARD, id = 4), 1))
    }

    private fun attribute(
        id: UUID,
        participant: Long = 1,
        revoke: Boolean = false,
    ): org.springframework.test.web.servlet.ResultActions =
        mvc.perform(
            post("/application/admin/expos/$EXPO/legacy-trainee-attributions" + if (revoke) "/revoke" else "")
                .header("Authorization", "Bearer admin")
                .header("X-User-Id", "9999")
                .header("Idempotency-Key", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"traineeId":700,"participantId":$participant,"reason":"원본 자료와 운영자 확인"}"""),
        )

    @ParameterizedTest
    @CsvSource("STANDARD", "TRAINING")
    fun `split seats enforce pre registration quota with replay cancellation and immutable route`(type: ProgramSessionType) {
        capacity = 3
        waitingCapacity = 1
        admissionCapacities = "\"preRegistrationCapacity\":1,\"onsiteCapacity\":2"
        val first = service.execute(command(type = type))
        assertEquals("PRE_REGISTRATION", column("admission_route", first.applicationId))
        assertEquals(first, service.execute(command(type = type).copy(sessionId = "renewed-session")))
        val waiting = service.execute(command(participant = 2, type = type))
        assertEquals("WAITING", waiting.status)
        assertEquals(null, waiting.traineeId)
        assertEquals("PRE_REGISTRATION", column("admission_route", waiting.applicationId))
        assertEquals(waiting, service.execute(command(participant = 2, type = type)))
        managedIds = listOf(1, 2, 3)
        addParticipant(3)
        assertThrows(ProgramApplicationConflictException::class.java) { service.execute(command(participant = 3, type = type)) }
        assertEquals(2L, count("tb_program_session_application"))
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update("UPDATE tb_program_session_application SET admission_route = 'ONSITE' WHERE id = ?", first.applicationId)
        }
        cancel(first.applicationId)
        cancel(first.applicationId)
        assertEquals("CONFIRMED", column("status", waiting.applicationId))
        assertEquals("PRE_REGISTRATION", column("admission_route", waiting.applicationId))
        assertEquals("PRE_REGISTRATION", column("admission_route", first.applicationId))
        val table = if (type == ProgramSessionType.STANDARD) "tb_standard_program_application" else "tb_training_program_application"
        assertEquals(1L, count(table))
    }

    @Test
    fun `split seats queue pre registration waiters in FIFO order and never promote into onsite seats`() {
        capacity = 3
        waitingCapacity = 2
        admissionCapacities = "\"preRegistrationCapacity\":1,\"onsiteCapacity\":2"
        managedIds = listOf(1, 2, 3)
        addParticipant(3)
        val first = service.execute(command())
        val second = service.execute(command(participant = 2))
        val third = service.execute(command(participant = 3))
        assertEquals(listOf("CONFIRMED", "WAITING", "WAITING"), listOf(first, second, third).map { it.status })
        reconcile()
        assertEquals("WAITING", column("status", second.applicationId))
        assertEquals(1L, count("tb_standard_program_application"))
        mvc
            .perform(
                get("/application/expos/$EXPO/program-sessions/STANDARD/1/participants")
                    .cookie(Cookie("PARTICIPANT_SESSION", "session")),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.capacity.remaining").value(2))
            .andExpect(jsonPath("$.capacity.waitingCount").value(2))
            .andExpect(jsonPath("$.admissionSeats.preRegistration.remaining").value(0))
            .andExpect(jsonPath("$.admissionSeats.onsite.remaining").value(2))
            .andExpect(jsonPath("$.admissionSeats.waitingEnabled").value(true))
            .andExpect(jsonPath("$.admissionSeats.onsiteAdmissionEnabled").value(false))
        cancel(first.applicationId)
        assertEquals("CONFIRMED", column("status", second.applicationId))
        assertEquals("WAITING", column("status", third.applicationId))
        assertEquals("PRE_REGISTRATION", column("admission_route", second.applicationId))
        assertEquals(1L, count("tb_standard_program_application"))
    }

    @Test
    fun `onsite admission confirms within onsite seats, rejects overflow without waiting, and ignores the pre queue`() {
        capacity = 3
        waitingCapacity = 2
        admissionCapacities = "\"preRegistrationCapacity\":1,\"onsiteCapacity\":2$ONSITE_OPEN"
        managedIds = listOf(1, 2, 3, 4, 5)
        fieldIds = setOf(4, 5)
        addParticipant(3)
        service.execute(command())
        val waiting = service.execute(command(participant = 2))
        assertEquals("WAITING", waiting.status)
        val preregistered = onsiteService.execute(command(participant = 3))
        val field = onsiteService.execute(command(participant = 4))
        listOf(preregistered, field).forEach {
            assertEquals("CONFIRMED", it.status)
            assertEquals("ONSITE", column("admission_route", it.applicationId))
        }
        assertEquals(field, onsiteService.execute(command(participant = 4)))
        assertThrows(ProgramApplicationConflictException::class.java) { service.execute(command(participant = 4)) }
        val rows = count("tb_program_session_application")
        assertThrows(ProgramApplicationConflictException::class.java) { onsiteService.execute(command(participant = 5)) }
        assertEquals(rows, count("tb_program_session_application"))
        reconcile()
        cancel(preregistered.applicationId)
        assertEquals("WAITING", column("status", waiting.applicationId))
        assertEquals("ONSITE", column("admission_route", preregistered.applicationId))
        val replacement = onsiteService.execute(command(participant = 5))
        assertEquals("CONFIRMED", replacement.status)
        assertEquals("ONSITE", column("admission_route", replacement.applicationId))
        assertEquals(3L, count("tb_standard_program_application"))
    }

    @Test
    fun `onsite admission follows its own window instead of the pre period and fails closed without one`() {
        capacity = 3
        val capacities = "\"preRegistrationCapacity\":1,\"onsiteCapacity\":2"
        expired = true
        admissionCapacities = capacities
        assertThrows(ProgramApplicationConflictException::class.java) { service.execute(command()) }
        assertThrows(ProgramApplicationConflictException::class.java) { onsiteService.execute(command()) }
        admissionCapacities = capacities + ",\"onsiteStartedAt\":\"2099-01-01T00:00:00Z\",\"onsiteEndedAt\":\"2100-01-01T00:00:00Z\""
        assertThrows(ProgramApplicationConflictException::class.java) { onsiteService.execute(command()) }
        admissionCapacities = capacities + ",\"onsiteStartedAt\":\"2020-01-01T00:00:00Z\",\"onsiteEndedAt\":\"2021-01-01T00:00:00Z\""
        assertThrows(ProgramApplicationConflictException::class.java) { onsiteService.execute(command()) }
        admissionCapacities = capacities + ",\"onsiteStartedAt\":\"2020-01-01T00:00:00Z\""
        assertEquals(502, assertThrows(ResponseStatusException::class.java) { onsiteService.execute(command()) }.statusCode.value())
        assertEquals(0L, count("tb_program_session_application"))
        admissionCapacities = capacities + ONSITE_OPEN
        assertEquals("ONSITE", column("admission_route", onsiteService.execute(command()).applicationId))
        jdbc.update("UPDATE tb_preregister_application SET status = 'CANCELLED' WHERE participant_id = 2")
        val error = assertThrows(ResponseStatusException::class.java) { onsiteService.execute(command(participant = 2)) }
        assertEquals(403, error.statusCode.value())
        managedIds = listOf(1, 2, 3, 4)
        fieldIds = setOf(3)
        // User가 사전 등록(PRE)으로 저장했지만 확정된 사전등록 원장이 없는 참가자는 현장 등록자가 아니다
        val notField = assertThrows(ResponseStatusException::class.java) { onsiteService.execute(command(participant = 4)) }
        assertEquals(403, notField.statusCode.value())
        formMode = "period"
        assertThrows(ProgramApplicationConflictException::class.java) { onsiteService.execute(command(participant = 3)) }
        reset()
        capacity = 3
        managedIds = listOf(1, 2, 3)
        fieldIds = setOf(3)
        admissionCapacities = null
        assertThrows(ProgramApplicationConflictException::class.java) { onsiteService.execute(command(participant = 3)) }
        assertEquals(0L, count("tb_program_session_application"))
    }

    @Test
    fun `onsite http entry point requires csrf and the participants view judges onsite eligibility`() {
        capacity = 3
        admissionCapacities = "\"preRegistrationCapacity\":1,\"onsiteCapacity\":2$ONSITE_OPEN"
        managedIds = listOf(1, 2, 3)
        fieldIds = setOf(3)
        val path = "/application/expos/$EXPO/program-sessions/STANDARD/1/onsite-applications"
        val body = """{"participantId":3,"formVersion":1,"answers":{"101":"a"}}"""
        mvc
            .perform(
                post(path)
                    .cookie(Cookie("PARTICIPANT_SESSION", "session"))
                    .header("Idempotency-Key", "onsite")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isForbidden)
        for (onsite in listOf(false, true)) {
            mvc
                .perform(
                    get("/application/expos/$EXPO/program-sessions/STANDARD/1/participants?onsite=$onsite")
                        .cookie(Cookie("PARTICIPANT_SESSION", "session")),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.participants[2].participant.participantId").value(3))
                .andExpect(jsonPath("$.participants[2].eligible").value(onsite))
                .andExpect(jsonPath("$.admissionSeats.onsiteAdmissionEnabled").value(true))
        }
        val created =
            mvc
                .perform(
                    post(path)
                        .with(csrf())
                        .cookie(Cookie("PARTICIPANT_SESSION", "session"))
                        .header("Idempotency-Key", "onsite")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andReturn()
        val id = UUID.fromString(mapper.readTree(created.response.contentAsString).path("applicationId").stringValue())
        assertEquals("ONSITE", column("admission_route", id))
    }

    @Test
    fun `onsite guardian batches confirm atomically within onsite seats and never wait`() {
        enableGuardianPolicy(4, 5)
        admissionCapacities = "\"preRegistrationCapacity\":2,\"onsiteCapacity\":2$ONSITE_OPEN"
        assertThrows(ProgramApplicationConflictException::class.java) {
            onsiteBatchService.execute(guardianBatch(1, 2))
        }
        assertEquals(0L, count("tb_program_guardian_batch"))
        assertEquals(0L, count("tb_program_session_application"))
        val result = onsiteBatchService.execute(guardianBatch(1))
        result.applications.forEach {
            assertEquals("CONFIRMED", it.application.status)
            assertEquals("ONSITE", column("admission_route", it.application.applicationId))
        }
        assertEquals(result, onsiteBatchService.execute(guardianBatch(1)))
        assertThrows(ProgramApplicationConflictException::class.java) { batchService.execute(guardianBatch(1)) }
    }

    @Test
    fun `split seats serialize the last pre seat without borrowing onsite seats`() {
        capacity = 3
        admissionCapacities = "\"preRegistrationCapacity\":1,\"onsiteCapacity\":2"
        val results = concurrent(command(), command(participant = 2))
        assertEquals(1, results.count { it == "ok" })
        assertEquals(1, results.count { it == "conflict" })
        assertEquals(1L, count("tb_standard_program_application"))
        assertEquals(1L, count("tb_program_session_application"))
    }

    @Test
    fun `split seats block forged onsite selection and expose both quotas without double counting`() {
        capacity = 3
        admissionCapacities = "\"preRegistrationCapacity\":1,\"onsiteCapacity\":2"
        service.execute(command())
        mvc
            .perform(
                post("/application/expos/$EXPO/program-sessions/STANDARD/1/applications?admissionRoute=ONSITE")
                    .with(csrf())
                    .cookie(Cookie("PARTICIPANT_SESSION", "session"))
                    .header("Idempotency-Key", "forged")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"participantId":2,"formVersion":1,"answers":{"101":"a"},"admissionRoute":"ONSITE"}"""),
            ).andExpect(status().isConflict)
        mvc
            .perform(
                get("/application/expos/$EXPO/program-sessions/STANDARD/1/participants")
                    .cookie(Cookie("PARTICIPANT_SESSION", "session")),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.capacity.confirmedCount").value(1))
            .andExpect(jsonPath("$.admissionSeats.preRegistration.capacity").value(1))
            .andExpect(jsonPath("$.admissionSeats.preRegistration.remaining").value(0))
            .andExpect(jsonPath("$.admissionSeats.onsite.capacity").value(2))
            .andExpect(jsonPath("$.admissionSeats.onsite.confirmedCount").value(0))
            .andExpect(jsonPath("$.admissionSeats.onsite.remaining").value(2))
            .andExpect(jsonPath("$.admissionSeats.onsiteAdmissionEnabled").value(false))
            .andExpect(jsonPath("$.admissionSeats.waitingEnabled").value(false))
    }

    @Test
    fun `split seats rollback failures and retry uses the same route exactly once`() {
        capacity = 3
        admissionCapacities = "\"preRegistrationCapacity\":1,\"onsiteCapacity\":2"
        jdbc.execute("ALTER TABLE tb_program_session_event ADD CONSTRAINT test_split_event CHECK (status <> 'CONFIRMED')")
        try {
            assertThrows(DataIntegrityViolationException::class.java) { service.execute(command()) }
            assertEquals(0L, count("tb_program_session_application"))
            assertEquals(0L, count("tb_standard_program_application"))
            assertEquals(0L, count("tb_program_session_receipt"))
        } finally {
            jdbc.execute("ALTER TABLE tb_program_session_event DROP CONSTRAINT test_split_event")
        }
        val result = service.execute(command())
        assertEquals(result, service.execute(command()))
        assertEquals("PRE_REGISTRATION", column("admission_route", result.applicationId))
        assertEquals(1L, count("tb_standard_program_application"))
    }

    @Test
    fun `split seats queue atomic guardian batches instead of borrowing onsite capacity`() {
        enableGuardianPolicy(4, 2)
        admissionCapacities = "\"preRegistrationCapacity\":2,\"onsiteCapacity\":2"
        assertThrows(ProgramApplicationConflictException::class.java) { batchService.execute(guardianBatch(1, 2)) }
        assertEquals(0L, count("tb_program_guardian_batch"))
        assertEquals(0L, count("tb_program_session_application"))
        val result = batchService.execute(guardianBatch(1))
        assertTrue(result.applications.all { it.application.status == "CONFIRMED" })
        assertEquals(result, batchService.execute(guardianBatch(1)))
        result.applications.forEach { assertEquals("PRE_REGISTRATION", column("admission_route", it.application.applicationId)) }
        reset()
        enableGuardianPolicy(4, 5)
        admissionCapacities = "\"preRegistrationCapacity\":2,\"onsiteCapacity\":2"
        guardianPolicy = false
        val single = service.execute(command(participant = 4, key = "single"))
        guardianPolicy = true
        val batch = batchService.execute(guardianBatch(1, 2))
        batch.applications.forEach {
            assertEquals("WAITING", it.application.status)
            assertEquals("PRE_REGISTRATION", column("admission_route", it.application.applicationId))
        }
        cancel(single.applicationId)
        reconcile()
        batch.applications.forEach { assertEquals("WAITING", column("status", it.application.applicationId)) }
        assertEquals(0L, count("tb_standard_program_application"))
        change {
            it.put("preRegistrationCapacity", 3)
            it.put("onsiteCapacity", 1)
        }
        admissionCapacities = "\"preRegistrationCapacity\":3,\"onsiteCapacity\":1"
        reconcile()
        batch.applications.forEach {
            assertEquals("CONFIRMED", column("status", it.application.applicationId))
            assertEquals("PRE_REGISTRATION", column("admission_route", it.application.applicationId))
        }
        assertEquals(3L, count("tb_standard_program_application"))
    }

    @Test
    fun `split seats reject malformed provider quotas before linking a trainee`() {
        capacity = 3
        for (fields in listOf(
            "\"preRegistrationCapacity\":1",
            "\"preRegistrationCapacity\":null,\"onsiteCapacity\":null",
            "\"preRegistrationCapacity\":1.5,\"onsiteCapacity\":2",
            "\"preRegistrationCapacity\":\"1\",\"onsiteCapacity\":2",
            "\"onsiteCapacity\":2",
            "\"preRegistrationCapacity\":-1,\"onsiteCapacity\":4",
            "\"preRegistrationCapacity\":1,\"onsiteCapacity\":1",
            "\"preRegistrationCapacity\":2147483647,\"onsiteCapacity\":2147483647",
        )) {
            admissionCapacities = fields
            val error = assertThrows(ResponseStatusException::class.java) { service.execute(command(type = ProgramSessionType.TRAINING)) }
            assertEquals(502, error.statusCode.value())
        }
        assertEquals(0, linkCalls.get())
        assertEquals(0L, count("tb_program_session_application"))
    }

    @Test
    fun `split seats cannot shrink an occupied route or revert to total only capacity`() {
        capacity = 3
        admissionCapacities = "\"preRegistrationCapacity\":1,\"onsiteCapacity\":2"
        service.execute(command())
        val previous = mapper.readTree(definition("1", "STANDARD"))
        for (remove in listOf(false, true)) {
            val next = previous.deepCopy().asObject().apply { put("revision", 2) }
            next.path("policy").asObject().apply {
                if (remove) {
                    remove("preRegistrationCapacity")
                    remove("onsiteCapacity")
                } else {
                    put("preRegistrationCapacity", 0)
                    put("onsiteCapacity", 3)
                }
            }
            mvc
                .perform(
                    put("/internal/expos/$EXPO/program-changes/${UUID.randomUUID()}")
                        .header("X-Internal-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"mutations":[{"previous":$previous,"definition":$next}]}"""),
                ).andExpect(status().isConflict)
        }
        assertEquals(0L, count("tb_program_change_receipt"))
        change {
            it.put("onsiteCapacity", 3)
            it.put("capacity", 4)
        }
        admissionCapacities = "\"preRegistrationCapacity\":1,\"onsiteCapacity\":3"
        reconcile()
        assertThrows(ProgramApplicationConflictException::class.java) { service.execute(command(participant = 2)) }
    }

    @Test
    fun `split seats never reclassify legacy confirmed rows and reject adoption with waiting parties`() {
        capacity = 4
        service.execute(command())
        change {
            it.put("preRegistrationCapacity", 2)
            it.put("onsiteCapacity", 2)
        }
        admissionCapacities = "\"preRegistrationCapacity\":2,\"onsiteCapacity\":2"
        reconcile()
        val definition =
            mapper.readValue(
                definition("1", "STANDARD"),
                team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition::class.java,
            )
        val seats = requireNotNull(context.getBean(ProgramSessionApplicationRepository::class.java).admissionSeats(definition))
        assertEquals(1L, seats.unknownConfirmedCount)
        assertEquals(1L, seats.preRegistration.remaining)
        assertEquals(1L, seats.onsite.remaining)
        assertEquals(null, jdbc.queryForObject("SELECT admission_route FROM tb_program_session_application", String::class.java))
        reset()
        waitingCapacity = 2
        service.execute(command())
        service.execute(command(participant = 2))
        val previous = mapper.readTree(definition("1", "STANDARD"))
        val next = previous.deepCopy().asObject().apply { put("revision", 2) }
        next.path("policy").asObject().apply {
            put("capacity", 3)
            put("preRegistrationCapacity", 1)
            put("onsiteCapacity", 2)
        }
        mvc
            .perform(
                put("/internal/expos/$EXPO/program-changes/${UUID.randomUUID()}")
                    .header("X-Internal-Token", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"mutations":[{"previous":$previous,"definition":$next}]}"""),
            ).andExpect(status().isConflict)
    }

    @Test
    fun `split seats preserve enabled guardian policy in supplier decoding and saved snapshots`() {
        capacity = 3
        val guardian = ProgramGuardianPolicy(1, 101, "primary", 102, (1..6).associate { "g$it" to it })
        admissionCapacities = "\"preRegistrationCapacity\":1,\"onsiteCapacity\":2,\"guardianPolicy\":${mapper.writeValueAsString(guardian)}"
        val supplied =
            requireNotNull(context.getBean(RegistrationGateway::class.java).programDefinition(EXPO, ProgramSessionType.STANDARD, 1))
        assertEquals(guardian, supplied.policy?.guardianPolicy)
        assertEquals(1, supplied.policy?.preRegistrationCapacity)
        assertEquals(2, supplied.policy?.onsiteCapacity)
        assertEquals(supplied, mapper.readValue(mapper.writeValueAsString(supplied), supplied.javaClass))
        val result = service.execute(command())
        val snapshot =
            jdbc.queryForObject(
                "SELECT definition_snapshot::text FROM tb_program_session_application WHERE id = ?",
                String::class.java,
                result.applicationId,
            )
        assertEquals(supplied, mapper.readValue(snapshot, supplied.javaClass))
    }

    @Test
    fun `split seats enabled guardian policy fails closed when eligibility provider is missing`() {
        capacity = 3
        val guardian = ProgramGuardianPolicy(1, 101, "primary", 102, (1..6).associate { "g$it" to it })
        admissionCapacities = "\"preRegistrationCapacity\":1,\"onsiteCapacity\":2,\"guardianPolicy\":${mapper.writeValueAsString(guardian)}"
        val withoutGuardian =
            ProgramSessionAdmission(
                context.getBean(ProgramSessionApplicationRepository::class.java),
                context.getBean(StandardProgramApplicationRepository::class.java),
                context.getBean(TrainingProgramApplicationRepository::class.java),
                context.getBean(TrainingOperationRepository::class.java),
                context.getBean(RegistrationGateway::class.java),
                context.getBeanProvider(ProgramFormProvider::class.java),
                context.getBean(ProgramWaitlist::class.java),
                context.getBean(ProgramWaitlistRepository::class.java),
                DefaultListableBeanFactory().getBeanProvider(StudentGuardianEligibilityProvider::class.java),
                transactions,
            )
        val error =
            assertThrows(ResponseStatusException::class.java) {
                transactions.execute { withoutGuardian.admit(command(), onsite = false) }
            }
        assertEquals(503, error.statusCode.value())
        assertEquals(0L, count("tb_program_session_application"))
        assertEquals(0L, count("tb_standard_program_application"))
    }

    @Test
    fun `split seats retain a seeded onsite route on cancellation and return only that quota`() {
        capacity = 3
        admissionCapacities = "\"preRegistrationCapacity\":1,\"onsiteCapacity\":2"
        val pre = service.execute(command())
        val onsite = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO tb_program_session_application " +
                "(id, expo_id, program_type, program_id, group_id, participant_id, status, " +
                "definition_snapshot, form_id, form_version, questions_snapshot, answers_snapshot, admission_route) " +
                "SELECT ?, expo_id, program_type, program_id, group_id, 2, 'CONFIRMED', " +
                "definition_snapshot, form_id, form_version, questions_snapshot, answers_snapshot, 'ONSITE' " +
                "FROM tb_program_session_application WHERE id = ?",
            onsite,
            pre.applicationId,
        )
        jdbc.update("INSERT INTO tb_standard_program_application (participant_id, standard_program_id) VALUES (2, 1)")
        val definition = mapper.readValue(definition("1", "STANDARD"), ProgramSessionDefinition::class.java)
        val ledger = context.getBean(ProgramSessionApplicationRepository::class.java)
        val before = requireNotNull(ledger.admissionSeats(definition))
        assertEquals(1L, before.onsite.confirmedCount)
        assertEquals(0L, before.unknownConfirmedCount)
        cancel(onsite)
        val after = requireNotNull(ledger.admissionSeats(definition))
        assertEquals(before.preRegistration, after.preRegistration)
        assertEquals(0L, after.onsite.confirmedCount)
        assertEquals(before.onsite.remaining + 1, after.onsite.remaining)
        assertEquals("ONSITE", column("admission_route", onsite))
        assertEquals("PRE_REGISTRATION", column("admission_route", pre.applicationId))
    }

    @Test
    fun `split seats allow current and prepared definition reads while rejecting unrelated revisions`() {
        capacity = 3
        admissionCapacities = "\"preRegistrationCapacity\":1,\"onsiteCapacity\":2"
        service.execute(command())
        val current = mapper.readValue(definition("1", "STANDARD"), ProgramSessionDefinition::class.java)
        change {
            it.put("capacity", 4)
            it.put("onsiteCapacity", 3)
        }
        admissionCapacities = "\"preRegistrationCapacity\":1,\"onsiteCapacity\":3"
        val prepared = mapper.readValue(definition("1", "STANDARD"), ProgramSessionDefinition::class.java)
        val repository = context.getBean(ProgramWaitlistRepository::class.java)
        transactions.execute {
            context.getBean(ProgramSessionApplicationRepository::class.java).lockCapacityReads(listOf(current))
            repository.checkDefinition(current.copy(pendingRevision = prepared.revision))
            repository.checkDefinition(prepared)
            assertThrows(ProgramApplicationConflictException::class.java) {
                repository.checkDefinition(prepared.copy(revision = prepared.revision + 1))
            }
        }
        pending = true
        val error = assertThrows(ResponseStatusException::class.java) { service.execute(command(participant = 2)) }
        assertEquals(409, error.statusCode.value())
    }

    // #172 manual confirmation after the stored Expo #143 policy stops automatic promotion.
    private fun promoteByAdmin(
        id: UUID,
        expected: Int,
        reason: String? = null,
        expo: String = EXPO,
    ) = mvc
        .perform(
            post("/application/admin/expos/$expo/program-session-applications/$id/promotion").header("Authorization", "Bearer admin"),
        ).andExpect(status().`is`(expected))
        .also { result -> reason?.let { result.andExpect(jsonPath("$.code").value(it)) } }

    private fun confirmations(id: UUID): List<Map<String, Any?>> =
        jdbc.queryForList(
            "SELECT actor_type, actor_id, previous_status, created_at FROM tb_program_session_history " +
                "WHERE application_id = ? AND status = 'CONFIRMED'",
            id,
        )

    @Test
    fun `auto promotion off keeps waiters through every automatic entry point and an admin confirms the chosen one once`() {
        capacity = 1
        waitingCapacity = 2
        promotionPolicy = "\"autoPromotionEnabled\":false"
        addParticipant(3)
        val confirmed = service.execute(command())
        val head = service.execute(command(participant = 2))
        val chosen = service.execute(command(participant = 3))
        cancel(confirmed.applicationId)
        reconcile()
        context.getBean(ProgramWaitlistScheduler::class.java).reconcile()
        change { it.put("capacity", 1) }
        reconcile()
        assertEquals("WAITING", status(head.applicationId))
        assertEquals("WAITING", status(chosen.applicationId))
        assertEquals(0L, count("tb_standard_program_application"))

        promoteAccessStatus = 403
        promoteByAdmin(chosen.applicationId, 403)
        promoteAccessStatus = 200
        promoteByAdmin(chosen.applicationId, 404, expo = UUID(9, 9).toString())
        promoteByAdmin(chosen.applicationId, 200).andExpect(jsonPath("$.status").value("CONFIRMED"))
        promoteByAdmin(chosen.applicationId, 200)
        applied(3, true)
        val history = confirmations(chosen.applicationId).single()
        assertEquals("ADMIN", history["actor_type"])
        assertEquals(42L, (history["actor_id"] as Number).toLong())
        assertEquals("WAITING", history["previous_status"])
        assertNotNull(history["created_at"])
        assertEquals(
            true,
            jdbc.queryForObject(
                "SELECT is_promotion FROM tb_program_session_event WHERE application_id = ? AND status = 'CONFIRMED'",
                Boolean::class.java,
                chosen.applicationId,
            ),
        )
        // The last seat is taken; the remaining waiter stays waiting and a cancelled row is not reopened.
        promoteByAdmin(head.applicationId, 409, "CAPACITY_CHANGED")
        assertEquals("WAITING", status(head.applicationId))
        promoteByAdmin(confirmed.applicationId, 409, "NOT_WAITING")
    }

    @Test
    fun `auto promotion end boundary and final manual deadline before at and after`() {
        val autoEnd = Instant.parse("2026-10-12T10:00:00Z")
        val finalEnd = Instant.parse("2026-10-13T10:00:00Z")
        for (offset in listOf(-1000L, 0L)) {
            reset()
            capacity = 1
            waitingCapacity = 2
            promotionEnd = finalEnd
            promotionPolicy = "\"autoPromotionEndedAt\":\"$autoEnd\""
            addParticipant(3)
            val confirmed = service.execute(command())
            val head = service.execute(command(participant = 2))
            val chosen = service.execute(command(participant = 3))
            testClock.current = autoEnd.plusNanos(offset)
            if (offset < 0) {
                promoteByAdmin(chosen.applicationId, 409, "AUTO_PROMOTION_ACTIVE")
                cancel(confirmed.applicationId)
                assertEquals("CONFIRMED", status(head.applicationId))
                assertEquals("SYSTEM", confirmations(head.applicationId).single()["actor_type"])
            } else {
                cancel(confirmed.applicationId)
                assertEquals("WAITING", status(head.applicationId))
                promoteByAdmin(chosen.applicationId, 200)
                assertEquals("WAITING", status(head.applicationId))
            }
        }
        for (offset in listOf(-1000L, 0L, 1000L)) {
            reset()
            capacity = 2
            waitingCapacity = 1
            promotionEnd = finalEnd
            promotionPolicy = "\"autoPromotionEndedAt\":\"$autoEnd\""
            service.execute(command())
            change { it.put("capacity", 1) }
            val waiting = service.execute(command(participant = 2))
            change { it.put("capacity", 2) }
            testClock.current = finalEnd.plusNanos(offset)
            if (offset < 0) {
                promoteByAdmin(waiting.applicationId, 200)
            } else {
                promoteByAdmin(waiting.applicationId, 409, "PROMOTION_ENDED")
                assertEquals("WAITING", status(waiting.applicationId))
            }
        }
        reset()
        waitingCapacity = 1
        promotionPolicy = "\"autoPromotionEnabled\":false"
        service.execute(command())
        val waiting = service.execute(command(participant = 2))
        change { it.put("closed", true) }
        promoteByAdmin(waiting.applicationId, 409, "PROMOTION_ENDED")
        // A default policy keeps automatic promotion and never opens manual confirmation.
        reset()
        waitingCapacity = 1
        service.execute(command())
        promoteByAdmin(service.execute(command(participant = 2)).applicationId, 409, "AUTO_PROMOTION_ACTIVE")
    }

    @Test
    fun `concurrent admin confirmations take the last seat once and repeat requests confirm once`() {
        capacity = 1
        waitingCapacity = 2
        promotionPolicy = "\"autoPromotionEnabled\":false"
        addParticipant(3)
        val confirmed = service.execute(command())
        val first = service.execute(command(participant = 2))
        val second = service.execute(command(participant = 3))
        cancel(confirmed.applicationId)
        val promote = context.getBean(PromoteAdminApplicationService::class.java)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results =
                listOf(first, second, first, second)
                    .map { receipt ->
                        pool.submit<Int> {
                            assertTrue(start.await(10, TimeUnit.SECONDS))
                            try {
                                promote.execute(
                                    PromoteAdminApplicationCommand(UUID.fromString(EXPO), receipt.applicationId, "Bearer admin"),
                                )
                                200
                            } catch (exception: ResponseStatusException) {
                                exception.statusCode.value()
                            }
                        }
                    }.also { start.countDown() }
                    .map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(listOf(200, 200, 409, 409), results.sorted())
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1L, count("tb_standard_program_application"))
        val statuses = listOf(status(first.applicationId), status(second.applicationId))
        assertEquals(listOf("CONFIRMED", "WAITING"), statuses.sortedBy { it })
        assertEquals(1, confirmations(first.applicationId).size + confirmations(second.applicationId).size)
    }

    @Test
    fun `manual confirmation links a training waiter once and a whole guardian batch with the admin actor`() {
        waitingCapacity = 1
        promotionPolicy = "\"autoPromotionEnabled\":false"
        val confirmed = service.execute(command(type = ProgramSessionType.TRAINING))
        val waiting = service.execute(command(participant = 2, type = ProgramSessionType.TRAINING))
        cancel(confirmed.applicationId)
        assertEquals(2, linkCalls.get())
        promoteByAdmin(waiting.applicationId, 200)
        assertEquals(3, linkCalls.get())
        mvc
            .perform(
                get("/internal/program-applications/training/1/trainees/502").header("X-Internal-Token", "test-token"),
            ).andExpect(jsonPath("$.applied").value(true))
        assertEquals(
            1L,
            jdbc.queryForObject("SELECT version FROM tb_training_application_version WHERE trainee_id = 502", Long::class.java),
        )

        reset()
        enableGuardianPolicy(2, 4)
        promotionPolicy = "\"autoPromotionEnabled\":false"
        val receipt = batchService.execute(guardianBatch(1, 2))
        assertTrue(receipt.applications.all { it.application.status == "WAITING" })
        change { it.put("capacity", 3) }
        reconcile()
        assertEquals(0L, count("tb_standard_program_application"))
        promoteByAdmin(
            receipt.applications
                .last()
                .application.applicationId,
            200,
        )
        receipt.applications.forEach {
            applied(it.application.participantId, true)
            assertEquals("ADMIN", confirmations(it.application.applicationId).single()["actor_type"])
        }
        assertEquals(3L, count("tb_standard_program_application"))
    }

    @Test
    fun `offers made before auto promotion ends keep their held seats and no new offer starts after it`() {
        capacity = 1
        waitingCapacity = 2
        val autoEnd = nineKst.plusSeconds(3600)
        promotionPolicy = "\"autoPromotionEndedAt\":\"$autoEnd\""
        addParticipant(3)
        val confirmed = service.execute(command())
        val head = service.execute(command(participant = 2))
        val next = service.execute(command(participant = 3))
        testClock.current = nineKst
        cancel(confirmed.applicationId)
        val offer = activeOffer()
        assertEquals(head.applicationId, offer["application_id"])
        testClock.current = autoEnd
        reconcile()
        assertEquals(offer["id"], activeOffer()["id"])
        promoteByAdmin(head.applicationId, 409, "OFFER_ACTIVE")
        promoteByAdmin(next.applicationId, 409, "CAPACITY_CHANGED")
        deliver(offer, "ACCEPTED", autoEnd)
        respond(offer["id"], "decline", 200)
        assertEquals("CANCELLED", status(head.applicationId))
        reconcile()
        assertEquals(0, offerRows().count { it["state"] in PromotionOfferRepository.ACTIVE_STATES })
        promoteByAdmin(next.applicationId, 200)
    }

    @Test
    fun `policy support headers and change approval accept only Expo shaped promotion settings`() {
        mvc
            .perform(get("/internal/expos/$EXPO/program-policy-capabilities").header("X-Internal-Token", "test-token"))
            .andExpect(status().isNoContent)
            .andExpect(header().string("X-Program-Promotion-Policy-Version", "1"))
            .andExpect(header().string("X-Program-Guardian-Policy-Version", "1"))
        waitingCapacity = 1
        service.execute(command())
        val previous = mapper.readTree(definition("1", "STANDARD"))
        for ((fields, expected) in listOf(
            """{"autoPromotionEnabled":false,"autoPromotionEndedAt":"2030-01-01T00:00:00Z"}""" to 400,
            """{"autoPromotionEndedAt":"2019-12-31T23:59:59Z"}""" to 400,
            """{"autoPromotionEndedAt":"2100-01-01T00:00:00.000001Z"}""" to 400,
            """{"autoPromotionEndedAt":"2030-01-01T00:00:00.0000001Z"}""" to 400,
            """{"autoPromotionEnabled":false,"waitingCapacity":0}""" to 400,
            """{"autoPromotionEndedAt":"2030-01-01T00:00:00Z"}""" to 204,
        )) {
            val next = previous.deepCopy().asObject()
            next.put("revision", revision + 1)
            mapper.readTree(fields).properties().forEach { (name, value) -> next.path("policy").asObject().set(name, value) }
            val result =
                mvc
                    .perform(
                        put("/internal/expos/$EXPO/program-changes/${UUID.randomUUID()}")
                            .header("X-Internal-Token", "test-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"mutations":[{"previous":$previous,"definition":$next}]}"""),
                    ).andExpect(status().`is`(expected))
            if (expected == 204) result.andExpect(header().string("X-Program-Promotion-Policy-Version", "1"))
        }
    }

    // #144 participation offers. Program 1 starts 2030-01-01 11:00 KST, so offers start 2029-12-31 00:00 KST.
    private val offerSwitch = Instant.parse("2029-12-30T15:00:00Z")
    private val nineKst = Instant.parse("2029-12-31T00:00:00Z")

    private fun offerRows(): List<Map<String, Any?>> = jdbc.queryForList("SELECT * FROM tb_promotion_offer ORDER BY created_at, id")

    private fun activeOffer(): Map<String, Any?> =
        jdbc.queryForMap("SELECT * FROM tb_promotion_offer WHERE state IN ('SCHEDULED', 'PUBLISHED', 'SENT', 'UNKNOWN', 'DELIVERY_FAILED')")

    private fun status(id: UUID) =
        jdbc.queryForObject("SELECT status FROM tb_program_session_application WHERE id = ?", String::class.java, id)

    private fun deliver(
        offer: Map<String, Any?>,
        status: String,
        acceptedAt: Instant? = null,
        attemptedAt: Instant? = acceptedAt?.minusSeconds(1),
        reason: String? = null,
        expected: Int = 200,
    ) = mvc
        .perform(
            post("/internal/promotion-offers/${offer["id"]}/deliveries")
                .header("X-Internal-Token", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "eventId" to offer["event_id"].toString(),
                            "status" to status,
                            "attemptId" to UUID(1, 1).toString(),
                            "attemptedAt" to attemptedAt?.toString(),
                            "providerAcceptedAt" to acceptedAt?.toString(),
                            "reason" to reason,
                        ),
                    ),
                ),
        ).andExpect(status().`is`(expected))

    private fun respond(
        offerId: Any?,
        action: String,
        expected: Int,
        withCsrf: Boolean = true,
    ) = mvc
        .perform(
            post("/application/expos/$EXPO/my-applications/promotion-offers/$offerId/$action")
                .let { if (withCsrf) it.with(csrf()) else it }
                .cookie(Cookie("PARTICIPANT_SESSION", "session")),
        ).andExpect(status().`is`(expected))

    private fun check(offer: Map<String, Any?>) =
        mvc
            .perform(
                post("/internal/promotion-offers/check")
                    .header("X-Internal-Token", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":1,"eventId":"${offer["event_id"]}","offerId":"${offer["id"]}","expoId":"$EXPO"}"""),
            ).andExpect(status().isOk)

    @Test
    fun `offer switch at 00 KST the day before keeps earlier automatic promotion and then only holds seats`() {
        waitingCapacity = 2
        addParticipant(3)
        addParticipant(4)
        val confirmed = service.execute(command())
        val first = service.execute(command(participant = 2))
        testClock.current = offerSwitch.minusNanos(1000)
        cancel(confirmed.applicationId)
        applied(2, true)
        assertEquals(0L, count("tb_promotion_offer"))
        val second = service.execute(command(participant = 3))
        testClock.current = offerSwitch
        cancel(first.applicationId)
        // Sending an offer never confirms: the waiting row stays WAITING without a legacy confirmed row.
        applied(3, false)
        assertEquals("WAITING", status(second.applicationId))
        val offer = activeOffer()
        assertEquals("SCHEDULED", offer["state"])
        assertEquals(1, offer["seats"])
        assertEquals(second.applicationId, offer["application_id"])
        assertEquals(nineKst, (offer["send_after"] as java.sql.Timestamp).toInstant())
        // The held seat is counted: a new applicant waits instead of taking it, and reconciling twice holds once.
        assertEquals("WAITING", service.execute(command(participant = 4)).status)
        reconcile()
        assertEquals(1L, count("tb_promotion_offer"))
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM tb_program_session_event WHERE status = 'CONFIRMED'", Long::class.java))
    }

    @Test
    fun `a night vacancy is offered only when the next 09 KST is still before the existing cutoff`() {
        waitingCapacity = 1
        val confirmed = service.execute(command())
        service.execute(command(participant = 2))
        change { it.put("promotionEndedAt", nineKst.toString()) }
        testClock.current = offerSwitch
        cancel(confirmed.applicationId)
        assertEquals(0L, count("tb_promotion_offer"))
        applied(2, false)
        change { it.put("promotionEndedAt", nineKst.plusNanos(1000).toString()) }
        reconcile()
        assertEquals(nineKst.plusNanos(1000), (activeOffer()["hard_deadline"] as java.sql.Timestamp).toInstant())
        applied(2, false)
    }

    @Test
    fun `only the authenticated owner accepts after real provider acceptance and repeated acceptance confirms once`() {
        waitingCapacity = 1
        val confirmed = service.execute(command())
        val waiting = service.execute(command(participant = 2))
        testClock.current = offerSwitch
        cancel(confirmed.applicationId)
        val offer = activeOffer()
        testClock.current = nineKst.plusSeconds(1800)
        check(offer)
            .andExpect(jsonPath("$.eligible").value(true))
            .andExpect(jsonPath("$.representativePhoneNumber").value("01012345678"))
            .andExpect(jsonPath("$.recipientName").value("Person1"))
            .andExpect(jsonPath("$.title").value("Session"))
            .andExpect(jsonPath("$.responseUrl").value("https://client.example/application/forms/$EXPO"))
            .andExpect(jsonPath("$.responseHours").value(6))
            .andExpect(jsonPath("$.sendWindowEndsAt").value("2029-12-31T09:00:00Z"))
        // Before a provider acceptance there is no deadline, so nothing can be accepted yet.
        respond(offer["id"], "acceptance", 409).andExpect(jsonPath("$.code").value("OFFER_NOT_RESPONDABLE"))
        val acceptedAt = nineKst.plusSeconds(1805)
        deliver(offer, "ACCEPTED", acceptedAt).andExpect(jsonPath("$.state").value("SENT"))
        assertEquals(acceptedAt.plusSeconds(6 * 3600), (activeOffer()["expires_at"] as java.sql.Timestamp).toInstant())
        deliver(offer, "ACCEPTED", acceptedAt)
        deliver(offer, "ACCEPTED", acceptedAt.plusSeconds(1), expected = 409)
        respond(offer["id"], "acceptance", 403, withCsrf = false)
        managedIds = listOf(1, 3)
        respond(offer["id"], "acceptance", 404)
        applied(2, false)
        managedIds = listOf(1, 2)
        respond(offer["id"], "acceptance", 200).andExpect(jsonPath("$.status").value("ACCEPTED"))
        respond(offer["id"], "acceptance", 200).andExpect(jsonPath("$.status").value("ACCEPTED"))
        applied(2, true)
        assertEquals("CONFIRMED", status(waiting.applicationId))
        assertEquals(1L, count("tb_standard_program_application"))
        assertEquals(
            listOf("USER:1"),
            jdbc.query(
                "SELECT actor_type, actor_participant_id FROM tb_program_session_history WHERE application_id = ? AND status = 'CONFIRMED'",
                { rs, _ -> "${rs.getString(1)}:${rs.getLong(2)}" },
                waiting.applicationId,
            ),
        )
        respond(offer["id"], "decline", 409).andExpect(jsonPath("$.code").value("OFFER_ACCEPTED"))
        mvc
            .perform(get("/application/expos/$EXPO/my-applications/promotion-offers").cookie(Cookie("PARTICIPANT_SESSION", "session")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].status").value("ACCEPTED"))
            .andExpect(jsonPath("$[0].respondable").value(false))
    }

    @Test
    fun `decline expiry and expired delivery failure end the wait for the next FIFO party`() {
        waitingCapacity = 3
        listOf(3L, 4L).forEach(::addParticipant)
        managedIds = listOf(1, 2, 3, 4)
        val cutoff = Instant.parse("2030-01-01T00:00:00Z")
        promotionEnd = cutoff
        val confirmed = service.execute(command())
        val waiting = (2L..4L).map { service.execute(command(participant = it)) }
        testClock.current = offerSwitch
        cancel(confirmed.applicationId)
        testClock.current = nineKst.plusSeconds(1800)
        deliver(activeOffer(), "ACCEPTED", testClock.current)
        respond(activeOffer()["id"], "decline", 200).andExpect(jsonPath("$.status").value("DECLINED"))
        respond(offerRows().first()["id"], "decline", 200).andExpect(jsonPath("$.status").value("DECLINED"))
        assertEquals("CANCELLED", status(waiting[0].applicationId))
        assertEquals(waiting[1].applicationId, activeOffer()["application_id"])
        val acceptedAt = testClock.current.plusSeconds(60)
        deliver(activeOffer(), "ACCEPTED", acceptedAt)
        val scheduler = context.getBean(team.startup.application.domain.application.service.impl.PromotionOfferScheduler::class.java)
        testClock.current = acceptedAt.plusSeconds(6 * 3600).minusNanos(1000)
        scheduler.sweep()
        assertEquals("WAITING", status(waiting[1].applicationId))
        testClock.current = acceptedAt.plusSeconds(6 * 3600)
        scheduler.sweep()
        assertEquals("CANCELLED", status(waiting[1].applicationId))
        assertEquals(
            listOf("DECLINED:USER", "EXPIRED:SYSTEM", "SCHEDULED:null"),
            offerRows().map { "${it["state"]}:${it["responder_type"]}" },
        )
        assertEquals(
            listOf("USER:1", "SYSTEM:0"),
            listOf(waiting[0], waiting[1]).map { entry ->
                jdbc.queryForObject(
                    "SELECT actor_type || ':' || coalesce(actor_participant_id, 0) FROM tb_program_session_history " +
                        "WHERE application_id = ? AND status = 'CANCELLED'",
                    String::class.java,
                    entry.applicationId,
                )
            },
        )
        // A definitive failure before provider acceptance gets report time + 6 hours, then expires like no response (#226).
        val failedAt = testClock.current
        deliver(activeOffer(), "FAILED", reason = "PROVIDER_CONFIRMED_FAILURE")
        assertEquals(failedAt.plusSeconds(6 * 3600), (activeOffer()["expires_at"] as java.sql.Timestamp).toInstant())
        testClock.current = failedAt.plusSeconds(6 * 3600).minusNanos(1000)
        scheduler.sweep()
        assertEquals("DELIVERY_FAILED", activeOffer()["state"])
        assertEquals("WAITING", status(waiting[2].applicationId))
        testClock.current = failedAt.plusSeconds(6 * 3600)
        scheduler.sweep()
        assertEquals("EXPIRED:SYSTEM", offerRows().last().let { "${it["state"]}:${it["responder_type"]}" })
        assertEquals("CANCELLED", status(waiting[2].applicationId))
        applied(4, false)
    }

    @Test
    fun `check and delivery reports respect the send window stale events and the hard cutoff`() {
        waitingCapacity = 1
        val confirmed = service.execute(command())
        service.execute(command(participant = 2))
        testClock.current = offerSwitch
        cancel(confirmed.applicationId)
        val night = activeOffer()
        check(night).andExpect(jsonPath("$.eligible").value(false)).andExpect(jsonPath("$.reason").value("OUTSIDE_SEND_WINDOW"))
        deliver(night, "SKIPPED", reason = "OUTSIDE_SEND_WINDOW").andExpect(jsonPath("$.state").value("SCHEDULED"))
        val next = activeOffer()
        assertTrue(next["event_id"] != night["event_id"])
        check(night).andExpect(jsonPath("$.reason").value("NOT_CURRENT_OFFER"))
        deliver(night, "UNKNOWN", expected = 409)
        testClock.current = nineKst
        check(next).andExpect(jsonPath("$.eligible").value(true))
        testClock.current = Instant.parse("2029-12-31T09:00:00Z")
        check(next).andExpect(jsonPath("$.reason").value("OUTSIDE_SEND_WINDOW"))
        testClock.current = nineKst.plusSeconds(60)
        deliver(next, "ACCEPTED", testClock.current, attemptedAt = testClock.current.plusSeconds(1), expected = 400)
        deliver(next, "UNKNOWN").andExpect(jsonPath("$.state").value("UNKNOWN"))
        respond(next["id"], "acceptance", 409)
        val scheduler = context.getBean(team.startup.application.domain.application.service.impl.PromotionOfferScheduler::class.java)
        testClock.current = nineKst.plusSeconds(8 * 3600)
        scheduler.sweep()
        assertEquals("UNKNOWN", activeOffer()["state"])
        applied(2, false)
    }

    @Test
    fun `provider acceptance at the cutoff leaves no response interval and reconfiguration keeps held seats`() {
        capacity = 2
        waitingCapacity = 1
        addParticipant(3)
        managedIds = listOf(1, 2, 3)
        val confirmed = service.execute(command())
        service.execute(command(participant = 2))
        service.execute(command(participant = 3))
        testClock.current = offerSwitch
        cancel(confirmed.applicationId)
        val previous = mapper.readTree(definition("1", "STANDARD"))
        val next = previous.deepCopy().asObject()
        next.put("revision", revision + 1)
        next.path("policy").asObject().put("capacity", 1)
        mvc
            .perform(
                put("/internal/expos/$EXPO/program-changes/${UUID.randomUUID()}")
                    .header("X-Internal-Token", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"mutations":[{"previous":$previous,"definition":$next}]}"""),
            ).andExpect(status().isConflict)
        // A later, earlier cutoff from Expo caps the existing offer too; it is never extended.
        change { it.put("promotionEndedAt", nineKst.plusSeconds(3600).toString()) }
        reconcile()
        assertEquals(nineKst.plusSeconds(3600), (activeOffer()["hard_deadline"] as java.sql.Timestamp).toInstant())
        testClock.current = nineKst.plusSeconds(3600)
        deliver(activeOffer(), "ACCEPTED", testClock.current).andExpect(jsonPath("$.state").value("VOIDED"))
        applied(3, false)
    }

    @Test
    fun `a guardian batch offer holds every new member and acceptance confirms the whole batch for the representative`() {
        enableGuardianPolicy(2, 4)
        val receipt = batchService.execute(guardianBatch(1, 2))
        assertTrue(receipt.applications.all { it.application.status == "WAITING" })
        testClock.current = offerSwitch
        change { it.put("capacity", 3) }
        reconcile()
        val offer = activeOffer()
        assertEquals(3, offer["seats"])
        assertTrue(offer["batch_id"] != null)
        assertEquals(0L, count("tb_standard_program_application"))
        testClock.current = nineKst.plusSeconds(60)
        deliver(offer, "ACCEPTED", testClock.current)
        respond(offer["id"], "acceptance", 200).andExpect(jsonPath("$.status").value("ACCEPTED"))
        receipt.applications.forEach { applied(it.application.participantId, true) }
        assertEquals(3L, count("tb_standard_program_application"))
        assertEquals(
            3L,
            jdbc.queryForObject(
                "SELECT count(*) FROM tb_program_session_history WHERE status = 'CONFIRMED' AND actor_type = 'USER' AND actor_participant_id = 1",
                Long::class.java,
            ),
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

    // 소유자 취소 경로로 취소한다. 세션 `owner-cancel-{참가자}`는 현재 관리 일행에 대상 참가자를 더해 돌려준다.
    private fun cancel(id: UUID) {
        val target =
            jdbc.queryForMap("SELECT participant_id, program_type FROM tb_program_session_application WHERE id = ?", id)
        context.getBean(CancelOwnerApplicationService::class.java).execute(
            CancelOwnerApplicationCommand(
                UUID.fromString(EXPO),
                "owner-cancel-${target["participant_id"]}",
                OwnerApplicationType.valueOf(target["program_type"] as String),
                id.toString(),
            ),
        )
    }

    private fun cancelStatus(id: UUID): Int =
        try {
            cancel(id)
            204
        } catch (exception: ResponseStatusException) {
            exception.statusCode.value()
        } catch (_: ProgramApplicationConflictException) {
            409
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
        fun guardianEligibility(): StudentGuardianEligibilityProvider =
            object : StudentGuardianEligibilityProvider {
                override fun validate(query: StudentGuardianEligibilityQuery) {
                    if (!guardianPolicy) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
                    if (query.links.any {
                            it.studentId !in
                                setOf(
                                    1L,
                                    2L,
                                    4L,
                                    5L,
                                ) || it.guardianId != 3L
                        }
                    ) {
                        throw ResponseStatusException(HttpStatus.FORBIDDEN)
                    }
                }

                override fun validateSingle(
                    command: ApplyProgramSessionCommand,
                    definition: team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition,
                    occupation: String?,
                ) {
                    if (guardianPolicy) throw ProgramApplicationConflictException("동반 정책은 배치 접수가 필요합니다.")
                }
            }

        @Bean
        @Primary
        fun guardianCancellationGuard(): OwnerCancellationGuard =
            object : OwnerCancellationGuard {
                override fun acquire(
                    command: CancelOwnerApplicationCommand,
                    participantId: Long,
                    party: OwnerApplicationParty,
                ): OwnerCancellationGuard.Permit {
                    if (party.participants.none { it.participantId == participantId }) throw ResponseStatusException(HttpStatus.FORBIDDEN)
                    return permit(participantId)
                }

                override fun acquireAdmin(
                    command: CancelOwnerApplicationCommand,
                    participantId: Long,
                    sessionId: Long,
                ): OwnerCancellationGuard.Permit = permit(participantId)

                override fun acquireAdminProgram(
                    command: CancelOwnerApplicationCommand,
                    participantId: Long,
                    programId: Long,
                ): OwnerCancellationGuard.Permit = permit(participantId)

                private fun permit(participantId: Long): OwnerCancellationGuard.Permit {
                    if (participantId in protectedParticipants) throw ResponseStatusException(HttpStatus.CONFLICT)
                    return object : OwnerCancellationGuard.Permit {
                        // User 연결 결과와 같게 legacy 귀속 참가자는 700, 나머지는 참가자 + 500으로 연결한다.
                        override val linkedTraineeId = if (attributionParticipant == participantId) 700 else participantId + 500
                        override val cancelEndedAt = cancelEnd

                        override fun close() {}
                    }
                }
            }

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
                    if (TransactionSynchronizationManager.isActualTransactionActive()) formCallsInTransaction.incrementAndGet()
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
        private const val OWNER_CANCEL_PHONE = "0107000"
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
        private val formCallsInTransaction = AtomicInteger()
        private val testClock =
            object : Clock() {
                @Volatile var current: Instant = Instant.parse("2026-10-11T10:00:00Z")

                override fun instant(): Instant = current

                override fun getZone(): ZoneId = ZoneOffset.UTC

                override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
            }

        @Volatile private var capacity = 1

        @Volatile private var waitingCapacity = 0

        @Volatile private var windows: Map<Pair<String, Long>, Pair<String, String>> = emptyMap()

        @Volatile private var revision = 1L

        @Volatile private var applicationEnd = Instant.parse("2100-01-01T00:00:00Z")

        @Volatile private var promotionEnd = applicationEnd

        @Volatile private var cancelEnd = applicationEnd

        @Volatile private var formEnd = applicationEnd

        @Volatile private var linkFailure = false

        @Volatile private var linkRejections = mapOf<Long, Int>()

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

        @Volatile private var managedIds = listOf<Long>(1, 2)

        @Volatile private var fieldIds = setOf<Long>()

        @Volatile private var guardianPolicy = false

        @Volatile private var protectedParticipants = emptySet<Long>()

        @Volatile private var allowed = "[\"TEACHER\"]"

        @Volatile private var channels: String? = null

        @Volatile private var closed = false

        @Volatile private var expired = false

        @Volatile private var badExpo = false

        @Volatile private var pending = false

        @Volatile private var formMode = "valid"

        @Volatile private var formQuestions = QUESTIONS

        @Volatile private var realFields = REAL_FIELDS

        @Volatile private var groups = mapOf<String, String>()

        private val attributionCalls = ConcurrentLinkedQueue<String>()

        @Volatile private var attributionStatus = 200

        @Volatile private var attributionMalformed = false

        @Volatile private var attributionParticipant: Long? = null

        @Volatile private var attributionAfterUser: (() -> Unit)? = null

        @Volatile private var attributionAccessStatus = 200

        @Volatile private var attributionActor = 42L

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

                            path.endsWith("/admin-application-promote-access") -> {
                                status = promoteAccessStatus
                                val owner = path.substringAfter("/internal/expo/").substringBefore('/')
                                """{"expoId":"$owner","adminId":$attributionActor,"permission":"APPLICATION_PROMOTE","scope":"EXPO_ADMIN","policyVersion":"EXPO_ADMIN_APPLICATION_PROMOTE_V1"}"""
                            }

                            path.endsWith("/admin-application-cancel-access") || path.endsWith("/admin-access") -> {
                                status = attributionAccessStatus
                                val owner = path.substringAfter("/internal/expo/").substringBefore('/')
                                val cancel = path.endsWith("/admin-application-cancel-access")
                                """{"expoId":"$owner","adminId":$attributionActor,"permission":"${if (cancel) "APPLICATION_CANCEL" else "APPLICATION_READ"}","scope":"${if (cancel) "EXPO_ADMIN" else "GLOBAL_ADMIN"}","policyVersion":"${if (cancel) "EXPO_ADMIN_APPLICATION_CANCEL_V1" else "GLOBAL_ADMIN_APPLICATION_READ_V1"}"}"""
                            }

                            path.startsWith("/internal/trainees/participant-link") -> {
                                attributionCalls.add(body)
                                status = attributionStatus
                                val request = ObjectMapper().readTree(body)
                                val participant = request.path("participantId").asLong()
                                val revoke = path.endsWith("/revoke")
                                val changed = if (revoke) attributionParticipant != null else attributionParticipant != participant
                                if (status == 200) {
                                    attributionParticipant = if (revoke) null else participant
                                    attributionAfterUser?.invoke()
                                }
                                if (attributionMalformed) {
                                    "{"
                                } else {
                                    """{"traineeId":700,"participantId":$attributionParticipant,"changed":$changed}"""
                                }
                            }

                            path == "/internal/forms/by-id/$FORM" -> {
                                """{"id":"$FORM","expoId":"$EXPO","purpose":"PROGRAM","version":1,"participantType":null,""" +
                                    """"startDate":"2020-01-01T00:00:00Z","endDate":"2100-01-01T00:00:00Z","dynamicForm":$realFields}"""
                            }

                            path.endsWith("participant-sessions/resolve") -> {
                                val ownerCancel =
                                    Regex("owner-cancel-([0-9]+)")
                                        .find(body)
                                        ?.groupValues
                                        ?.get(1)
                                        ?.toLong()
                                if (ownerCancel != null) {
                                    """{"expoId":"$EXPO","phoneNumber":"${OWNER_CANCEL_PHONE}${"%04d".format(
                                        ownerCancel,
                                    )}","expiresAt":"2100-01-01T00:00:00"}"""
                                } else if (body.contains("expired")) {
                                    status = 401
                                    """{"code":"SESSION_EXPIRED"}"""
                                } else {
                                    sessionResponse
                                        ?: """{"expoId":"$EXPO","phoneNumber":"01012345678","expiresAt":"2100-01-01T00:00:00"}"""
                                }
                            }

                            path.endsWith("standard-participants/managed") -> {
                                val ownerCancel =
                                    Regex("\"$OWNER_CANCEL_PHONE([0-9]{4})\"")
                                        .find(body)
                                        ?.groupValues
                                        ?.get(1)
                                        ?.toLong()
                                val people =
                                    (
                                        ownerCancel?.let {
                                            if (it in
                                                managedIds
                                            ) {
                                                managedIds
                                            } else {
                                                managedIds.take(4) + it
                                            }
                                        } ?: managedIds
                                    ).joinToString(",") { id ->
                                        """{"participantId":$id,"name":"Person$id","occupation":"${occupations[id] ?: occupation}","representative":${id == 1L},"applicationType":"${if (id in fieldIds) "FIELD" else "PRE"}"}"""
                                    }
                                """{"registered":true,"participants":[$people]}"""
                            }

                            path.endsWith("standard-participants/details") -> {
                                val id =
                                    ObjectMapper()
                                        .readTree(body)
                                        .path("participantIds")
                                        .get(0)
                                        .asLong()
                                val representative = if (id == 1L) "null" else "1"
                                val phone = if (id == 1L) "\"010-1234-5678\"" else "null"
                                """[{"participantId":$id,"name":"Person$id","representativeId":$representative,"phoneNumber":$phone}]"""
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
                                    """[{"participantId":$id,"name":"Person","occupation":${occupationResponse ?: "\"${occupations[id] ?: occupation}\""},"representative":true,"applicationType":"${if (id in fieldIds) "FIELD" else "PRE"}"}]"""
                                }
                            }

                            path.endsWith("resolve-or-create-by-participant") -> {
                                linkCalls.incrementAndGet()
                                linkRequests.add(body)
                                if (linkFailure) status = 503
                                val participantId = ObjectMapper().readTree(body).path("participantId").asLong()
                                linkRejections[participantId]?.let { status = it }
                                if (delayedLinkParticipant == null || delayedLinkParticipant == participantId) {
                                    linkCompletedAt?.let { testClock.current = it }
                                }
                                val id = if (attributionParticipant == participantId) 700 else participantId + 500
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

        @Volatile private var places: Map<String, String?> = emptyMap()

        @Volatile private var admissionCapacities: String? = null

        @Volatile private var promotionPolicy: String? = null

        @Volatile private var promoteAccessStatus = 200

        private fun definition(
            id: String,
            type: String,
        ): String {
            val response =
                """{"expoId":"${if (badExpo) "other" else EXPO}","programType":"$type","id":$id,"title":"$title","groupId":"${groups[id] ?: GROUP}","revision":$revision,"pendingRevision":${if (pending) 2 else "null"},"policy":{"formId":"$FORM","allowedOccupations":$allowed,"capacity":$capacity,"waitingCapacity":$waitingCapacity,"applicationStartedAt":"2020-01-01T00:00:00Z","applicationEndedAt":"${if (expired) "2021-01-01T00:00:00Z" else applicationEnd}","cancelEndedAt":"$cancelEnd","promotionEndedAt":"$promotionEnd","closed":$closed${channels?.let {
                    ",\"channels\":$it"
                } ?: ""}}}"""
            val json = ObjectMapper().readTree(response).asObject()
            listOfNotNull(admissionCapacities, promotionPolicy).forEach { fields ->
                val policy = json.path("policy").asObject()
                ObjectMapper().readTree("{$fields}").properties().forEach { (name, value) -> policy.set(name, value) }
            }
            val start = Instant.parse("2030-01-01T00:00:00Z").plusSeconds(id.toLong() * 7200 + if (type == "TRAINING") 86400 else 0)
            val formatter =
                if (type ==
                    "TRAINING"
                ) {
                    java.time.format.DateTimeFormatter
                        .ofPattern("yyyy-MM-dd HH:mm")
                } else {
                    java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
                }
            val localStart = start.atZone(java.time.ZoneId.of("Asia/Seoul"))
            val window = windows[type to id.toLong()] ?: (localStart.format(formatter) to localStart.plusHours(1).format(formatter))
            json.put("startedAt", window.first)
            json.put("endedAt", window.second)
            if (noPolicyProgram == id.toLong() || noPolicyProgram == 0L) {
                json.putNull("policy")
                return json.toString()
            }
            if (places.containsKey(id)) {
                json.put("place", places[id])
            }
            return json.toString()
        }

        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")

        @DynamicPropertySource @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("application.legacy-trainee-attribution.enabled") { true }
            registry.add("application.program-sessions.enabled") { true }
            registry.add("application.program-sessions.reconcile-delay") { 3600000 }
            registry.add("application.promotion-offers.enabled") { true }
            registry.add("application.promotion-offers.bootstrap-servers") { "127.0.0.1:9" }
            registry.add("application.promotion-offers.response-url") { "https://client.example/application/forms/{expoId}" }
            registry.add("application.promotion-offers.delay-ms") { 3600000 }
            registry.add("application.registration.expo-internal-token") { "test-token" }
            registry.add("application.registration.user-internal-token") { "test-token" }
            registry.add("application.registration.form-internal-token") { "test-token" }
            registry.add("application.registration.form-url") { "http://127.0.0.1:${server.address.port}" }
            registry.add("application.registration.expo-url") { "http://127.0.0.1:${server.address.port}" }
            registry.add("application.registration.user-url") { "http://127.0.0.1:${server.address.port}" }
        }
    }
}

private const val ONSITE_OPEN = ",\"onsiteStartedAt\":\"2020-01-01T00:00:00Z\",\"onsiteEndedAt\":\"2100-01-01T00:00:00Z\""
