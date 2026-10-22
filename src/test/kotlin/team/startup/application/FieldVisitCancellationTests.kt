package team.startup.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.CancelOwnerApplicationCommand
import team.startup.application.domain.application.presentation.dto.FieldVisitRegistration
import team.startup.application.domain.application.presentation.dto.GetOwnerCancellationStatusCommand
import team.startup.application.domain.application.presentation.dto.ManagedOwnerParticipant
import team.startup.application.domain.application.presentation.dto.OwnerApplicationParty
import team.startup.application.domain.application.presentation.dto.OwnerApplicationType
import team.startup.application.domain.application.presentation.dto.OwnerCancellationStatus
import team.startup.application.domain.application.repository.FieldCancellationDeliveryRepository
import team.startup.application.domain.application.repository.FieldCancellationPolicyRepository
import team.startup.application.domain.application.repository.FieldVisitRegistrationRepository
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import team.startup.application.domain.application.service.impl.FieldCancellationGateway
import team.startup.application.domain.application.service.impl.FieldCancellationTarget
import team.startup.application.domain.application.service.impl.FieldVisitCancellation
import team.startup.application.domain.application.service.impl.OwnerApplicationRejectedException
import team.startup.application.domain.application.service.impl.OwnerCancellationGateway
import team.startup.application.domain.application.service.impl.PreregisterSessionLedger
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class FieldVisitCancellationTests {
    private val visits = mock(FieldVisitRegistrationRepository::class.java)
    private val deliveries = mock(FieldCancellationDeliveryRepository::class.java)
    private val policy = mock(FieldCancellationPolicyRepository::class.java)
    private val gateway = mock(FieldCancellationGateway::class.java)
    private val user = mock(OwnerCancellationGateway::class.java)
    private val ledger = mock(PreregisterSessionLedger::class.java)
    private val programs = mock(ProgramSessionApplicationRepository::class.java)
    private val trainings = mock(TrainingProgramApplicationRepository::class.java)
    private val id = UUID.randomUUID()
    private val expo = UUID.randomUUID()
    private val date = LocalDate.of(2026, 10, 22)
    private val target = FieldCancellationTarget(id, expo, 2, date, 1)
    private val party = OwnerApplicationParty(listOf(ManagedOwnerParticipant(2, "Owner", null, true)))
    private val command = CancelOwnerApplicationCommand(expo, "session", OwnerApplicationType.FIELD, id.toString(), 1)
    private var current = FieldVisitRegistration(id, expo, 2, date, "ACTIVE", 1, Instant.parse("2026-10-22T00:00:00Z"), null, null, false)
    private var delivery: FieldCancellationDeliveryRepository.Delivery? = null
    private var events = 0
    private var guarded = false
    private var enteredToday = false
    private var failCommit = false
    private var failAbort = false
    private var failProjection = false
    private var beforeAcquire: () -> Unit = {}

    @BeforeEach
    fun setup() {
        val manager =
            object : PlatformTransactionManager {
                override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()

                override fun commit(status: TransactionStatus) {}

                override fun rollback(status: TransactionStatus) {}
            }
        `when`(ledger.transactions).thenReturn(TransactionTemplate(manager))
        `when`(visits.findById(id)).thenAnswer { current }
        `when`(visits.lock(id)).thenAnswer { current }
        `when`(visits.findByParticipants(expo, listOf(2L))).thenAnswer { listOf(current) }
        `when`(deliveries.lock(expo, id)).thenReturn(AutoCloseable {})
        `when`(deliveries.find(id)).thenAnswer { delivery }
        `when`(deliveries.pending()).thenAnswer { listOfNotNull(delivery) }
        doAnswer {
            delivery = it.getArgument(0)
            null
        }.`when`(deliveries).insert(argument(FieldCancellationDeliveryRepository.Delivery(target, UUID(0, 0), 2, "ACQUIRING")))
        doAnswer {
            delivery = delivery?.copy(action = "COMMIT")
            null
        }.`when`(deliveries).commit(argument(UUID(0, 0)))
        doAnswer {
            if (delivery?.action ==
                "ACQUIRING"
            ) {
                delivery = delivery?.copy(action = "ABORT")
            }
            ; null
        }.`when`(deliveries).abort(argument(UUID(0, 0)))
        doAnswer {
            delivery = null
            null
        }.`when`(deliveries).delete(argument(UUID(0, 0)))
        `when`(visits.beginCancellation(argument(UUID(0, 0)), anyLong(), argument(UUID(0, 0)))).thenAnswer {
            current = current.copy(cancellationPending = true)
            true
        }
        `when`(visits.cancel(argument(UUID(0, 0)), anyLong(), anyLong(), argument(UUID(0, 0)))).thenAnswer {
            current = current.copy(status = "CANCELLED", cancelledAt = Instant.now(), cancelledBy = it.getArgument(2))
            events++
            true
        }
        `when`(visits.clearCancellation(argument(UUID(0, 0)), anyLong(), argument(UUID(0, 0)))).thenAnswer {
            current = current.copy(cancellationPending = false)
            true
        }
        `when`(user.verify(GetOwnerCancellationStatusCommand(expo, "session", 2))).thenReturn(
            OwnerCancellationGateway.VerifiedStatus("01000000000", OwnerCancellationStatus(2, true, true, false, null)),
        )
        `when`(gateway.status(target, "01000000000")).thenAnswer { FieldCancellationGateway.Status(enteredToday, false, "NONE", null) }
        doAnswer {
            beforeAcquire()
            if (enteredToday) throw ResponseStatusException(HttpStatus.CONFLICT)
            guarded = true
            null
        }.`when`(gateway).acquire(argument(target), argument(UUID(0, 0)), argument("01000000000"))
        doAnswer {
            if (failCommit) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
            guarded = false
            null
        }.`when`(gateway).commit(argument(target), argument(UUID(0, 0)))
        doAnswer {
            if (failAbort) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
            guarded = false
            null
        }.`when`(gateway).abort(argument(target), argument(UUID(0, 0)))
        doAnswer {
            if (failProjection) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
            null
        }.`when`(gateway).projectCancellation(argument(target), argument(UUID(0, 0)))
    }

    private fun service(
        enabled: Boolean = true,
        featureEnabled: Boolean = true,
    ) = FieldVisitCancellation(visits, deliveries, policy, gateway, user, ledger, programs, trainings, enabled, featureEnabled)

    @Test
    fun `yesterday entry does not block unentered target visit even after event start and repeat keeps one event`() {
        service().execute(command, party)
        assertEquals("CANCELLED", current.status)
        assertEquals(2L, current.cancelledBy)
        assertFalse(current.cancellationPending)
        assertFalse(guarded)
        assertNull(delivery)
        service().execute(command, party)
        assertEquals(1, events)
        verify(gateway, times(1)).acquire(argument(target), argument(UUID(0, 0)), argument("01000000000"))
    }

    @Test
    fun `entry on target date remains blocking after exit`() {
        enteredToday = true
        assertEquals("ENTERED", service().items(expo, "session", party).single().cancellationBlockedReason)
        failure("ENTERED") { service().execute(command, party) }
        assertEquals("ACTIVE", current.status)
        assertEquals(0, events)
    }

    @Test
    fun `scan winning between observation and acquire cannot also cancel`() {
        beforeAcquire = { enteredToday = true }
        assertThrows(ResponseStatusException::class.java) { service().execute(command, party) }
        assertEquals("ACTIVE", current.status)
        assertFalse(current.cancellationPending)
        assertNull(delivery)
        assertEquals(0, events)
    }

    @Test
    fun `uncertain guard acquisition retains pending until abort is acknowledged`() {
        beforeAcquire = {
            guarded = true
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
        }
        failAbort = true
        assertThrows(ResponseStatusException::class.java) { service().execute(command, party) }
        assertTrue(current.cancellationPending)
        assertEquals("ABORT", delivery?.action)
        assertEquals("ACTIVE", current.status)
        failAbort = false
        service().recoverPending()
        assertFalse(guarded)
        assertFalse(current.cancellationPending)
        assertNull(delivery)
        assertEquals(0, events)
    }

    @Test
    fun `lost commit response keeps durable committed decision and recovery does not abort it`() {
        failCommit = true
        assertThrows(ResponseStatusException::class.java) { service().execute(command, party) }
        assertEquals("CANCELLED", current.status)
        assertEquals("COMMIT", delivery?.action)
        assertTrue(current.cancellationPending)
        val operation = requireNotNull(delivery).operationId
        failCommit = false
        service().recoverPending()
        assertNull(delivery)
        assertFalse(current.cancellationPending)
        assertEquals(1, events)
        verify(gateway, never()).abort(target, operation)
        verify(gateway, times(2)).commit(target, operation)
    }

    @Test
    fun `User projection failure retains pending and exact operation for owner retry`() {
        failProjection = true
        assertThrows(ResponseStatusException::class.java) { service().execute(command, party) }
        assertNotNull(delivery)
        val operation = requireNotNull(delivery).operationId
        assertTrue(current.cancellationPending)
        failProjection = false
        service().execute(command, party)
        assertEquals(1, events)
        assertNull(delivery)
        verify(gateway, times(2)).projectCancellation(target, operation)
    }

    @Test
    fun `reactivation cannot be cancelled by an old generation retry`() {
        service().execute(command, party)
        current = current.copy(status = "ACTIVE", generation = 2, cancelledAt = null, cancelledBy = null)
        failure("FIELD_GENERATION_CHANGED") { service().execute(command, party) }
        assertEquals("ACTIVE", current.status)
        assertEquals(1, events)
    }

    @Test
    fun `other expo other party missing generation and disabled provider fail closed`() {
        failure("FIELD_NOT_FOUND") { service().execute(command.copy(expoId = UUID.randomUUID()), party) }
        failure("FIELD_NOT_FOUND") { service().execute(command, OwnerApplicationParty(emptyList())) }
        failure("FIELD_GENERATION_REQUIRED") { service().execute(command.copy(generation = null), party) }
        failure("EXTERNAL_CONTRACT_REQUIRED") { service(false).execute(command, party) }
        failure("EXTERNAL_CONTRACT_REQUIRED") { service(featureEnabled = false).execute(command, party) }
        current = current.copy(deliveryPending = true)
        assertEquals("REGISTRATION_IN_PROGRESS", service().items(expo, "session", party).single().cancellationBlockedReason)
        failure("APPLICATION_STATE_CHANGED") { service().execute(command, party) }
        assertEquals(0, events)
    }

    @Test
    fun `program and companion protection are checked again after remote guard`() {
        `when`(policy.blockedReason(argument(current), argument(party), org.mockito.ArgumentMatchers.isNull()))
            .thenReturn(null, "ACTIVE_PROGRAMS")
        failure("ACTIVE_PROGRAMS") { service().execute(command, party) }
        assertEquals("ACTIVE", current.status)
        assertFalse(current.cancellationPending)
        assertFalse(guarded)
        assertEquals(0, events)
    }

    @Test
    fun `ownership changing while acquiring guard aborts without a cancellation event`() {
        beforeAcquire = {
            `when`(user.verify(GetOwnerCancellationStatusCommand(expo, "session", 2))).thenReturn(
                OwnerCancellationGateway.VerifiedStatus("01099999999", OwnerCancellationStatus(2, true, true, false, null)),
            )
        }
        failure("OWNERSHIP_CHANGED") { service().execute(command, party) }
        assertEquals("ACTIVE", current.status)
        assertFalse(guarded)
        assertFalse(current.cancellationPending)
        assertNull(delivery)
        assertEquals(0, events)
    }

    private inline fun <reified T> argument(fallback: T): T = any(T::class.java) ?: fallback

    private fun failure(
        code: String,
        action: () -> Unit,
    ) = assertEquals(code, assertThrows(OwnerApplicationRejectedException::class.java, action).code)
}
