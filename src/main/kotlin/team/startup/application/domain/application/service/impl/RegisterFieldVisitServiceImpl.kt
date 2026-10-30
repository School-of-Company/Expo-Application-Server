package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.FieldVisitCommand
import team.startup.application.domain.application.presentation.dto.FieldVisitParticipantResult
import team.startup.application.domain.application.presentation.dto.FieldVisitReceipt
import team.startup.application.domain.application.repository.FieldVisitRegistrationRepository
import team.startup.application.domain.application.repository.FieldVisitRequestRepository
import team.startup.application.domain.application.repository.StoredFieldVisitRequest
import team.startup.application.domain.application.service.RegisterFieldVisitService
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Service
class RegisterFieldVisitServiceImpl(
    private val gateway: RegistrationGateway,
    private val visits: FieldVisitRegistrationRepository,
    private val requests: FieldVisitRequestRepository,
    private val transactions: TransactionTemplate,
    @Value("\${application.registration.enabled:false}") private val enabled: Boolean,
    @Value("\${application.field-visits.enabled:false}") private val fieldVisitsEnabled: Boolean = false,
    private val ledger: team.startup.application.domain.application.repository.PreregisterSessionRepository? = null,
    private val programs: team.startup.application.domain.application.repository.ProgramSessionApplicationRepository? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val mapper: tools.jackson.databind.ObjectMapper =
        tools.jackson.module.kotlin
            .jacksonObjectMapper(),
) : RegisterFieldVisitService {
    override fun execute(command: FieldVisitCommand): FieldVisitReceipt {
        if (!enabled || !fieldVisitsEnabled) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
        val request = command.request
        if (command.requestId.isBlank() || command.requestId.length > 100 || !request.personalInformationStatus ||
            request.participantIds.size + request.newParticipants.size !in 1..5 ||
            request.participantIds.any { it <= 0 } || request.participantIds.distinct().size != request.participantIds.size ||
            request.newParticipants
                .map { it.clientReference }
                .distinct()
                .size != request.newParticipants.size
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        }
        request.newParticipants.forEach {
            FieldVisitAnswers.assemble(null, it, mapper)
            if (it.occupation !in CompanionAnswers.occupations || it.region !in CompanionAnswers.regions ||
                (it.occupation in setOf("TEACHER", "PRE_SERVICE_TEACHER") && it.school.isNullOrBlank())
            ) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST)
            }
        }
        val phone = gateway.participantSessionPhone(command.expoId.toString(), command.sessionId)
        val ownerHash = MessageDigest.getInstance("SHA-256").digest(phone.toByteArray()).joinToString("") { "%02x".format(it) }
        return requests.withLock(command.expoId, ownerHash) {
            val party = gateway.managedParty(command.expoId, command.sessionId, phone)
            if (!party.participants.map { it.participantId }.containsAll(request.participantIds)) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND)
            }
            val expo = gateway.expoPeriod(command.expoId.toString())
            val today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))
            val period =
                try {
                    LocalDate.parse(expo.startedDay) to LocalDate.parse(expo.finishedDay)
                } catch (ex: java.time.format.DateTimeParseException) {
                    throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "박람회 날짜 응답이 올바르지 않습니다.", ex)
                }
            if (!requests.exists(command.expoId, command.requestId) &&
                (today.isBefore(period.first) || today.isAfter(period.second))
            ) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "박람회 등록 기간이 아닙니다.")
            }
            if (!requests.exists(command.expoId, command.requestId)) {
                transactions.execute {
                    ledger?.lock(command.expoId)
                    val candidates = party.participants.map { it.participantId } + request.participantIds
                    candidates.distinct().sorted().forEach { programs?.lockParticipant(it) }
                    val active = visits.activeParticipantIds(command.expoId, candidates) + request.participantIds
                    if (active.size + request.newParticipants.size > 5) {
                        throw ResponseStatusException(HttpStatus.CONFLICT, "PARTY_LIMIT_EXCEEDED")
                    }
                }
            }
            val form =
                if (request.newParticipants.isEmpty() || requests.exists(command.expoId, command.requestId)) {
                    null
                } else {
                    // #333: 요청 저장과 User 생성 전에 제출 당시 FIELD 폼으로 답변을 검증한다. 같은 키 재시도는 처음 snapshot을 쓴다.
                    gateway.form(command.expoId.toString(), "STANDARD", "FIELD", false).also { form ->
                        request.newParticipants.forEach { FieldVisitAnswers.assemble(form, it, mapper) }
                    }
                }
            val stored =
                requests.reserve(
                    command.expoId,
                    command.requestId,
                    ownerHash,
                    phone,
                    today,
                    request,
                    party.representativeId,
                    party.participants.map { it.participantId },
                    form,
                )
            finish(command.expoId, command.requestId, stored)
        }
    }

    fun recover(
        expoId: UUID,
        requestId: String,
        ownerHash: String,
    ) {
        requests.tryWithLock(expoId, ownerHash) { finish(expoId, requestId, requests.recoverRequest(expoId, requestId, ownerHash)) }
    }

    private fun finish(
        expoId: UUID,
        requestId: String,
        stored: StoredFieldVisitRequest,
    ): FieldVisitReceipt {
        stored.receipt?.let { return it }
        val request = stored.request
        val resolved =
            stored.resolved ?: gateway.resolveFieldParticipants(expoId, stored.phoneNumber, requestId, request, stored.form).also {
                if (it.any { person -> person.participantId in stored.partyIds }) throw ParticipantIdsMismatchException()
                requests.resolved(expoId, requestId, it)
            }
        val ids = request.participantIds + resolved.map { it.participantId }
        val results =
            stored.plannedReceipt?.participants ?: requireNotNull(
                transactions.execute {
                    ledger?.lock(expoId)
                    if (ledger?.expoDeleted(expoId) == true) throw ResponseStatusException(HttpStatus.CONFLICT, "EXPO_DELETED")
                    (ids + listOfNotNull(stored.representativeId)).distinct().sorted().forEach { programs?.lockParticipant(it) }
                    if ((visits.activeParticipantIds(expoId, stored.partyIds + ids) + ids).size > 5) {
                        throw ResponseStatusException(HttpStatus.CONFLICT, "PARTY_LIMIT_EXCEEDED")
                    }
                    stored.representativeId?.let {
                        if (visits.find(expoId, it, stored.visitDate)?.cancellationPending == true) {
                            throw ResponseStatusException(HttpStatus.CONFLICT, "FIELD_CANCELLATION_PENDING")
                        }
                    }
                    ids
                        .sorted()
                        .map { participantId ->
                            val current = visits.find(expoId, participantId, stored.visitDate)?.let { visits.lock(it.id) }
                            if (current?.cancellationPending ==
                                true
                            ) {
                                throw ResponseStatusException(HttpStatus.CONFLICT, "FIELD_CANCELLATION_PENDING")
                            }
                            val reference = resolved.singleOrNull { it.participantId == participantId }?.clientReference
                            if (visits.preregistered(expoId, participantId, stored.visitDate)) {
                                FieldVisitParticipantResult(participantId, reference, "REUSED_PRE")
                            } else {
                                val visit = visits.activate(expoId, participantId, stored.visitDate, operationId(expoId, requestId))
                                FieldVisitParticipantResult(
                                    participantId,
                                    reference,
                                    if (current?.status ==
                                        "ACTIVE"
                                    ) {
                                        "REUSED_FIELD"
                                    } else {
                                        "REGISTERED_FIELD"
                                    },
                                    visit,
                                )
                            }
                        }.also { requests.plan(expoId, requestId, FieldVisitReceipt(stored.visitDate, it)) }
                },
            )
        results.mapNotNull { it.registration }.filter { it.deliveryPending }.forEach {
            gateway.projectFieldVisit(it)
            visits.delivered(it.id, it.generation)
        }
        ids.forEach { gateway.countStandard(expoId.toString(), it) }
        val receipt =
            FieldVisitReceipt(
                stored.visitDate,
                results.map {
                    it.copy(
                        registration =
                            it.registration?.let { visit ->
                                visits.findById(visit.id)
                            },
                    )
                },
            )
        requests.complete(expoId, requestId, receipt)
        return receipt
    }

    private fun operationId(
        expoId: UUID,
        requestId: String,
    ): UUID = UUID.nameUUIDFromBytes("$expoId:$requestId".toByteArray())
}
