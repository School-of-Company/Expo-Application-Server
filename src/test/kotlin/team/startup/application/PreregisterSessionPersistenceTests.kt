package team.startup.application

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.exception.SessionChangeConflictCode
import team.startup.application.domain.application.exception.SessionChangeConflictException
import team.startup.application.domain.application.presentation.dto.ApplyPreregisterSessionCommand
import team.startup.application.domain.application.presentation.dto.CancelPreregisterSessionCommand
import team.startup.application.domain.application.presentation.dto.PreparePreregisterSessionChangeCommand
import team.startup.application.domain.application.presentation.dto.PreregisterChangeOperation
import team.startup.application.domain.application.presentation.dto.PreregisterSessionChangeRequest
import team.startup.application.domain.application.presentation.dto.PreregisterSessionDefinition
import team.startup.application.domain.application.presentation.dto.PreregisterSessionKey
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.service.ApplyPreregisterSessionService
import team.startup.application.domain.application.service.CancelPreregisterSessionService
import team.startup.application.domain.application.service.PreparePreregisterSessionChangeService
import team.startup.application.domain.application.service.PurgePreregisterSessionsService
import team.startup.application.domain.application.service.ReconcilePreregisterSessionService
import team.startup.application.domain.application.service.impl.PreregisterSessionGateway
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest(properties = ["eureka.client.enabled=false"])
@Import(PermissiveCancellationGuard::class)
@AutoConfigureMockMvc
@Testcontainers
class PreregisterSessionPersistenceTests {
    @Autowired private lateinit var apply: ApplyPreregisterSessionService

    @Autowired private lateinit var cancel: CancelPreregisterSessionService

    @Autowired private lateinit var prepare: PreparePreregisterSessionChangeService

    @Autowired private lateinit var reconcile: ReconcilePreregisterSessionService

    @Autowired private lateinit var purge: PurgePreregisterSessionsService

    @Autowired private lateinit var repository: PreregisterSessionRepository

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @Autowired private lateinit var gateway: PreregisterSessionGateway

    private val expoId = UUID.fromString("00000000-0000-0000-0000-000000000034")
    private lateinit var definition: PreregisterSessionDefinition

    @BeforeEach
    fun reset() {
        pausedResponse.set(null)
        jdbc.execute(
            "TRUNCATE tb_preregister_transition, tb_preregister_request, tb_preregister_application, " +
                "tb_preregister_session_change, tb_preregister_session_state, tb_preregister_deleted_expo RESTART IDENTITY CASCADE",
        )
        definition =
            PreregisterSessionDefinition(
                1,
                expoId,
                "Morning",
                Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                Instant.now().plusSeconds(7200).truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                "Place",
                2,
                false,
                1,
            )
        publish(definition)
    }

    private fun publish(value: PreregisterSessionDefinition) {
        response = mapper.writeValueAsString(value)
        responseStatus = 200
    }

    private fun command(
        vararg participants: Long,
        key: String = UUID.randomUUID().toString(),
        session: Long = 1,
    ) = ApplyPreregisterSessionCommand(expoId, session, key, 1, participants.toList())

    private fun change(
        next: PreregisterSessionDefinition,
        changed: Boolean = true,
    ) = PreparePreregisterSessionChangeCommand(
        expoId,
        1,
        next.revision,
        PreregisterSessionChangeRequest(PreregisterChangeOperation.UPDATE, changed, next),
    )

    private fun assertConflict(block: () -> Unit) {
        assertEquals(409, assertThrows(ResponseStatusException::class.java, block).statusCode.value())
    }

    @Test
    fun `five people are all confirmed and rejection is atomic`() {
        definition = definition.copy(capacity = 5)
        publish(definition)
        val receipt = apply.execute(command(1, 2, 3, 4, 5))
        assertEquals(List(5) { "CONFIRMED" }, receipt.map { it.status })
        assertConflict { apply.execute(command(6, 7)) }
        assertEquals(5, repository.applications(expoId, 1).size)
        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM tb_preregister_transition", Int::class.java))
    }

    @Test
    fun `same request replays exact receipt and different payload conflicts`() {
        val request = command(1, 2, key = "stable")
        val receipt = apply.execute(request)
        assertEquals(receipt, apply.execute(request))
        assertConflict { apply.execute(command(1, 3, key = "stable")) }
        assertEquals(2, repository.applications(expoId, 1).size)
    }

    @Test
    fun `overlapping ids only allocate new participants and reject another owner`() {
        definition = definition.copy(capacity = 3)
        publish(definition)
        val first = apply.execute(command(1, 2))
        val second = apply.execute(command(2, 3))
        assertEquals(first[1], second[0])
        assertEquals("CONFIRMED", second[1].status)
        assertConflict { apply.execute(command(2).copy(representativeId = 9)) }
        assertEquals(3, repository.applications(expoId, 1).size)
    }

    @Test
    fun `last seat concurrent applications serialize`() {
        definition = definition.copy(capacity = 1)
        publish(definition)
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val futures =
                (1L..2L).map { id ->
                    pool.submit<Boolean> {
                        assertTrue(start.await(10, TimeUnit.SECONDS))
                        try {
                            apply.execute(command(id))
                            true
                        } catch (exception: ResponseStatusException) {
                            assertEquals(409, exception.statusCode.value())
                            false
                        }
                    }
                }
            start.countDown()
            assertEquals(1, futures.count { it.get(20, TimeUnit.SECONDS) })
            assertEquals(1, repository.applications(expoId, 1).size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `cancellation only frees seats and repeat cannot cancel new application`() {
        val receipt = apply.execute(command(1, 2))
        // The representative is cancelled with (and after) its companion.
        val cancellation = CancelPreregisterSessionCommand(expoId, 1, 1, receipt.map { it.id })
        val changed = cancel.execute(cancellation)
        assertEquals(listOf(1L, 2L), changed.map { it.participantId })
        assertEquals(listOf("CANCELLED", "CANCELLED"), changed.map { it.status })
        val new = apply.execute(command(1)).single()
        assertEquals("CONFIRMED", new.status)
        assertTrue(new.id > receipt.last().id)
        assertTrue(cancel.execute(cancellation).isEmpty())
        assertEquals("CONFIRMED", repository.active(expoId, 1, 1)?.status)
    }

    @Test
    fun `participant holds sessions that do not overlap and keeps others after one cancellation`() {
        val morning = apply.execute(command(1)).single()
        val afternoon = definition.copy(id = 2, startedAt = definition.endedAt, endedAt = definition.endedAt.plusSeconds(3600))
        publish(afternoon)
        assertEquals(2L, apply.execute(command(1, session = 2)).single().sessionId)
        // Partial overlap with the morning session is refused even though the afternoon one does not overlap it.
        val overlapping =
            definition.copy(
                id = 3,
                startedAt = definition.startedAt.plusSeconds(1800),
                endedAt = definition.endedAt.minusSeconds(1),
            )
        publish(overlapping)
        assertConflict { apply.execute(command(1, session = 3)) }
        publish(definition)
        cancel.execute(CancelPreregisterSessionCommand(expoId, 1, 1, listOf(morning.id)))
        publish(overlapping)
        assertEquals(3L, apply.execute(command(1, session = 3)).single().sessionId)
        assertEquals("CONFIRMED", repository.active(expoId, 2, 1)?.status)
        assertEquals(null, repository.active(expoId, 1, 1))
    }

    @Test
    fun `wrong representative cancellation is atomic`() {
        val receipt = apply.execute(command(1, 2))
        assertConflict { cancel.execute(CancelPreregisterSessionCommand(expoId, 1, 9, receipt.map { it.id })) }
        assertEquals(listOf("CONFIRMED", "CONFIRMED"), repository.applications(expoId, 1).map { it.status })
    }

    @Test
    fun `after start cancellation frees seats without promotion`() {
        definition = definition.copy(startedAt = Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS))
        publish(definition)
        val receipt = apply.execute(command(1, 2))
        assertEquals(1, cancel.execute(CancelPreregisterSessionCommand(expoId, 1, null, listOf(receipt[1].id))).size)
        assertEquals(1L, repository.occupied(expoId, 1))
        assertEquals("CONFIRMED", apply.execute(command(3)).single().status)
    }

    @Test
    fun `cancelled history forbids time change and deletion`() {
        val receipt = apply.execute(command(1))
        cancel.execute(CancelPreregisterSessionCommand(expoId, 1, null, receipt.map { it.id }))
        assertConflict { prepare.execute(change(definition.copy(endedAt = definition.endedAt.plusSeconds(3600), revision = 2))) }
        assertConflict {
            prepare.execute(
                PreparePreregisterSessionChangeCommand(
                    expoId,
                    1,
                    2,
                    PreregisterSessionChangeRequest(PreregisterChangeOperation.DELETE, true, null),
                ),
            )
        }
    }

    @Test
    fun `nullable cancellation deadline accepts old definitions and exact approval reconciliation`() {
        response = mapper.writeValueAsString(definition).replace(",\"cancelEndedAt\":null", "")
        assertEquals(null, gateway.find(expoId, 1)?.cancelEndedAt)
        val next = definition.copy(cancelEndedAt = Instant.parse("2026-10-30T00:30:00.123456Z"), revision = 2)
        prepare.execute(change(next))
        publish(next.copy(cancelEndedAt = null))
        assertFalse(reconcile.execute(PreregisterSessionKey(expoId, 1)))
        publish(next)
        assertTrue(reconcile.execute(PreregisterSessionKey(expoId, 1)))
        assertEquals(next, repository.definition(expoId, 1))
    }

    @Test
    fun `deadline only changes cannot bypass application history protection`() {
        apply.execute(command(1))
        val next = definition.copy(cancelEndedAt = Instant.parse("2026-10-30T00:30:00.123456Z"), revision = 2)
        assertConflict { prepare.execute(change(next)) }
        assertEquals(
            400,
            assertThrows(ResponseStatusException::class.java) {
                prepare.execute(change(next, false))
            }.statusCode.value(),
        )
    }

    @Test
    fun `approval survives expo failure and only matching entire revision unblocks`() {
        val next = definition.copy(capacity = 3, revision = 2)
        val request = change(next)
        prepare.execute(request)
        prepare.execute(request)
        assertConflict { prepare.execute(change(next.copy(title = "Other"))) }
        assertConflict { apply.execute(command(1)) }
        assertFalse(reconcile.execute(PreregisterSessionKey(expoId, 1)))
        publish(next.copy(place = "Wrong"))
        assertFalse(reconcile.execute(PreregisterSessionKey(expoId, 1)))
        responseStatus = 409
        assertConflict { reconcile.execute(PreregisterSessionKey(expoId, 1)) }
        responseStatus = 503
        assertEquals(
            503,
            assertThrows(ResponseStatusException::class.java) {
                reconcile.execute(PreregisterSessionKey(expoId, 1))
            }.statusCode.value(),
        )
        assertTrue(repository.pending(expoId, 1) != null)
        publish(next)
        assertTrue(reconcile.execute(PreregisterSessionKey(expoId, 1)))
        assertEquals("CONFIRMED", apply.execute(command(1)).single().status)
        prepare.execute(request)
    }

    @Test
    fun `closed only changes allow history and reconciliation retains closed admission guard`() {
        apply.execute(command(1, 2))
        val closed = definition.copy(closed = true, revision = 2)
        prepare.execute(change(closed, false))
        publish(closed)
        assertTrue(reconcile.execute(PreregisterSessionKey(expoId, 1)))
        assertConflict { apply.execute(command(4)) }
        cancel.execute(CancelPreregisterSessionCommand(expoId, 1, 1, repository.applications(expoId, 1).map { it.id }))
        assertTrue(repository.active(expoId, 1, 1) == null)
        val opened = closed.copy(closed = false, revision = 3)
        prepare.execute(change(opened, false))
        publish(opened)
        assertTrue(reconcile.execute(PreregisterSessionKey(expoId, 1)))
        assertEquals("CONFIRMED", apply.execute(command(4)).single().status)
    }

    @Test
    fun `capacity only changes after history keep every used seat`() {
        definition = definition.copy(capacity = 4)
        publish(definition)
        apply.execute(command(1))
        apply.execute(command(2))
        assertConflict { prepare.execute(change(definition.copy(capacity = 1, revision = 2))) }
        val grown = definition.copy(capacity = 6, revision = 2)
        prepare.execute(change(grown))
        publish(grown)
        assertTrue(reconcile.execute(PreregisterSessionKey(expoId, 1)))
        assertEquals(grown, repository.definition(expoId, 1))
        // #268: time changes stay blocked, even together with a capacity change.
        assertConflict { prepare.execute(change(grown.copy(capacity = 7, endedAt = grown.endedAt.plusSeconds(3600), revision = 3))) }
    }

    private fun assertConflict(
        code: SessionChangeConflictCode,
        block: () -> Unit,
    ) {
        assertEquals(code, assertThrows(SessionChangeConflictException::class.java, block).code)
    }

    private fun assertBadRequest(block: () -> Unit) {
        assertEquals(400, assertThrows(ResponseStatusException::class.java, block).statusCode.value())
    }

    // #324 QA fixture copies the operating afternoon sessions on a future year; product code fixes no date or duration.
    private fun afternoon(
        day: String,
        end: String,
    ) = definition.copy(
        title = "Afternoon",
        startedAt = Instant.parse("${day}T13:00:00+09:00"),
        endedAt = Instant.parse("${day}T$end:00+09:00"),
        cancelEndedAt = Instant.parse("${day}T12:00:00+09:00"),
        capacity = 10,
        waitingCapacity = 5,
    )

    private fun insertReservation(
        requestId: String,
        state: String,
        queue: Int?,
    ) {
        jdbc.update(
            "INSERT INTO tb_preregister_reservation " +
                "(expo_id, request_id, session_id, command, user_request, applicants, state, queue_seq) " +
                "VALUES (?, ?, 1, ?, '{}', 1, ?, ?)",
            expoId,
            requestId,
            requestId,
            state,
            queue,
        )
    }

    @Test
    fun `history allows shortening the end on the same KST date and keeps every other time lock`() {
        definition = afternoon("2099-10-31", "17:00")
        publish(definition)
        val confirmed = apply.execute(command(1, 2))
        val cancelled = apply.execute(command(3))
        cancel.execute(CancelPreregisterSessionCommand(expoId, 1, null, cancelled.map { it.id }))
        insertReservation("queued-324", "WAITING", 1)
        insertReservation("processing-324", "RESERVED", null)
        try {
            val before = repository.applications(expoId, 1)
            val next = definition.copy(revision = 2)
            val day = java.time.Duration.ofDays(1)
            assertConflict(SessionChangeConflictCode.TIME_LOCKED_BY_HISTORY) {
                prepare.execute(change(next.copy(endedAt = definition.endedAt.plusSeconds(1800))))
            }
            assertConflict(SessionChangeConflictCode.TIME_LOCKED_BY_HISTORY) {
                prepare.execute(
                    change(next.copy(startedAt = definition.startedAt.plusSeconds(1800), endedAt = definition.endedAt.minusSeconds(1800))),
                )
            }
            assertConflict(SessionChangeConflictCode.TIME_LOCKED_BY_HISTORY) {
                prepare.execute(change(next.copy(endedAt = definition.endedAt.minusSeconds(1800), cancelEndedAt = definition.startedAt)))
            }
            assertConflict(SessionChangeConflictCode.TIME_LOCKED_BY_HISTORY) {
                prepare.execute(change(next.copy(startedAt = definition.startedAt.minus(day), endedAt = definition.endedAt.minus(day))))
            }
            assertBadRequest { prepare.execute(change(next.copy(endedAt = definition.startedAt))) }
            assertBadRequest { prepare.execute(change(next.copy(endedAt = definition.startedAt.minusSeconds(1800)))) }
            // Shortening still runs every capacity floor: 3 used seats (2 confirmed + 1 processing) and 1 waiting party.
            assertConflict(SessionChangeConflictCode.BELOW_CONFIRMED_COUNT) {
                prepare.execute(change(next.copy(endedAt = definition.endedAt.minusSeconds(1800), capacity = 2)))
            }
            assertConflict(SessionChangeConflictCode.BELOW_WAITING_COUNT) {
                prepare.execute(change(next.copy(endedAt = definition.endedAt.minusSeconds(1800), waitingCapacity = 0)))
            }
            assertTrue(repository.pending(expoId, 1) == null)
            assertEquals(definition, repository.definition(expoId, 1))

            val shortened =
                next.copy(
                    endedAt = Instant.parse("2099-10-31T16:30:00+09:00"),
                    place = "Hall",
                    capacity = 3,
                    waitingCapacity = 1,
                )
            val request = change(shortened)
            prepare.execute(request)
            prepare.execute(request)
            assertConflict { prepare.execute(change(shortened.copy(endedAt = shortened.endedAt.minusSeconds(1800)))) }
            assertConflict { apply.execute(command(4)) }
            publish(shortened)
            assertTrue(reconcile.execute(PreregisterSessionKey(expoId, 1)))
            assertEquals(shortened, repository.definition(expoId, 1))
            assertEquals(before, repository.applications(expoId, 1))
            assertEquals(confirmed.map { it.id }, repository.applications(expoId, 1).filter { it.status == "CONFIRMED" }.map { it.id })
            assertEquals(
                listOf("RESERVED", "WAITING"),
                jdbc.queryForList(
                    "SELECT state FROM tb_preregister_reservation WHERE request_id IN ('queued-324', 'processing-324') ORDER BY state",
                    String::class.java,
                ),
            )
            assertConflict(SessionChangeConflictCode.TIME_LOCKED_BY_HISTORY) {
                prepare.execute(change(shortened.copy(endedAt = definition.endedAt, revision = 3)))
            }
        } finally {
            jdbc.update("DELETE FROM tb_preregister_reservation WHERE request_id IN ('queued-324', 'processing-324')")
        }
    }

    @Test
    fun `shortening that moves the end to an earlier KST date stays locked`() {
        definition =
            definition.copy(
                startedAt = Instant.parse("2099-10-31T22:00:00+09:00"),
                endedAt = Instant.parse("2099-11-01T01:00:00+09:00"),
            )
        publish(definition)
        apply.execute(command(1))
        assertConflict(SessionChangeConflictCode.TIME_LOCKED_BY_HISTORY) {
            prepare.execute(change(definition.copy(endedAt = Instant.parse("2099-10-31T23:30:00+09:00"), revision = 2)))
        }
        prepare.execute(change(definition.copy(endedAt = Instant.parse("2099-11-01T00:30:00+09:00"), revision = 2)))
    }

    @Test
    fun `shortening keeps an unadopted waiting capacity unapplied`() {
        definition = afternoon("2099-11-01", "16:00").copy(waitingCapacity = null)
        publish(definition)
        apply.execute(command(1))
        val shortened = definition.copy(endedAt = Instant.parse("2099-11-01T15:30:00+09:00"), waitingCapacity = 4, revision = 2)
        prepare.execute(change(shortened))
        publish(shortened)
        assertTrue(reconcile.execute(PreregisterSessionKey(expoId, 1)))
        assertEquals(shortened.copy(waitingCapacity = null), repository.definition(expoId, 1))
    }

    @Test
    fun `same end with history keeps the no-op path and sessions without history keep free time edits`() {
        apply.execute(command(1))
        prepare.execute(change(definition.copy(revision = 2), false))
        publish(definition.copy(revision = 2))
        assertTrue(reconcile.execute(PreregisterSessionKey(expoId, 1)))
        assertBadRequest { prepare.execute(change(definition.copy(endedAt = definition.endedAt.minusSeconds(60), revision = 3), false)) }

        jdbc.execute(
            "TRUNCATE tb_preregister_application, tb_preregister_session_change, tb_preregister_session_state RESTART IDENTITY CASCADE",
        )
        publish(definition)
        val moved =
            definition.copy(
                startedAt = definition.startedAt.plusSeconds(86400),
                endedAt = definition.endedAt.plusSeconds(90000),
                revision = 2,
            )
        prepare.execute(change(moved))
    }

    @Test
    fun `concurrent shortenings of one revision have one winner`() {
        definition = afternoon("2099-10-31", "17:00")
        publish(definition)
        apply.execute(command(1))
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val results =
                listOf(1800L, 3600L).map { seconds ->
                    pool.submit<Boolean> {
                        assertTrue(start.await(10, TimeUnit.SECONDS))
                        try {
                            prepare.execute(change(definition.copy(endedAt = definition.endedAt.minusSeconds(seconds), revision = 2)))
                            true
                        } catch (exception: ResponseStatusException) {
                            assertEquals(409, exception.statusCode.value())
                            false
                        }
                    }
                }
            start.countDown()
            assertEquals(1, results.count { it.get(20, TimeUnit.SECONDS) })
            assertTrue(repository.pending(expoId, 1) != null)
            assertEquals(definition, repository.definition(expoId, 1))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `adding target capacities counts unknown seats against both categories`() {
        definition = definition.copy(capacity = 10)
        publish(definition)
        // Applications stored without a seat category (before V31 or internal apply) are unknown.
        apply.execute(command(1))
        apply.execute(command(2))
        assertConflict { prepare.execute(change(definition.copy(teacherCapacity = 1, nonTeacherCapacity = 9, revision = 2))) }
        val target = definition.copy(teacherCapacity = 2, nonTeacherCapacity = 8, revision = 2)
        prepare.execute(change(target))
        publish(target)
        assertTrue(reconcile.execute(PreregisterSessionKey(expoId, 1)))
        assertEquals(target, repository.definition(expoId, 1))
        assertConflict { apply.execute(command(3)) }
        assertConflict { prepare.execute(change(target.copy(teacherCapacity = 1, nonTeacherCapacity = 9, revision = 3))) }
        val reverted = target.copy(teacherCapacity = null, nonTeacherCapacity = null, revision = 3)
        prepare.execute(change(reverted))
    }

    @Test
    fun `target capacities are refused while a waiting party remains`() {
        apply.execute(command(1))
        jdbc.update(
            "INSERT INTO tb_preregister_reservation " +
                "(expo_id, request_id, session_id, command, user_request, applicants, state, queue_seq) " +
                "VALUES (?, 'queued', 1, 'queued', '{}', 1, 'WAITING', 1)",
            expoId,
        )
        try {
            assertConflict {
                prepare.execute(
                    change(definition.copy(capacity = 4, teacherCapacity = 2, nonTeacherCapacity = 2, revision = 2)),
                )
            }
            prepare.execute(change(definition.copy(capacity = 4, revision = 2)))
        } finally {
            jdbc.update("DELETE FROM tb_preregister_reservation WHERE request_id = 'queued'")
        }
    }

    @Test
    fun `target capacities must match the total capacity`() {
        assertEquals(
            400,
            assertThrows(ResponseStatusException::class.java) {
                prepare.execute(change(definition.copy(teacherCapacity = 1, nonTeacherCapacity = 2, revision = 2)))
            }.statusCode.value(),
        )
        assertEquals(
            400,
            assertThrows(ResponseStatusException::class.java) {
                prepare.execute(change(definition.copy(teacherCapacity = 2, revision = 2)))
            }.statusCode.value(),
        )
    }

    @Test
    fun `definitionChanged flag cannot bypass history guard`() {
        apply.execute(command(1))
        assertEquals(
            400,
            assertThrows(ResponseStatusException::class.java) {
                prepare.execute(change(definition.copy(capacity = 20, revision = 2), false))
            }.statusCode.value(),
        )
    }

    @Test
    fun `delete acknowledgement requires not found and preserves permanent marker`() {
        val request =
            PreparePreregisterSessionChangeCommand(
                expoId,
                1,
                2,
                PreregisterSessionChangeRequest(PreregisterChangeOperation.DELETE, true, null),
            )
        prepare.execute(request)
        assertFalse(reconcile.execute(PreregisterSessionKey(expoId, 1)))
        responseStatus = 404
        assertTrue(reconcile.execute(PreregisterSessionKey(expoId, 1)))
        publish(definition)
        assertConflict { apply.execute(command(1)) }
        prepare.execute(request)
    }

    @Test
    fun `purge deletes receipts and prevents late resurrection`() {
        apply.execute(command(1, 2))
        purge.execute(expoId)
        purge.execute(expoId)
        assertEquals(0, repository.applications(expoId, 1).size)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM tb_preregister_request", Int::class.java))
        assertConflict { apply.execute(command(4)) }
        assertConflict { prepare.execute(change(definition.copy(revision = 2))) }
    }

    @Test
    fun `expo approval http contract authenticates and persists block before 204`() {
        val next = definition.copy(capacity = 3, revision = 2)
        val body = mapper.writeValueAsString(change(next).request)
        val path = "/internal/expos/$expoId/preregister-sessions/1/changes/2"
        assertEquals(
            401,
            mvc
                .perform(put(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn()
                .response.status,
        )
        assertEquals(
            401,
            mvc
                .perform(put(path).header("X-Internal-Token", "wrong").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn()
                .response.status,
        )
        repeat(2) {
            assertEquals(
                204,
                mvc
                    .perform(put(path).header("X-Internal-Token", "test-token").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn()
                    .response.status,
            )
        }
        assertTrue(repository.pending(expoId, 1) != null)
        assertConflict { apply.execute(command(1)) }
        assertEquals(
            409,
            mvc
                .perform(
                    put(path)
                        .header("X-Internal-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(change(next.copy(place = "Other")).request)),
                ).andReturn()
                .response.status,
        )
        val recovery = "/internal/expos/$expoId/preregister-sessions/1/reconciliation"
        assertEquals(
            401,
            mvc
                .perform(post(recovery))
                .andReturn()
                .response.status,
        )
        assertEquals(
            "false",
            mvc
                .perform(post(recovery).header("X-Internal-Token", "test-token"))
                .andReturn()
                .response.contentAsString,
        )
        publish(next)
        assertEquals(
            "true",
            mvc
                .perform(post(recovery).header("X-Internal-Token", "test-token"))
                .andReturn()
                .response.contentAsString,
        )
        assertEquals(
            401,
            mvc
                .perform(post("/internal/expos/$expoId/purge"))
                .andReturn()
                .response.status,
        )
        assertEquals(
            204,
            mvc
                .perform(post("/internal/expos/$expoId/purge").header("X-Internal-Token", "test-token"))
                .andReturn()
                .response.status,
        )
    }

    @Test
    fun `invalid ids and groups are rejected before persistence`() {
        listOf(command(), command(0), command(1, 1), command(1, 2, 3, 4, 5, 6), command(1, key = ""), command(1, session = 0)).forEach {
            assertEquals(400, assertThrows(ResponseStatusException::class.java) { apply.execute(it) }.statusCode.value())
        }
        assertTrue(repository.applications(expoId, 1).isEmpty())
    }

    @Test
    fun `http approval cannot default missing policy booleans`() {
        val path = "/internal/expos/$expoId/preregister-sessions/1/changes/2"
        val missingFlag = """{"operation":"DELETE","definition":null}"""
        val missingClosed = mapper.writeValueAsString(change(definition.copy(revision = 2), false).request).replace("\"closed\":false,", "")
        listOf(missingFlag, missingClosed).forEach { body ->
            assertEquals(
                400,
                mvc
                    .perform(put(path).header("X-Internal-Token", "test-token").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn()
                    .response.status,
            )
        }
        assertTrue(repository.pending(expoId, 1) == null)
    }

    @Test
    fun `application and definition approval race has one winner`() {
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val allocation =
                pool.submit<Boolean> {
                    assertTrue(start.await(10, TimeUnit.SECONDS))
                    try {
                        apply.execute(command(1))
                        true
                    } catch (exception: ResponseStatusException) {
                        assertEquals(409, exception.statusCode.value())
                        false
                    }
                }
            val approval =
                pool.submit<Boolean> {
                    assertTrue(start.await(10, TimeUnit.SECONDS))
                    try {
                        prepare.execute(change(definition.copy(endedAt = definition.endedAt.plusSeconds(3600), revision = 2)))
                        true
                    } catch (
                        exception: ResponseStatusException,
                    ) {
                        assertEquals(409, exception.statusCode.value())
                        false
                    }
                }
            start.countDown()
            val accepted = allocation.get(20, TimeUnit.SECONDS)
            assertEquals(!accepted, approval.get(20, TimeUnit.SECONDS))
            assertEquals(if (accepted) 1 else 0, repository.applications(expoId, 1).size)
            assertEquals(!accepted, repository.pending(expoId, 1) != null)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `slow expo lookup does not hold the purge lock and late response cannot resurrect expo`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        pausedResponse.set(entered to release)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val allocation = pool.submit { assertConflict { apply.execute(command(1)) } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            pool.submit { purge.execute(expoId) }.get(2, TimeUnit.SECONDS)
            release.countDown()
            allocation.get(5, TimeUnit.SECONDS)
            assertTrue(repository.expoDeleted(expoId))
            assertTrue(repository.applications(expoId, 1).isEmpty())
        } finally {
            release.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `upstream client errors are bad gateway and do not allocate`() {
        listOf(400, 401, 403, 422).forEach { status ->
            responseStatus = status
            assertEquals(502, assertThrows(ResponseStatusException::class.java) { apply.execute(command(1)) }.statusCode.value())
            assertTrue(repository.applications(expoId, 1).isEmpty())
        }
    }

    @Test
    fun `database rejects more than one pending change per session`() {
        prepare.execute(change(definition.copy(capacity = 3, revision = 2)))
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update(
                "INSERT INTO tb_preregister_session_change (expo_id, session_id, revision, command) VALUES (?, 1, 3, ?)",
                expoId,
                mapper.writeValueAsString(change(definition.copy(revision = 3)).request),
            )
        }
    }

    @Test
    fun `late stale definition is rejected after a concurrent close is reconciled`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        pausedResponse.set(entered to release)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val allocation = pool.submit { assertConflict { apply.execute(command(1)) } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val closed = definition.copy(closed = true, revision = 2)
            prepare.execute(change(closed, false))
            publish(closed)
            assertTrue(reconcile.execute(PreregisterSessionKey(expoId, 1)))
            release.countDown()
            allocation.get(5, TimeUnit.SECONDS)
            assertTrue(repository.applications(expoId, 1).isEmpty())
            assertEquals(closed, repository.definition(expoId, 1))
        } finally {
            release.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `retries replay committed receipts without expo availability`() {
        val request = command(1, key = "offline-retry")
        val receipt = apply.execute(request)
        val approval = change(definition.copy(closed = true, revision = 2), false)
        prepare.execute(approval)
        responseStatus = 503
        assertEquals(receipt, apply.execute(request))
        prepare.execute(approval)
        assertConflict { apply.execute(command(2, key = "offline-retry")) }
        assertConflict { prepare.execute(approval.copy(request = approval.request.copy(definitionChanged = true))) }
    }

    @Test
    fun `corrupt duplicate pending changes fail closed even without the index`() {
        TransactionTemplate(transactionManager).executeWithoutResult { transaction ->
            transaction.setRollbackOnly()
            jdbc.execute("DROP INDEX uq_preregister_pending_change")
            val body = mapper.writeValueAsString(change(definition.copy(revision = 2)).request)
            jdbc.update(
                "INSERT INTO tb_preregister_session_change (expo_id, session_id, revision, command) VALUES (?, 1, 2, ?), (?, 1, 3, ?)",
                expoId,
                body,
                expoId,
                body,
            )
            val failure = assertThrows(InvalidDataAccessApiUsageException::class.java) { repository.pending(expoId, 1) }
            assertTrue(failure.cause is IllegalStateException)
            assertEquals("Multiple pending preregister session changes", failure.cause?.message)
        }
    }

    @Test
    fun `gateway forbids remote lookup in database transaction and operation suspends callers transaction`() {
        TransactionTemplate(transactionManager).executeWithoutResult {
            assertThrows(IllegalTransactionStateException::class.java) { gateway.find(expoId, 1) }
            assertEquals("CONFIRMED", apply.execute(command(1)).single().status)
        }
    }

    @Test
    fun `occupied seats ignore cancelled history`() {
        val receipt = apply.execute(command(1, 2))
        jdbc.update(
            "INSERT INTO tb_preregister_application (expo_id, session_id, representative_id, participant_id, status) " +
                "SELECT ?, 1, 1, 1000 + n, 'CANCELLED' FROM generate_series(1, 1000) AS n",
            expoId,
        )
        assertEquals(2L, repository.occupied(expoId, 1))
        cancel.execute(CancelPreregisterSessionCommand(expoId, 1, 1, listOf(receipt[1].id)))
        assertEquals(1L, repository.occupied(expoId, 1))
    }

    companion object {
        private val pausedResponse = AtomicReference<Pair<CountDownLatch, CountDownLatch>?>()

        @Volatile private var response = "{}"

        @Volatile private var responseStatus = 200
        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/internal/expo/") { exchange ->
                    val status = if (exchange.requestHeaders.getFirst("X-Internal-Token") == "expo-token") responseStatus else 401
                    val bytes = response.toByteArray()
                    pausedResponse.getAndSet(null)?.let { (entered, release) ->
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                executor = Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } }
                start()
            }

        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")

        @DynamicPropertySource @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("application.registration.expo-url") { "http://127.0.0.1:${server.address.port}" }
            registry.add("application.registration.expo-internal-token") { "expo-token" }
        }
    }
}
