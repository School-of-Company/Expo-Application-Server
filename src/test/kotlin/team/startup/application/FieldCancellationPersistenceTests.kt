package team.startup.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.repository.FieldCancellationDeliveryRepository
import team.startup.application.domain.application.repository.FieldCancellationPolicyRepository
import team.startup.application.domain.application.repository.FieldVisitRegistrationRepository
import team.startup.application.domain.application.service.impl.FieldCancellationTarget
import java.time.LocalDate
import java.util.UUID

@SpringBootTest(properties = ["eureka.client.enabled=false", "spring.jpa.hibernate.ddl-auto=validate"])
@Testcontainers
class FieldCancellationPersistenceTests {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var visits: FieldVisitRegistrationRepository

    @Autowired private lateinit var deliveries: FieldCancellationDeliveryRepository

    @Autowired private lateinit var tx: TransactionTemplate

    @BeforeEach
    fun setup() {
        jdbc.execute("TRUNCATE tb_field_visit_registration CASCADE")
        jdbc.execute(
            "TRUNCATE tb_program_session_application, tb_standard_program_application, tb_training_program_application, tb_preregister_session_state CASCADE",
        )
    }

    @Test
    fun `cancel and durable decision rollback together and restart can recover original operation`() {
        val visit = requireNotNull(tx.execute { visits.activate(EXPO, 2, DATE, UUID.randomUUID()) })
        visits.delivered(visit.id, 1)
        val operation = UUID.randomUUID()
        tx.executeWithoutResult {
            assertTrue(visits.beginCancellation(visit.id, 1, operation))
            deliveries.insert(
                FieldCancellationDeliveryRepository.Delivery(
                    FieldCancellationTarget(visit.id, EXPO, 2, DATE, 1),
                    operation,
                    2,
                    "ACQUIRING",
                ),
            )
        }
        assertThrows(IllegalStateException::class.java) {
            tx.executeWithoutResult {
                assertTrue(visits.cancel(visit.id, 1, 2, operation))
                deliveries.commit(operation)
                error("rollback cancellation")
            }
        }
        assertEquals("ACTIVE", visits.findById(visit.id)?.status)
        assertEquals("ACQUIRING", deliveries.find(visit.id)?.action)
        assertEquals(0, count("CANCELLED"))
        tx.executeWithoutResult {
            assertTrue(visits.cancel(visit.id, 1, 2, operation))
            deliveries.commit(operation)
        }
        val restarted = FieldVisitRegistrationRepository(jdbc)
        assertEquals("CANCELLED", restarted.findById(visit.id)?.status)
        assertTrue(requireNotNull(restarted.findById(visit.id)).cancellationPending)
        assertEquals(operation, deliveries.pending().single().operationId)
        assertFalse(restarted.cancel(visit.id, 1, 2, operation))
        assertEquals(1, count("CANCELLED"))
    }

    @Test
    fun `cancel reactivation keeps ID history and fences old generation and operation`() {
        val visit = requireNotNull(tx.execute { visits.activate(EXPO, 2, DATE, UUID.randomUUID()) })
        assertFalse(visits.beginCancellation(visit.id, 1, UUID.randomUUID()))
        visits.delivered(visit.id, 1)
        val operation = UUID.randomUUID()
        assertTrue(visits.beginCancellation(visit.id, 1, operation))
        assertTrue(visits.cancel(visit.id, 1, 2, operation))
        assertStateRejected { tx.execute { visits.activate(EXPO, 2, DATE, UUID.randomUUID()) } }
        assertFalse(visits.clearCancellation(visit.id, 1, UUID.randomUUID()))
        assertTrue(visits.clearCancellation(visit.id, 1, operation))
        val next = requireNotNull(tx.execute { visits.activate(EXPO, 2, DATE, UUID.randomUUID()) })
        assertEquals(visit.id, next.id)
        assertEquals(2, next.generation)
        assertNull(next.cancelledAt)
        assertFalse(visits.cancel(visit.id, 1, 2, operation))
        assertFalse(visits.clearCancellation(visit.id, 1, operation))
        assertEquals(2, count("REGISTERED"))
        assertEquals(1, count("CANCELLED"))
        assertNotNull(
            jdbc.queryForObject("SELECT actor_participant_id FROM tb_field_visit_event WHERE event_type='CANCELLED'", Long::class.java),
        )
    }

    @Test
    fun `same date is unique and another date is untouched by cancellation`() {
        val first = requireNotNull(tx.execute { visits.activate(EXPO, 2, DATE, UUID.randomUUID()) })
        val replay = requireNotNull(tx.execute { visits.activate(EXPO, 2, DATE, UUID.randomUUID()) })
        val other = requireNotNull(tx.execute { visits.activate(EXPO, 2, DATE.plusDays(1), UUID.randomUUID()) })
        assertEquals(first.id, replay.id)
        visits.delivered(first.id, 1)
        val operation = UUID.randomUUID()
        assertTrue(visits.beginCancellation(first.id, 1, operation))
        tx.executeWithoutResult { assertTrue(visits.cancel(first.id, 1, 2, operation)) }
        assertEquals("ACTIVE", visits.findById(other.id)?.status)
        assertEquals(2, visits.findByParticipants(EXPO, listOf(2)).size)
        assertEquals(2, count("REGISTERED"))
    }

    @Test
    fun `program protection uses KST target visit and keeps unknown legacy programs blocked`() {
        val visit = requireNotNull(tx.execute { visits.activate(EXPO, 2, DATE, UUID.randomUUID()) })
        val policy = FieldCancellationPolicyRepository(jdbc)
        val party =
            team.startup.application.domain.application.presentation.dto.OwnerApplicationParty(
                listOf(
                    team.startup.application.domain.application.presentation.dto
                        .ManagedOwnerParticipant(2, "Owner", null, true),
                ),
            )
        val applicationId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO tb_program_session_application " +
                "(id,expo_id,program_type,program_id,group_id,participant_id,status," +
                "definition_snapshot,form_id,form_version,questions_snapshot,answers_snapshot) " +
                "VALUES (?,?,'STANDARD',1,?,2,'CONFIRMED',?::jsonb,?,1,'[]'::jsonb,'{}'::jsonb)",
            applicationId,
            EXPO,
            UUID.randomUUID(),
            """{"startedAt":"2026-10-23T01:00:00Z"}""",
            UUID.randomUUID(),
        )
        assertNull(policy.blockedReason(visit, party, null))
        jdbc.update(
            "UPDATE tb_program_session_application SET definition_snapshot=?::jsonb WHERE id=?",
            """{"startedAt":"2026-10-21T15:00:00Z"}""",
            applicationId,
        )
        assertEquals("ACTIVE_PROGRAMS", policy.blockedReason(visit, party, null))
        jdbc.update("UPDATE tb_program_session_application SET status='CANCELLED' WHERE id=?", applicationId)
        assertNull(policy.blockedReason(visit, party, null))
        jdbc.update("INSERT INTO tb_standard_program_application(participant_id,standard_program_id) VALUES (2,77)")
        assertEquals("ACTIVE_PROGRAMS", policy.blockedReason(visit, party, null))
    }

    @Test
    fun `only active companions on target visit date block representative FIELD cancellation`() {
        val owner = requireNotNull(tx.execute { visits.activate(EXPO, 2, DATE, UUID.randomUUID()) })
        val policy = FieldCancellationPolicyRepository(jdbc)
        val party =
            team.startup.application.domain.application.presentation.dto.OwnerApplicationParty(
                listOf(
                    team.startup.application.domain.application.presentation.dto
                        .ManagedOwnerParticipant(2, "Owner", null, true),
                    team.startup.application.domain.application.presentation.dto
                        .ManagedOwnerParticipant(3, "Companion", null, false),
                ),
            )
        tx.executeWithoutResult { visits.activate(EXPO, 3, DATE.plusDays(1), UUID.randomUUID()) }
        assertNull(policy.blockedReason(owner, party, null))
        val companion = requireNotNull(tx.execute { visits.activate(EXPO, 3, DATE, UUID.randomUUID()) })
        assertEquals("COMPANIONS_ACTIVE", policy.blockedReason(owner, party, null))
        visits.delivered(companion.id, 1)
        val operation = UUID.randomUUID()
        assertTrue(visits.beginCancellation(companion.id, 1, operation))
        tx.executeWithoutResult { assertTrue(visits.cancel(companion.id, 1, 2, operation)) }
        assertNull(policy.blockedReason(owner, party, null))
        jdbc.update(
            "INSERT INTO tb_preregister_session_state(expo_id,session_id,definition) VALUES (?,1,?)",
            EXPO,
            """{"startedAt":"2026-10-23T09:00:00"}""",
        )
        jdbc.update(
            "INSERT INTO tb_preregister_application(expo_id,session_id,representative_id,participant_id,status) VALUES (?,1,2,3,'CONFIRMED')",
            EXPO,
        )
        assertNull(policy.blockedReason(owner, party, null))
        jdbc.update(
            "UPDATE tb_preregister_session_state SET definition=? WHERE expo_id=? AND session_id=1",
            """{"startedAt":"2026-10-22T09:00:00"}""",
            EXPO,
        )
        assertEquals("COMPANIONS_ACTIVE", policy.blockedReason(owner, party, null))
        assertEquals(
            "CONFIRMED",
            jdbc.queryForObject("SELECT status FROM tb_preregister_application WHERE expo_id=?", String::class.java, EXPO),
        )
    }

    private fun count(type: String): Int =
        requireNotNull(jdbc.queryForObject("SELECT count(*) FROM tb_field_visit_event WHERE event_type=?", Int::class.java, type))

    @Test
    fun `committed decision cannot be aborted and reads in the same transaction see the decision`() {
        val visit = requireNotNull(tx.execute { visits.activate(EXPO, 2, DATE, UUID.randomUUID()) })
        val operation = UUID.randomUUID()
        val delivery =
            FieldCancellationDeliveryRepository.Delivery(FieldCancellationTarget(visit.id, EXPO, 2, DATE, 1), operation, 2, "ABORT")
        tx.executeWithoutResult {
            deliveries.insert(delivery)
            assertEquals("ACQUIRING", deliveries.find(visit.id)?.action)
            deliveries.commit(operation)
            deliveries.abort(operation)
            assertEquals(delivery.copy(action = "COMMIT"), deliveries.find(visit.id))
        }
        assertStateRejected { deliveries.commit(operation) }
        assertStateRejected { deliveries.commit(UUID.randomUUID()) }
    }

    @Test
    fun `aborted decision is terminal and deferred delivery is excluded until retry`() {
        val visit = requireNotNull(tx.execute { visits.activate(EXPO, 2, DATE, UUID.randomUUID()) })
        val operation = UUID.randomUUID()
        val delivery =
            FieldCancellationDeliveryRepository.Delivery(FieldCancellationTarget(visit.id, EXPO, 2, DATE, 1), operation, 2, "COMMIT")
        deliveries.insert(delivery)
        assertEquals(delivery.copy(action = "ACQUIRING"), deliveries.pending().single())
        deliveries.abort(operation)
        deliveries.abort(operation)
        assertStateRejected { deliveries.commit(operation) }
        assertEquals("ABORT", deliveries.find(visit.id)?.action)
        deliveries.defer(operation)
        assertTrue(deliveries.pending().isEmpty())
        deliveries.delete(UUID.randomUUID())
        assertEquals(operation, deliveries.find(visit.id)?.operationId)
        deliveries.delete(operation)
        assertNull(deliveries.find(visit.id))
    }

    private fun assertStateRejected(operation: () -> Unit) {
        val failure = assertThrows(InvalidDataAccessApiUsageException::class.java) { operation() }
        assertInstanceOf(IllegalStateException::class.java, failure.cause)
    }

    companion object {
        @Container @JvmStatic
        @ServiceConnection
        val postgres = PostgreSQLContainer("postgres:17-alpine")
        private val EXPO = UUID.randomUUID()
        private val DATE = LocalDate.of(2026, 10, 22)
    }
}
