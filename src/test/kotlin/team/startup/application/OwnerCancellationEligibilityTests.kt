package team.startup.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.CancelOwnerApplicationCommand
import team.startup.application.domain.application.presentation.dto.GetOwnerApplicationsCommand
import team.startup.application.domain.application.presentation.dto.GetOwnerCancellationStatusCommand
import team.startup.application.domain.application.presentation.dto.ManagedOwnerParticipant
import team.startup.application.domain.application.presentation.dto.OwnerApplicationHistory
import team.startup.application.domain.application.presentation.dto.OwnerApplicationItem
import team.startup.application.domain.application.presentation.dto.OwnerApplicationParty
import team.startup.application.domain.application.presentation.dto.OwnerApplicationType
import team.startup.application.domain.application.presentation.dto.OwnerCancellationStatus
import team.startup.application.domain.application.repository.ManagedParticipantRepository
import team.startup.application.domain.application.repository.OwnerApplicationRepository
import team.startup.application.domain.application.repository.OwnerCancellationDeliveryRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.service.impl.AttendanceCancellationStatusGateway
import team.startup.application.domain.application.service.impl.GetOwnerApplicationsServiceImpl
import team.startup.application.domain.application.service.impl.OwnerCancellationDeadlineGateway
import team.startup.application.domain.application.service.impl.OwnerCancellationGateway
import team.startup.application.domain.application.service.impl.PreregisterCompanionPolicy
import team.startup.application.domain.application.service.impl.RegistrationGateway
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class OwnerCancellationEligibilityTests {
    private val gateway = mock(RegistrationGateway::class.java)
    private val repository = mock(OwnerApplicationRepository::class.java)
    private val user = mock(OwnerCancellationGateway::class.java)
    private val attendance = mock(AttendanceCancellationStatusGateway::class.java)
    private val deadlines = mock(OwnerCancellationDeadlineGateway::class.java)
    private val deliveries = mock(OwnerCancellationDeliveryRepository::class.java)
    private val preregister = mock(PreregisterSessionRepository::class.java)
    private val managed = mock(ManagedParticipantRepository::class.java)
    private var reading = false
    private val transactions =
        object : PlatformTransactionManager {
            override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
                assertTrue(requireNotNull(definition).isReadOnly)
                assertFalse(reading)
                reading = true
                return SimpleTransactionStatus()
            }

            override fun commit(status: TransactionStatus) {
                reading = false
            }

            override fun rollback(status: TransactionStatus) {
                reading = false
            }
        }

    @BeforeEach
    fun start() {
        `when`(gateway.managedParty(EXPO, COMMAND.sessionId)).thenReturn(PARTY)
        configure(APPLICATION)
    }

    @Test
    fun `eligible active application and cancelled history retain response order without a mutation`() {
        val cancelled =
            APPLICATION.copy(
                applicationId = "3",
                status = "CANCELLED",
                applied = false,
                cancellationBlockedReason = "CANCELLED",
            )
        `when`(repository.applications(EXPO, PARTY)).thenReturn(listOf(cancelled, APPLICATION))
        val response = service().execute(COMMAND)
        assertEquals(PARTY.participants, response.participants)
        assertEquals(listOf("3", "2"), response.applications.map { it.applicationId })
        assertEquals(cancelled, response.applications[0])
        assertEquals(APPLICATION.history, response.applications[1].history)
        assertTrue(response.applications[1].cancellable)
        assertEquals("", response.applications[1].cancellationBlockedReason)
        verify(user, times(1)).verify(STATUS_COMMAND)
        verify(attendance, times(1)).find(EXPO, 2, PHONE)
    }

    @Test
    fun `field registered participants without applications are listed for QR lookup`() {
        val party =
            OwnerApplicationParty(
                listOf(
                    ManagedOwnerParticipant(1, "Owner", null, true, fieldRegistered = true),
                    ManagedOwnerParticipant(2, "Companion", null, false, fieldRegistered = true),
                    ManagedOwnerParticipant(3, "Waiting", null, false),
                ),
            )
        `when`(gateway.managedParty(EXPO, COMMAND.sessionId)).thenReturn(party)
        `when`(repository.applications(EXPO, party)).thenReturn(emptyList())
        `when`(managed.qrAvailable(EXPO, 1, 1, true)).thenReturn(true)
        `when`(managed.qrAvailable(EXPO, 2, 1, true)).thenReturn(true)

        val response = service().execute(COMMAND)

        assertEquals(emptyList<OwnerApplicationItem>(), response.applications)
        assertEquals(listOf(1L, 2L), response.qrParticipantIds)
        verify(managed).qrAvailable(EXPO, 3, 1, false)
        assertEquals(emptyList<Long>(), service(false).execute(COMMAND).qrParticipantIds)
    }

    @Test
    fun `entered attended and held or committed participant states block eligibility`() {
        for ((snapshot, reason) in listOf(
            OBSERVATION.copy(entered = true) to "ENTERED",
            OBSERVATION.copy(programAttended = true) to "ATTENDED",
            OBSERVATION.copy(guardState = "HELD") to "CANCELLATION_IN_PROGRESS",
            OBSERVATION.copy(guardState = "COMMITTED") to "CANCELLATION_IN_PROGRESS",
            OBSERVATION.copy(linkedTraineeId = 503) to "PARTICIPANT_STATE_CHANGED",
        )) {
            `when`(attendance.find(EXPO, 2, PHONE)).thenReturn(snapshot)
            blocked(reason)
        }
    }

    @Test
    fun `equality past and unset cancellation deadline never advertise eligibility`() {
        for (deadline in listOf(NOW, NOW.minusSeconds(1))) {
            `when`(deadlines.preregister(EXPO, 7)).thenReturn(deadline)
            blocked("DEADLINE_EXPIRED")
        }
        `when`(deadlines.preregister(EXPO, 7)).thenThrow(ResponseStatusException(HttpStatus.CONFLICT))
        blocked("DEADLINE_UNSET")
    }

    @Test
    fun `active programs and representative companions are checked before showing cancellation`() {
        `when`(repository.activePrograms(EXPO, 2, 502)).thenReturn(true)
        blocked("ACTIVE_PROGRAMS")
        // #257: only the representative's own session is checked; a companion's application never is.
        `when`(repository.activePrograms(EXPO, 2, 502)).thenReturn(false)
        `when`(repository.activeCompanions(EXPO, 7, 2)).thenReturn(true)
        assertTrue(
            service()
                .execute(COMMAND)
                .applications
                .single()
                .cancellable,
        )
        val representative = APPLICATION.copy(participantId = 1)
        configure(representative)
        `when`(repository.activeCompanions(EXPO, 7, 1)).thenReturn(true)
        blocked("COMPANIONS_ACTIVE")
    }

    @Test
    fun `pending session changes and local cancellation decisions block eligibility`() {
        `when`(preregister.deleted(EXPO, 7)).thenReturn(true)
        blocked("SESSION_CHANGE_PENDING")
        `when`(preregister.deleted(EXPO, 7)).thenReturn(false)
        `when`(deliveries.find(CANCELLATION)).thenReturn(mock(OwnerCancellationDeliveryRepository.Delivery::class.java))
        blocked("CANCELLATION_IN_PROGRESS")
    }

    @Test
    fun `program policies allow standard and linked training cancellation and reuse participant snapshot`() {
        val standard = APPLICATION.copy(applicationId = UUID.randomUUID().toString(), type = OwnerApplicationType.STANDARD)
        val training = APPLICATION.copy(applicationId = UUID.randomUUID().toString(), type = OwnerApplicationType.TRAINING)
        configure(standard, training)
        assertTrue(service().execute(COMMAND).applications.all { it.cancellable })
        verify(user, times(1)).verify(STATUS_COMMAND)
        verify(attendance, times(1)).find(EXPO, 2, PHONE)
        `when`(repository.cancellationTarget(cancel(training), PARTY))
            .thenReturn(OwnerApplicationRepository.CancellationTarget(training.applicationId, 2, 7, "CONFIRMED", 503))
        val response = service().execute(COMMAND)
        assertTrue(response.applications[0].cancellable)
        assertEquals("PARTICIPANT_STATE_CHANGED", response.applications[1].cancellationBlockedReason)
    }

    @Test
    fun `provider outage is conservative but expired session and lost ownership retain errors`() {
        `when`(attendance.find(EXPO, 2, PHONE)).thenThrow(ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE))
        blocked("EXTERNAL_CONTRACT_REQUIRED")
        for (status in listOf(HttpStatus.UNAUTHORIZED, HttpStatus.NOT_FOUND)) {
            doThrow(ResponseStatusException(status)).`when`(user).verify(STATUS_COMMAND)
            assertEquals(status, assertThrows(ResponseStatusException::class.java) { service().execute(COMMAND) }.statusCode)
        }
    }

    @Test
    fun `disabled delivery and cancelled-only records make no remote status calls`() {
        assertFalse(
            service(false)
                .execute(COMMAND)
                .applications
                .single()
                .cancellable,
        )
        `when`(repository.applications(EXPO, PARTY)).thenReturn(listOf(APPLICATION.copy(status = "CANCELLED", applied = false)))
        assertFalse(
            service()
                .execute(COMMAND)
                .applications
                .single()
                .cancellable,
        )
        verifyNoInteractions(user, attendance, deadlines, deliveries, preregister)
    }

    @Test
    fun `application changed after listing cannot be advertised as cancellable`() {
        `when`(repository.cancellationTarget(CANCELLATION, PARTY))
            .thenReturn(OwnerApplicationRepository.CancellationTarget("2", 2, 7, "CANCELLED"))
        blocked("APPLICATION_STATE_CHANGED")
    }

    private fun configure(vararg applications: OwnerApplicationItem) {
        `when`(repository.applications(EXPO, PARTY)).thenReturn(applications.toList())
        applications.forEach { application ->
            `when`(repository.cancellationTarget(cancel(application), PARTY))
                .thenReturn(
                    OwnerApplicationRepository.CancellationTarget(
                        application.applicationId,
                        application.participantId,
                        application.sessionOrProgramId,
                        application.status,
                        if (application.type == OwnerApplicationType.TRAINING) 502 else null,
                    ),
                )
            val id = application.participantId
            `when`(user.verify(GetOwnerCancellationStatusCommand(EXPO, COMMAND.sessionId, id))).thenAnswer {
                assertFalse(reading)
                OwnerCancellationGateway.VerifiedStatus(PHONE, OwnerCancellationStatus(id, id == 1L, false, true, 502))
            }
            `when`(attendance.find(EXPO, id, PHONE)).thenAnswer {
                assertFalse(reading)
                OBSERVATION.copy(participantId = id, representative = id == 1L)
            }
            if (application.type == OwnerApplicationType.PREREGISTER) {
                `when`(deadlines.preregister(EXPO, application.sessionOrProgramId)).thenReturn(NOW.plusSeconds(100))
            } else {
                `when`(deadlines.program(EXPO, application.type, application.sessionOrProgramId)).thenReturn(NOW.plusSeconds(100))
            }
        }
    }

    private fun service(enabled: Boolean = true): GetOwnerApplicationsServiceImpl =
        GetOwnerApplicationsServiceImpl(
            gateway,
            repository,
            user,
            attendance,
            deadlines,
            deliveries,
            preregister,
            Clock.fixed(NOW, ZoneOffset.UTC),
            // 동행자 추가 여부(#169)는 이 테스트 대상이 아니므로 공급자 장애로 둔다.
            org.mockito.Mockito.mock(PreregisterCompanionPolicy::class.java) {
                throw org.springframework.web.server
                    .ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
            },
            managed,
            transactions,
            enabled,
        )

    private fun blocked(reason: String) {
        val application = service().execute(COMMAND).applications.single()
        assertFalse(application.cancellable)
        assertEquals(reason, application.cancellationBlockedReason)
    }

    private fun cancel(application: OwnerApplicationItem): CancelOwnerApplicationCommand =
        CancelOwnerApplicationCommand(EXPO, COMMAND.sessionId, application.type, application.applicationId)

    companion object {
        private val EXPO = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000045")
        private val NOW = Instant.parse("2026-10-12T00:00:00Z")
        private const val PHONE = "01012345678"
        private val COMMAND = GetOwnerApplicationsCommand(EXPO, "opaque")
        private val CANCELLATION = CancelOwnerApplicationCommand(EXPO, COMMAND.sessionId, OwnerApplicationType.PREREGISTER, "2")
        private val STATUS_COMMAND = GetOwnerCancellationStatusCommand(EXPO, COMMAND.sessionId, 2)
        private val PARTY =
            OwnerApplicationParty(
                listOf(ManagedOwnerParticipant(1, "Owner", null, true), ManagedOwnerParticipant(2, "Companion", null, false)),
            )
        private val APPLICATION =
            OwnerApplicationItem(
                "2",
                OwnerApplicationType.PREREGISTER,
                2,
                7,
                "CONFIRMED",
                true,
                null,
                null,
                listOf(OwnerApplicationHistory("CONFIRMED")),
            )
        private val OBSERVATION = AttendanceCancellationStatusGateway.Status(2, false, false, false, true, 502, "NONE")
    }
}
