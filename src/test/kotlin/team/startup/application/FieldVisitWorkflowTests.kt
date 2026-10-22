package team.startup.application

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.FieldParticipantResolution
import team.startup.application.domain.application.presentation.dto.FieldVisitCommand
import team.startup.application.domain.application.presentation.dto.FieldVisitReceipt
import team.startup.application.domain.application.presentation.dto.FieldVisitRegistration
import team.startup.application.domain.application.presentation.dto.ManagedOwnerParticipant
import team.startup.application.domain.application.presentation.dto.NewFieldParticipant
import team.startup.application.domain.application.presentation.dto.OwnerApplicationParty
import team.startup.application.domain.application.presentation.dto.RegisterFieldVisitRequest
import team.startup.application.domain.application.repository.FieldVisitRegistrationRepository
import team.startup.application.domain.application.repository.FieldVisitRequestRepository
import team.startup.application.domain.application.repository.StoredFieldVisitRequest
import team.startup.application.domain.application.service.impl.ExpoPeriod
import team.startup.application.domain.application.service.impl.RegisterFieldVisitServiceImpl
import team.startup.application.domain.application.service.impl.RegistrationGateway
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals

class FieldVisitWorkflowTests {
    private val gateway = mock(RegistrationGateway::class.java)
    private val visits = mock(FieldVisitRegistrationRepository::class.java)
    private val requests = mock(FieldVisitRequestRepository::class.java)
    private val expoId = UUID.randomUUID()
    private val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
    private val phone = "01000000000"
    private val ownerHash = MessageDigest.getInstance("SHA-256").digest(phone.toByteArray()).joinToString("") { "%02x".format(it) }
    private val transactionManager =
        object : PlatformTransactionManager {
            override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()

            override fun commit(status: TransactionStatus) {}

            override fun rollback(status: TransactionStatus) {}
        }
    private val workflow = RegisterFieldVisitServiceImpl(gateway, visits, requests, TransactionTemplate(transactionManager), true, true)

    @Test
    fun `혼합 일행은 오늘 PRE와 FIELD를 재사용하고 다른날과 신규 사람만 생성한다`() {
        val request = RegisterFieldVisitRequest(listOf(1, 2, 3), listOf(NewFieldParticipant("new", "신규", "GENERAL", null, "GWANGJU")), true)
        prepare(request)
        `when`(gateway.resolveFieldParticipants(expoId, phone, "key", request)).thenReturn(listOf(FieldParticipantResolution(4, "new")))
        `when`(visits.preregistered(expoId, 1, today)).thenReturn(true)
        val second = visit(2, false)
        `when`(visits.find(expoId, 2, today)).thenReturn(second)
        `when`(visits.lock(second.id)).thenReturn(second)
        for (id in 2L..4L) {
            val registration = if (id == 2L) second else visit(id, true)
            `when`(visits.activate(expoId, id, today, UUID.nameUUIDFromBytes("$expoId:key".toByteArray()))).thenReturn(registration)
            `when`(visits.findById(registration.id)).thenReturn(registration.copy(deliveryPending = false))
        }
        val result = workflow.execute(FieldVisitCommand(expoId, "session", "key", request))
        assertEquals(listOf("REUSED_PRE", "REUSED_FIELD", "REGISTERED_FIELD", "REGISTERED_FIELD"), result.participants.map { it.outcome })
        assertEquals(listOf(1L, 2L, 3L, 4L), result.participants.map { it.participantId })
        assertEquals("new", result.participants.last().clientReference)
        verify(gateway, never()).projectFieldVisit(second)
    }

    @Test
    fun `5명 기존 참가자 날짜 추가는 신규 생성없이 처리하고 소유하지 않은 ID는 거절한다`() {
        val request = RegisterFieldVisitRequest((1L..5L).toList(), emptyList(), true)
        prepare(request)
        `when`(gateway.resolveFieldParticipants(expoId, phone, "key", request)).thenReturn(emptyList())
        request.participantIds.forEach { id -> `when`(visits.preregistered(expoId, id, today)).thenReturn(true) }
        assertEquals(5, workflow.execute(FieldVisitCommand(expoId, "session", "key", request)).participants.size)
        val invalid = request.copy(participantIds = listOf(99))
        assertEquals(
            404,
            assertThrows<ResponseStatusException> {
                workflow.execute(FieldVisitCommand(expoId, "session", "other", invalid))
            }.statusCode.value(),
        )
    }

    @Test
    fun `기존 활성 5명과 신규 1명의 합집합은 날짜수가 아니라 사람수로 거절한다`() {
        val request = RegisterFieldVisitRequest(listOf(1), listOf(NewFieldParticipant("sixth", "신규", "GENERAL", null, "GWANGJU")), true)
        prepare(request)
        val partyIds = (1L..5L).toList()
        `when`(visits.activeParticipantIds(expoId, partyIds + request.participantIds)).thenReturn(partyIds.toSet())
        `when`(requests.reserve(expoId, "key", ownerHash, phone, today, request, 1, partyIds))
            .thenReturn(
                StoredFieldVisitRequest(today, request, phone, listOf(FieldParticipantResolution(6, "sixth")), null, partyIds = partyIds),
            )
        `when`(visits.activeParticipantIds(expoId, partyIds + listOf(1L, 6L))).thenReturn(partyIds.toSet())
        assertEquals(
            409,
            assertThrows<ResponseStatusException> {
                workflow.execute(FieldVisitCommand(expoId, "session", "key", request))
            }.statusCode.value(),
        )
    }

    @Test
    fun `취소 전달 pending 중에는 새로운 활성화가 없고 이전 완료 응답은 재현한다`() {
        val request = RegisterFieldVisitRequest(listOf(1), emptyList(), true)
        prepare(request)
        `when`(gateway.resolveFieldParticipants(expoId, phone, "key", request)).thenReturn(emptyList())
        val pending = visit(1, false).copy(cancellationPending = true)
        `when`(visits.find(expoId, 1, today)).thenReturn(pending)
        `when`(visits.lock(pending.id)).thenReturn(pending)
        assertEquals(
            409,
            assertThrows<ResponseStatusException> {
                workflow.execute(FieldVisitCommand(expoId, "session", "key", request))
            }.statusCode.value(),
        )
        val original = FieldVisitReceipt(today.minusDays(1), emptyList())
        `when`(
            requests.reserve(expoId, "key", ownerHash, phone, today, request, 1, (1L..5L).toList()),
        ).thenReturn(StoredFieldVisitRequest(today.minusDays(1), request, phone, emptyList(), original))
        assertEquals(original, workflow.execute(FieldVisitCommand(expoId, "session", "key", request)))
    }

    private fun prepare(request: RegisterFieldVisitRequest) {
        `when`(gateway.participantSessionPhone(expoId.toString(), "session")).thenReturn(phone)
        `when`(gateway.managedParty(expoId, "session")).thenReturn(
            OwnerApplicationParty(
                (1L..5L).map {
                    ManagedOwnerParticipant(
                        it,
                        "기존",
                        "GENERAL",
                        it == 1L,
                    )
                },
            ),
        )
        `when`(gateway.expoPeriod(expoId.toString())).thenReturn(ExpoPeriod(today.toString(), today.toString()))
        doAnswer { it.getArgument<() -> FieldVisitReceipt>(2).invoke() }.`when`(requests).withLock(
            org.mockito.ArgumentMatchers.eq(expoId) ?: expoId,
            org.mockito.ArgumentMatchers.eq(ownerHash) ?: ownerHash,
            any<() -> FieldVisitReceipt>() ?: { error("matcher") },
        )
        `when`(
            requests.reserve(expoId, "key", ownerHash, phone, today, request, 1, (1L..5L).toList()),
        ).thenReturn(StoredFieldVisitRequest(today, request, phone, null, null))
    }

    private fun visit(
        participantId: Long,
        pending: Boolean,
    ) = FieldVisitRegistration(UUID.randomUUID(), expoId, participantId, today, "ACTIVE", 1, Instant.now(), null, null, false, pending)
}
