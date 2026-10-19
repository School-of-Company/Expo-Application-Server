package team.startup.application

import com.sun.net.httpserver.HttpServer
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.presentation.dto.ApplyPreregisterSessionCommand
import team.startup.application.domain.application.presentation.dto.ApplyProgramSessionCommand
import team.startup.application.domain.application.presentation.dto.CancelOwnerApplicationCommand
import team.startup.application.domain.application.presentation.dto.GetOwnerApplicationsCommand
import team.startup.application.domain.application.presentation.dto.OwnerApplicationParty
import team.startup.application.domain.application.presentation.dto.OwnerApplicationType
import team.startup.application.domain.application.presentation.dto.PreregisterSessionDefinition
import team.startup.application.domain.application.presentation.dto.ProgramFormSnapshot
import team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition
import team.startup.application.domain.application.presentation.dto.ProgramSessionPolicy
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.repository.OwnerApplicationRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.StudentGuardianBatchRepository
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import team.startup.application.domain.application.service.ApplyPreregisterSessionService
import team.startup.application.domain.application.service.ApplyProgramSessionService
import team.startup.application.domain.application.service.CancelOwnerApplicationService
import team.startup.application.domain.application.service.CancelProgramSessionService
import team.startup.application.domain.application.service.CancelStudentGuardianApplicationService
import team.startup.application.domain.application.service.GetOwnerApplicationsService
import team.startup.application.domain.application.service.OwnerCancellationGuard
import team.startup.application.domain.application.service.ProgramFormProvider
import team.startup.application.domain.application.service.impl.CancelOwnerApplicationServiceImpl
import team.startup.application.domain.application.service.impl.PreregisterCancellation
import team.startup.application.domain.application.service.impl.PreregisterSessionLedger
import team.startup.application.domain.application.service.impl.PreregisterWaitlist
import team.startup.application.domain.application.service.impl.RegistrationGateway
import team.startup.application.domain.application.service.impl.SessionAttendanceOutbox
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(properties = ["eureka.client.enabled=false"])
@AutoConfigureMockMvc
@Testcontainers
@Import(OwnerApplicationTests.Providers::class)
class OwnerApplicationTests {
    @Autowired private lateinit var query: GetOwnerApplicationsService

    @Autowired private lateinit var cancel: CancelOwnerApplicationService

    @Autowired private lateinit var applyProgram: ApplyProgramSessionService

    @Autowired private lateinit var applyPreregister: ApplyPreregisterSessionService

    @Autowired private lateinit var preregister: PreregisterSessionRepository

    @Autowired private lateinit var programs: ProgramSessionApplicationRepository

    @Autowired private lateinit var transactions: TransactionTemplate

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var context: ApplicationContext

    @Autowired private lateinit var mvc: MockMvc

    @BeforeEach
    fun reset() {
        jdbc.execute(
            "TRUNCATE tb_program_session_event, tb_program_session_history, tb_program_session_receipt, " +
                "tb_program_session_application, tb_program_session_state, tb_program_change_receipt, " +
                "tb_standard_program_application, tb_training_program_application, tb_training_application_version, " +
                "tb_training_operation_receipt, tb_preregister_application, tb_preregister_transition, tb_preregister_request, " +
                "tb_preregister_reservation, tb_preregister_session_state, tb_preregister_session_change, " +
                "tb_preregister_deleted_expo, tb_session_attendance_outbox, tb_owner_cancellation_delivery RESTART IDENTITY CASCADE",
        )
        transactions.executeWithoutResult {
            preregister.saveDefinition(session)
            preregister.insert(EXPO, 1, 1, 1, "CONFIRMED")
            preregister.insert(EXPO, 1, 1, 2, "CONFIRMED")
            preregister.insert(EXPO, 1, 99, 99, "CONFIRMED")
        }
        sessionStatus = 200
        sessionBody = null
        partyBody = null
        guardStatus = null
        deadline = END
        traineeId = null
        closedPermits.set(0)
        adminGuards.clear()
        currentDefinition = definition
    }

    @Test
    fun `only managed participants and owner snapshots are returned without session phone or QR`() {
        val own = applyProgram.execute(programCommand())
        val other = applyProgram.execute(programCommand(99).copy(sessionId = "other"))
        val response = query.execute(GetOwnerApplicationsCommand(EXPO, "session"))
        val outsider = query.execute(GetOwnerApplicationsCommand(EXPO, "other"))
        assertEquals(listOf(99L), outsider.participants.map { it.participantId })
        assertEquals(setOf("3", other.applicationId.toString()), outsider.applications.map { it.applicationId }.toSet())
        failure(HttpStatus.NOT_FOUND) {
            cancel.execute(cancelCommand(own.applicationId.toString(), OwnerApplicationType.STANDARD).copy(sessionId = "other"))
        }
        failure(HttpStatus.NOT_FOUND) { cancel.execute(cancelCommand("1").copy(sessionId = "other")) }
        assertEquals(listOf(1L, 2L), response.participants.map { it.participantId })
        assertEquals(setOf("1", "2", own.applicationId.toString()), response.applications.map { it.applicationId }.toSet())
        assertFalse(response.applications.any { it.applicationId == other.applicationId.toString() })
        val program = response.applications.single { it.type == OwnerApplicationType.STANDARD }
        assertEquals(mapper.readTree("""{"101":"yes"}"""), program.answers)
        assertEquals(listOf("CONFIRMED"), program.history.map { it.status })
        assertTrue(program.applied)
        assertFalse(program.cancellable)
        assertEquals("EXTERNAL_CONTRACT_REQUIRED", program.cancellationBlockedReason)
        val json = mapper.writeValueAsString(response)
        assertFalse(json.contains("phoneNumber") || json.contains("sessionId") || json.contains("code"))
        failure(HttpStatus.NOT_FOUND) { cancel.execute(cancelCommand("3")) }
        failure(HttpStatus.NOT_FOUND) { cancel.execute(cancelCommand(other.applicationId.toString(), OwnerApplicationType.STANDARD)) }
        failure(HttpStatus.NOT_FOUND) { cancel.execute(cancelCommand("9999")) }
        for (id in listOf("0", "-1", "invalid", "9223372036854775808")) {
            failure(HttpStatus.NOT_FOUND) { cancel.execute(cancelCommand(id)) }
        }
        failure(HttpStatus.NOT_FOUND) { cancel.execute(cancelCommand("invalid", OwnerApplicationType.STANDARD)) }
        failure(HttpStatus.NOT_FOUND) { cancel.execute(cancelCommand(own.applicationId.toString(), OwnerApplicationType.TRAINING)) }
        assertEquals(0, closedPermits.get())
    }

    @Test
    fun `owner venue is current while application venue snapshot stays immutable and legacy missing stays absent`() {
        currentDefinition = definition.copy(place = "Room A")
        val receipt = applyProgram.execute(programCommand())
        currentDefinition = definition.copy(revision = 2, place = "Room B")

        fun item() =
            query.execute(GetOwnerApplicationsCommand(EXPO, "session")).applications.single {
                it.applicationId == receipt.applicationId.toString()
            }
        assertEquals("Room B", item().place)
        assertEquals("Room A", item().snapshotPlace)
        currentDefinition = currentDefinition.copy(place = null)
        assertEquals(null, item().place)
        assertEquals("Room A", item().snapshotPlace)
        jdbc.update(
            "UPDATE tb_program_session_application SET definition_snapshot = definition_snapshot - 'place' WHERE id = ?",
            receipt.applicationId,
        )
        currentDefinition = currentDefinition.copy(place = "Room C")
        assertEquals("Room C", item().place)
        assertEquals(null, item().snapshotPlace)
        val response =
            mvc
                .perform(get("/application/expos/$EXPO/my-applications").cookie(Cookie("PARTICIPANT_SESSION", "session")))
                .andExpect(status().isOk)
                .andReturn()
                .response
        val program = mapper.readTree(response.contentAsString)["applications"].single { it["type"].asString() == "STANDARD" }
        assertEquals("Room C", program["place"].asString())
        assertTrue(
            query
                .execute(GetOwnerApplicationsCommand(EXPO, "session"))
                .applications
                .filter {
                    it.type == OwnerApplicationType.PREREGISTER
                }.all { it.place == null && it.snapshotPlace == null },
        )
    }

    @Test
    fun `blank expired wrong expo and malformed upstream authority fail closed`() {
        failure(HttpStatus.UNAUTHORIZED) { query.execute(GetOwnerApplicationsCommand(EXPO, "")) }
        sessionStatus = 401
        failure(HttpStatus.UNAUTHORIZED) { cancel.execute(cancelCommand("2")) }
        sessionStatus = 200
        sessionBody = """{"expoId":"${UUID.randomUUID()}","phoneNumber":"01012345678"}"""
        failure(HttpStatus.BAD_GATEWAY) { query.execute(GetOwnerApplicationsCommand(EXPO, "session")) }
        sessionBody = null
        for (body in listOf(
            """{"registered":true,"participants":[]}""",
            """{"registered":false,"participants":[$REPRESENTATIVE]}""",
            """{"registered":true,"participants":[$REPRESENTATIVE,$REPRESENTATIVE]}""",
            """{"registered":true,"participants":[{"participantId":1,"name":"Person","occupation":null,"representative":"true"}]}""",
        )) {
            partyBody = body
            failure(HttpStatus.BAD_GATEWAY) { query.execute(GetOwnerApplicationsCommand(EXPO, "session")) }
        }
        assertEquals("CONFIRMED", state(2))
    }

    @Test
    fun `not registered returns empty list and cannot select another application`() {
        partyBody = """{"registered":false,"participants":[]}"""
        assertTrue(query.execute(GetOwnerApplicationsCommand(EXPO, "session")).applications.isEmpty())
        failure(HttpStatus.NOT_FOUND) { cancel.execute(cancelCommand("2")) }
    }

    @Test
    fun `missing production cancellation provider rejects before changes`() {
        val service =
            CancelOwnerApplicationServiceImpl(
                context.getBean(RegistrationGateway::class.java),
                context.getBean(OwnerApplicationRepository::class.java),
                context.getBean(PreregisterCancellation::class.java),
                context.getBean(PreregisterSessionLedger::class.java),
                context.getBean(CancelProgramSessionService::class.java),
                DefaultListableBeanFactory().getBeanProvider(OwnerCancellationGuard::class.java),
                TEST_CLOCK,
                context.getBean(PreregisterWaitlist::class.java),
                context.getBean(StudentGuardianBatchRepository::class.java),
                context.getBean(CancelStudentGuardianApplicationService::class.java),
            )
        failure(HttpStatus.SERVICE_UNAVAILABLE) { service.execute(cancelCommand("2")) }
        assertEquals("CONFIRMED", state(2))
        assertEquals(0L, count("tb_session_attendance_outbox"))
    }

    @Test
    fun `cancellation deadline equality and attended or entered participants are rejected`() {
        deadline = NOW
        failure(HttpStatus.CONFLICT) { cancel.execute(cancelCommand("2")) }
        deadline = END
        for (blocked in listOf(HttpStatus.CONFLICT, HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.BAD_GATEWAY)) {
            guardStatus = blocked
            failure(blocked) { cancel.execute(cancelCommand("2")) }
        }
        assertEquals("CONFIRMED", state(2))
        assertEquals(3L, count("tb_preregister_transition"))
        assertEquals(0L, count("tb_session_attendance_outbox"))
    }

    @Test
    fun `representative cancels its own session while companions keep theirs and repeat is idempotent`() {
        transactions.executeWithoutResult {
            preregister.saveDefinition(session.copy(id = 2))
            preregister.insert(EXPO, 2, 1, 4, "CONFIRMED")
        }
        partyBody = """{"registered":true,"participants":[$REPRESENTATIVE,""" +
            """{"participantId":2,"name":"Companion","occupation":null,"representative":false},""" +
            """{"participantId":4,"name":"Other session","occupation":null,"representative":false}]}"""
        // #198: the representative no longer waits for companions; each companion keeps its own session.
        cancel.execute(cancelCommand("1"))
        cancel.execute(cancelCommand("1"))
        assertEquals("CONFIRMED", state(2))
        assertEquals("CONFIRMED", state(4))
        cancel.execute(cancelCommand("2"))
        cancel.execute(cancelCommand("4"))
        assertEquals(3L, count("tb_session_attendance_outbox"))
        assertEquals(7L, count("tb_preregister_transition"))
        assertEquals(
            listOf("1:USER:1:true", "2:USER:1:true", "4:USER:1:true"),
            cancelledTransitions(),
        )
        assertEquals(3, closedPermits.get())
        val applications = query.execute(GetOwnerApplicationsCommand(EXPO, "session")).applications
        assertTrue(applications.all { !it.applied && !it.cancellable })
        assertEquals(listOf("CONFIRMED", "CANCELLED"), applications.first().history.map { it.status })
    }

    @Test
    fun `confirmed and waiting programs block preregistration and program cancellation preserves history`() {
        val confirmed = applyProgram.execute(programCommand(2))
        failure(HttpStatus.CONFLICT) { cancel.execute(cancelCommand("2")) }
        cancel.execute(cancelCommand(confirmed.applicationId.toString(), OwnerApplicationType.STANDARD))
        cancel.execute(cancelCommand(confirmed.applicationId.toString(), OwnerApplicationType.STANDARD))
        // The companion (target 2) was cancelled by the session's representative (1); the replay adds no second actor row.
        assertEquals(
            listOf("2:USER:1"),
            jdbc.query(
                "SELECT a.participant_id, h.actor_type, h.actor_participant_id FROM tb_program_session_history h " +
                    "JOIN tb_program_session_application a ON a.id = h.application_id " +
                    "WHERE h.application_id = ? AND h.status = 'CANCELLED'",
                { rs, _ -> "${rs.getLong(1)}:${rs.getString(2)}:${rs.getString(3)}" },
                confirmed.applicationId,
            ),
        )
        assertFalse(
            requireNotNull(
                jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM tb_standard_program_application WHERE participant_id = 2)",
                    Boolean::class.java,
                ),
            ),
        )
        applyProgram.execute(programCommand())
        val retry = applyProgram.execute(programCommand(2).copy(idempotencyKey = "new-key"))
        assertTrue(retry.applicationId != confirmed.applicationId)
        assertEquals("WAITING", retry.status)
        failure(HttpStatus.CONFLICT) { cancel.execute(cancelCommand("2")) }
        cancel.execute(cancelCommand(retry.applicationId.toString(), OwnerApplicationType.STANDARD))
        cancel.execute(cancelCommand("2"))
        val old =
            query.execute(GetOwnerApplicationsCommand(EXPO, "session")).applications.single {
                it.applicationId ==
                    confirmed.applicationId.toString()
            }
        assertEquals(listOf("CONFIRMED", "CANCELLED"), old.history.map { it.status })
        assertFalse(old.applied)
        assertEquals(mapper.readTree("""{"101":"yes"}"""), old.answers)
    }

    @Test
    fun `internal cancellation shares owner protections and waiting program blocks it`() {
        applyProgram.execute(programCommand())
        assertEquals("WAITING", applyProgram.execute(programCommand(2)).status)
        assertEquals(409, internalCancel("""{"representativeId":1,"applicationIds":[2]}"""))
        assertEquals(409, internalCancel("""{"applicationIds":[1]}"""))
        jdbc.execute("TRUNCATE tb_program_session_application, tb_standard_program_application CASCADE")
        deadline = NOW
        assertEquals(409, internalCancel("""{"applicationIds":[2]}"""))
        deadline = END
        guardStatus = HttpStatus.CONFLICT
        assertEquals(409, internalCancel("""{"applicationIds":[2]}"""))
        guardStatus = null
        assertEquals(listOf("CONFIRMED", "CONFIRMED"), listOf(state(1), state(2)))
        assertEquals(0L, count("tb_session_attendance_outbox"))
        adminGuards.clear()
        closedPermits.set(0)
        assertEquals(200, internalCancel("""{"representativeId":1,"applicationIds":[1,2]}"""))
        assertEquals(listOf("CANCELLED", "CANCELLED"), listOf(state(1), state(2)))
        // The request representativeId only scopes ownership; an unauthenticated internal caller is not the actor.
        assertEquals(listOf("1:UNKNOWN:null:true", "2:UNKNOWN:null:true"), cancelledTransitions())
        assertEquals(listOf(2L, 1L), adminGuards.toList())
        assertEquals(2, closedPermits.get())
        assertEquals(2L, count("tb_session_attendance_outbox"))
        assertEquals(200, internalCancel("""{"applicationIds":[1,2]}"""))
        assertEquals(2L, adminGuards.size.toLong())
        assertEquals(400, internalCancel("""{"applicationIds":[1,2,3,4,5,6]}"""))
    }

    @Test
    fun `promotion skips waiting participants without confirmed preregistration`() {
        val confirmed = applyProgram.execute(programCommand())
        val stale = applyProgram.execute(programCommand(2))
        val next = applyProgram.execute(programCommand(99).copy(sessionId = "other"))
        // Simulates a participant cancelled by the pre-#117 internal API while still WAITING.
        jdbc.update("UPDATE tb_preregister_application SET status = 'CANCELLED' WHERE participant_id = 2")
        cancel.execute(cancelCommand(confirmed.applicationId.toString(), OwnerApplicationType.STANDARD))
        assertEquals("WAITING", programStatus(stale.applicationId))
        assertEquals("CONFIRMED", programStatus(next.applicationId))
    }

    @Test
    fun `concurrent internal cancellation and program application keep preregistration consistent`() {
        repeat(5) {
            reset()
            val results =
                concurrently(
                    {
                        internalCancel("""{"applicationIds":[2]}""").let {
                            if (it !=
                                200
                            ) {
                                throw ResponseStatusException(HttpStatus.valueOf(it))
                            }
                        }
                    },
                    { applyProgram.execute(programCommand(2)) },
                )
            val programs = count("tb_program_session_application")
            if (state(2) == "CANCELLED") {
                assertEquals(0L, programs)
                assertEquals(listOf("ok", "blocked"), results)
            } else {
                assertEquals(1L, programs)
                assertEquals(listOf("blocked", "ok"), results)
            }
        }
    }

    @Test
    fun `concurrent internal and owner cancellation cancel once`() {
        repeat(5) {
            reset()
            val results =
                concurrently(
                    { assertTrue(internalCancel("""{"applicationIds":[2]}""") in setOf(200, 409)) },
                    { cancel.execute(cancelCommand("2")) },
                )
            assertEquals(listOf("ok", "ok"), results)
            assertEquals("CANCELLED", state(2))
            assertEquals(4L, count("tb_preregister_transition"))
            assertEquals(1L, count("tb_session_attendance_outbox"))
        }
    }

    @Test
    fun `legacy standard and linked training applications block preregistration`() {
        jdbc.update("INSERT INTO tb_standard_program_application (participant_id, standard_program_id) VALUES (2, 50)")
        failure(HttpStatus.CONFLICT) { cancel.execute(cancelCommand("2")) }
        jdbc.update("DELETE FROM tb_standard_program_application")
        traineeId = 502
        jdbc.update("INSERT INTO tb_training_program_application (trainee_id, training_program_id) VALUES (502, 50)")
        failure(HttpStatus.CONFLICT) { cancel.execute(cancelCommand("2")) }
        assertEquals("CONFIRMED", state(2))
    }

    @Test
    fun `training cancellation retains trainee identity and history but clears applied`() {
        val receipt = applyProgram.execute(programCommand(2).copy(programType = ProgramSessionType.TRAINING))
        assertEquals(502L, receipt.traineeId)
        // #122: the companion's own entry carries the receipt ID that cancellation uses.
        val applied =
            query.execute(GetOwnerApplicationsCommand(EXPO, "session")).applications.single {
                it.type ==
                    OwnerApplicationType.TRAINING
            }
        assertEquals(receipt.applicationId.toString(), applied.applicationId)
        assertEquals(2L, applied.participantId)
        assertTrue(applied.applied)
        traineeId = 502
        guardStatus = HttpStatus.CONFLICT
        failure(HttpStatus.CONFLICT) { cancel.execute(cancelCommand(receipt.applicationId.toString(), OwnerApplicationType.TRAINING)) }
        guardStatus = null
        cancel.execute(cancelCommand(receipt.applicationId.toString(), OwnerApplicationType.TRAINING))
        assertEquals(0L, count("tb_training_program_application"))
        assertEquals(
            502L,
            jdbc.queryForObject(
                "SELECT trainee_id FROM tb_program_session_application WHERE id = ?",
                Long::class.java,
                receipt.applicationId,
            ),
        )
        val item =
            query.execute(GetOwnerApplicationsCommand(EXPO, "session")).applications.single {
                it.type ==
                    OwnerApplicationType.TRAINING
            }
        assertFalse(item.applied)
        assertEquals(listOf("CONFIRMED", "CANCELLED"), item.history.map { it.status })
        assertEquals(
            2L,
            jdbc.queryForObject("SELECT version FROM tb_training_application_version WHERE trainee_id = 502", Long::class.java),
        )
    }

    @Test
    fun `unrelated malformed historic answers cannot prevent individual cancellation`() {
        val snapshot = mapper.writeValueAsString(mapOf("questions" to emptyList<String>(), "informationJson" to "{"))
        jdbc.update(
            "INSERT INTO tb_preregister_reservation (expo_id, request_id, session_id, command, user_request, applicants, state) " +
                "VALUES (?, 'damaged-history', 1, '{}', ?, 1, 'COMPLETED')",
            EXPO,
            snapshot,
        )
        val historic =
            requireNotNull(
                transactions.execute {
                    preregister.insert(EXPO, 1, 1, 1, "CANCELLED", "damaged-history")
                },
            )
        val program = applyProgram.execute(programCommand())
        cancel.execute(cancelCommand(program.applicationId.toString(), OwnerApplicationType.STANDARD))
        assertEquals("CONFIRMED", state(1))
        assertEquals("CONFIRMED", state(2))
        assertEquals(0L, count("tb_standard_program_application"))
        cancel.execute(cancelCommand("2"))
        assertEquals("CANCELLED", state(2))
        assertEquals("CANCELLED", state(historic.id))
        assertEquals(
            snapshot,
            jdbc.queryForObject(
                "SELECT user_request FROM tb_preregister_reservation WHERE request_id = 'damaged-history'",
                String::class.java,
            ),
        )
    }

    @Test
    fun `preregister answers are linked to exact original reservation and missing legacy snapshots stay absent`() {
        val snapshot =
            mapper.writeValueAsString(
                mapOf(
                    "questions" to listOf(mapOf("id" to 101)),
                    "informationJson" to """{"Question":"original"}""",
                ),
            )
        jdbc.update(
            "INSERT INTO tb_preregister_reservation (expo_id, request_id, session_id, command, user_request, applicants, state) " +
                "VALUES (?, 'original', 1, '{}', ?, 1, 'COMPLETED')",
            EXPO,
            snapshot,
        )
        cancel.execute(cancelCommand("2"))
        transactions.executeWithoutResult { preregister.insert(EXPO, 1, 1, 2, "CONFIRMED", "original") }
        val result = query.execute(GetOwnerApplicationsCommand(EXPO, "session")).applications
        assertEquals(null, result.single { it.applicationId == "2" }.answers)
        val current = result.single { it.participantId == 2L && it.applied }
        assertEquals(mapper.readTree("""{"Question":"original"}"""), current.answers)
        assertEquals(mapper.readTree("""[{"id":101}]"""), current.questions)
        assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) {
            transactions.executeWithoutResult { preregister.insert(EXPO, 1, 1, 5, "CONFIRMED", "missing") }
        }
        cancel.execute(cancelCommand(current.applicationId))
        assertEquals(
            current.answers,
            query
                .execute(GetOwnerApplicationsCommand(EXPO, "session"))
                .applications
                .single {
                    it.applicationId ==
                        current.applicationId
                }.answers,
        )
    }

    @Test
    fun `preregister reapplication creates new identity retains cancelled history and validates capacity`() {
        cancel.execute(cancelCommand("2"))
        val command = ApplyPreregisterSessionCommand(EXPO, 1, "reapply", 1, listOf(2))
        val receipt = applyPreregister.execute(command).single()
        assertTrue(receipt.id != 2L)
        assertEquals(receipt, applyPreregister.execute(command).single())
        assertEquals("CANCELLED", state(2))
        assertEquals("CONFIRMED", state(receipt.id))
        assertEquals(2L, count("tb_session_attendance_outbox"))
        val sessions =
            jdbc.query(
                "SELECT session_id, cancelled FROM tb_session_attendance_outbox WHERE participant_id = 2 ORDER BY id",
                { rs, _ -> rs.getLong(1) to rs.getBoolean(2) },
            )
        // #198: the cancellation DELETE names its session; the participant may hold others.
        assertEquals(listOf(1L to true, 1L to false), sessions)
        failure(HttpStatus.CONFLICT) {
            applyPreregister.execute(command.copy(requestId = "over-capacity", participantIds = listOf(4, 5, 6)))
        }
        assertEquals(4L, count("tb_preregister_application"))
    }

    @Test
    fun `concurrent repeated cancellation writes one transition and one DELETE`() {
        concurrently({ cancel.execute(cancelCommand("2")) }, { cancel.execute(cancelCommand("2")) })
        assertEquals("CANCELLED", state(2))
        assertEquals(4L, count("tb_preregister_transition"))
        assertEquals(1L, count("tb_session_attendance_outbox"))
    }

    @Test
    fun `concurrent followup application and preregistration cancellation cannot both commit`() {
        val outcomes =
            concurrently(
                { cancel.execute(cancelCommand("2")) },
                { applyProgram.execute(programCommand(2)) },
            )
        assertEquals(1, outcomes.count { it == "ok" })
        if (state(2) == "CANCELLED") {
            assertEquals(0L, count("tb_program_session_application"))
        } else {
            assertEquals(1L, count("tb_program_session_application"))
        }
    }

    @Test
    fun `failed outbox insert rolls back cancellation and history`() {
        jdbc.execute("ALTER TABLE tb_session_attendance_outbox ADD CONSTRAINT test_owner_reject CHECK (participant_id <> 2)")
        try {
            assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) { cancel.execute(cancelCommand("2")) }
            assertEquals("CONFIRMED", state(2))
            assertEquals(3L, count("tb_preregister_transition"))
        } finally {
            jdbc.execute("ALTER TABLE tb_session_attendance_outbox DROP CONSTRAINT test_owner_reject")
        }
    }

    @Test
    fun `public owner paths require participant cookie and cancellation requires CSRF`() {
        val path = "/application/expos/$EXPO/my-applications"
        mvc.perform(get(path)).andExpect(status().isUnauthorized)
        mvc.perform(get(path).header("X-Internal-Token", "test-token")).andExpect(status().isUnauthorized)
        mvc.perform(get(path).with(user("admin"))).andExpect(status().isUnauthorized)
        mvc.perform(get(path).param("sessionId", "session")).andExpect(status().isUnauthorized)
        mvc
            .perform(get(path).cookie(Cookie("PARTICIPANT_SESSION", "session"), Cookie("PARTICIPANT_SESSION", "other")))
            .andExpect(status().isUnauthorized)
        val result =
            mvc
                .perform(get(path).cookie(Cookie("PARTICIPANT_SESSION", "session")))
                .andExpect(status().isOk)
                .andReturn()
        assertEquals("no-store", result.response.getHeader("Cache-Control"))
        val token = requireNotNull(result.response.getHeader("X-CSRF-TOKEN"))
        val webSession = requireNotNull(result.request.session)
        // The shared context may hold the session-backed repository installed by csrf() elsewhere.
        val csrfCookies = listOfNotNull(Cookie("PARTICIPANT_SESSION", "session"), result.response.getCookie("XSRF-TOKEN"))
        assertFalse(result.response.contentAsString.contains("phoneNumber"))
        mvc
            .perform(post("$path/PREREGISTER/2/cancellation").cookie(Cookie("PARTICIPANT_SESSION", "session")))
            .andExpect(status().isForbidden)
        mvc
            .perform(
                post("$path/PREREGISTER/2/cancellation")
                    .session(webSession as org.springframework.mock.web.MockHttpSession)
                    .header("X-CSRF-TOKEN", token)
                    .cookie(*csrfCookies.toTypedArray()),
            ).andExpect(status().isNoContent)
        assertEquals("CANCELLED", state(2))
        mvc
            .perform(post("$path/PREREGISTER/2/cancellation").with(csrf()))
            .andExpect(status().isUnauthorized)
        mvc
            .perform(post("$path/PREREGISTER/2/cancellation").with(csrf()).cookie(Cookie("PARTICIPANT_SESSION", "other")))
            .andExpect(status().isNotFound)
        mvc
            .perform(post("$path/PREREGISTER/2/cancellation").with(csrf()).cookie(Cookie("PARTICIPANT_SESSION", "session")))
            .andExpect(status().isNoContent)
        assertEquals(1L, count("tb_session_attendance_outbox"))
        sessionStatus = 401
        mvc.perform(get(path).cookie(Cookie("PARTICIPANT_SESSION", "session"))).andExpect(status().isUnauthorized)
    }

    @Test
    fun `session 401 carries SESSION_REQUIRED or SESSION_EXPIRED code on query and cancellation`() {
        val path = "/application/expos/$EXPO/my-applications"
        val session = Cookie("PARTICIPANT_SESSION", "session")

        fun codes(expected: String) {
            mvc
                .perform(get(path).cookie(session))
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value(expected))
            mvc
                .perform(post("$path/PREREGISTER/2/cancellation").with(csrf()).cookie(session))
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.code").value(expected))
            mvc
                .perform(get("$path/preregister-waitlist").cookie(session))
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.code").value(expected))
            mvc
                .perform(post("$path/preregister-waitlist/cancellation").with(csrf()).cookie(session))
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.code").value(expected))
        }
        mvc
            .perform(get(path))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("SESSION_REQUIRED"))
        sessionStatus = 401
        codes("SESSION_REQUIRED")
        // 만료는 User의 401 code 또는 만료 시각으로 구분한다.
        sessionStatus = 200
        sessionBody = """{"expoId":"$EXPO","phoneNumber":"01012345678","expiresAt":"2000-01-01T00:00:00"}"""
        codes("SESSION_EXPIRED")
    }

    private fun cancelCommand(
        id: String,
        type: OwnerApplicationType = OwnerApplicationType.PREREGISTER,
    ) = CancelOwnerApplicationCommand(EXPO, "session", type, id)

    private fun programCommand(participant: Long = 1) =
        ApplyProgramSessionCommand(
            EXPO.toString(),
            ProgramSessionType.STANDARD,
            1,
            participant,
            1,
            mapper.readTree("""{"101":"yes"}"""),
            "session",
            "key",
        )

    private fun internalCancel(body: String): Int =
        mvc
            .perform(
                delete("/internal/expos/$EXPO/preregister-sessions/1/applications")
                    .header("X-Internal-Token", "test-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andReturn()
            .response.status

    private fun programStatus(id: UUID) =
        jdbc.queryForObject("SELECT status FROM tb_program_session_application WHERE id = ?", String::class.java, id)

    private fun state(id: Long) = jdbc.queryForObject("SELECT status FROM tb_preregister_application WHERE id = ?", String::class.java, id)

    private fun cancelledTransitions() =
        jdbc.query(
            "SELECT participant_id, actor_type, actor_participant_id, created_at IS NOT NULL FROM tb_preregister_transition " +
                "WHERE status = 'CANCELLED' ORDER BY participant_id",
        ) { rs, _ -> "${rs.getLong(1)}:${rs.getString(2)}:${rs.getString(3)}:${rs.getBoolean(4)}" }

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)

    private fun failure(
        expected: HttpStatus,
        action: () -> Unit,
    ) {
        assertEquals(expected, assertThrows(ResponseStatusException::class.java, action).statusCode)
    }

    private fun concurrently(vararg actions: () -> Unit): List<String> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(actions.size)
        try {
            val results =
                actions.map { action ->
                    pool.submit<String> {
                        assertTrue(start.await(10, TimeUnit.SECONDS))
                        try {
                            action()
                            "ok"
                        } catch (_: ResponseStatusException) {
                            "blocked"
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
    class Providers {
        @Bean @Primary
        fun ownerClock(): Clock = TEST_CLOCK

        @Bean @Primary
        fun ownerGuard(): OwnerCancellationGuard =
            object : OwnerCancellationGuard {
                override fun acquire(
                    command: CancelOwnerApplicationCommand,
                    participantId: Long,
                    party: OwnerApplicationParty,
                ) = permit()

                override fun acquireAdmin(
                    command: CancelOwnerApplicationCommand,
                    participantId: Long,
                    sessionId: Long,
                ): OwnerCancellationGuard.Permit {
                    adminGuards.add(participantId)
                    return permit()
                }

                private fun permit(): OwnerCancellationGuard.Permit {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
                    guardStatus?.let { throw ResponseStatusException(it) }
                    return object : OwnerCancellationGuard.Permit {
                        override val cancelEndedAt = deadline
                        override val linkedTraineeId = traineeId

                        override fun close() {
                            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
                            closedPermits.incrementAndGet()
                        }
                    }
                }
            }

        @Bean fun ownerForms(mapper: ObjectMapper): ProgramFormProvider =
            object : ProgramFormProvider {
                override fun loadAndValidate(
                    expoId: String,
                    formId: UUID,
                    version: Long,
                    answers: JsonNode,
                ) = ProgramFormSnapshot(
                    expoId,
                    formId,
                    "PROGRAM",
                    version,
                    START,
                    END,
                    mapper.readTree("""[{"id":101,"title":"Question"}]"""),
                )
            }
    }

    companion object {
        private val EXPO = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000045")
        private val NOW = Instant.parse("2026-10-11T10:00:00Z")
        private val START = Instant.parse("2020-01-01T00:00:00Z")
        private val END = Instant.parse("2100-01-01T00:00:00Z")
        private val TEST_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC)
        private val session = PreregisterSessionDefinition(1, EXPO, "Session", START, END, "Place", 5, false, 1)
        private val definition =
            ProgramSessionDefinition(
                EXPO.toString(),
                ProgramSessionType.STANDARD,
                1,
                UUID.randomUUID(),
                1,
                ProgramSessionPolicy(UUID.randomUUID(), setOf("TEACHER"), 1, 5, START, END, false, END, END),
                startedAt = START.toString(),
                endedAt = END.toString(),
            )
        private const val REPRESENTATIVE = """{"participantId":1,"name":"Owner","occupation":"TEACHER","representative":true}"""

        @Volatile private var currentDefinition = definition

        @Volatile private var sessionStatus = 200

        @Volatile private var sessionBody: String? = null

        @Volatile private var partyBody: String? = null

        @Volatile private var guardStatus: HttpStatus? = null

        @Volatile private var deadline = END

        @Volatile private var traineeId: Long? = null
        private val closedPermits = AtomicInteger()
        private val adminGuards = CopyOnWriteArrayList<Long>()
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

                            path.endsWith("participant-sessions/resolve") -> {
                                status = sessionStatus
                                val phone = if (body.contains("other")) "01099999999" else "01012345678"
                                sessionBody ?: """{"expoId":"$EXPO","phoneNumber":"$phone","expiresAt":"2100-01-01T00:00:00"}"""
                            }

                            path.endsWith("managed/select") -> {
                                val id =
                                    ObjectMapper()
                                        .readTree(body)
                                        .path("participantIds")
                                        .get(0)
                                        .asLong()
                                """[{"participantId":$id,"name":"Person","occupation":"TEACHER","representative":true}]"""
                            }

                            path.endsWith("/managed") -> {
                                partyBody ?: if (body.contains("01099999999")) {
                                    """{"registered":true,"participants":[""" +
                                        """{"participantId":99,"name":"Other owner","occupation":"TEACHER","representative":true}]}"""
                                } else {
                                    """{"registered":true,"participants":[$REPRESENTATIVE,""" +
                                        """{"participantId":2,"name":"Companion","occupation":"TEACHER","representative":false}]}"""
                                }
                            }

                            path.contains("standard-programs/") -> {
                                ObjectMapper().writeValueAsString(currentDefinition)
                            }

                            path.endsWith("training-programs/batch") -> {
                                ObjectMapper().writeValueAsString(listOf(currentDefinition.copy(programType = ProgramSessionType.TRAINING)))
                            }

                            path.endsWith("resolve-or-create-by-participant") -> {
                                """{"traineeId":502,"created":true}"""
                            }

                            path.contains("preregister-sessions/") -> {
                                ObjectMapper().writeValueAsString(session)
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

        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")

        @DynamicPropertySource @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("application.program-sessions.enabled") { true }
            registry.add("application.program-sessions.reconcile-delay") { 3600000 }
            registry.add("application.registration.expo-internal-token") { "test-token" }
            registry.add("application.registration.user-internal-token") { "test-token" }
            registry.add("application.registration.expo-url") { "http://127.0.0.1:${server.address.port}" }
            registry.add("application.registration.user-url") { "http://127.0.0.1:${server.address.port}" }
        }
    }
}
