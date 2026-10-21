package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.OwnerApplicationParty
import team.startup.application.domain.application.presentation.dto.PreregisterSessionDefinition
import team.startup.application.domain.application.presentation.dto.targeted
import team.startup.application.domain.application.repository.PreregisterReservationRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import java.time.Clock
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

/** 잠금 밖에서 읽은 원격 값(Expo 회차 정의, 현재 사전등록 폼). */
data class CompanionObservation(
    val definition: PreregisterSessionDefinition?,
    /** 현재 폼 동행자 문항의 최대 인원. 동행자 문항이 없으면 null. */
    val companionLimit: Int?,
    val registrationOpen: Boolean,
    /** 현재 사전등록 폼 ID. 다른 회차 추가(#199)는 신청 때 폼과 같을 때만 답변을 다시 쓴다. */
    val formId: String? = null,
    val occupationOpenAt: OffsetDateTime? = null,
)

/** 동행자 추가 가능 여부. 막히면 [blockedReason]과 추가 API가 돌려줄 상태·메시지를 함께 둔다. */
data class CompanionAvailability(
    val remaining: Int,
    val blockedReason: String,
    val status: HttpStatus = HttpStatus.CONFLICT,
    val message: String = "",
    val sessionId: Long = 0,
    val representativeId: Long = 0,
    val snapshot: ParticipantRegistration? = null,
    val companionTitle: String? = null,
    /** 지금 바로 확정할 수 있는 좌석 수. 대기 중인 일행이 있으면 앞지르지 않도록 0이다. */
    val seats: Int = 0,
    /** 자리가 모자랄 때 대기로 받을 수 있는 인원(#144 승급 대상). 대기 접수가 꺼져 있거나 회차가 시작됐으면 0이다. */
    val waitRoom: Int = 0,
    /** 판정에 쓴 회차 정의. 대상별 정원 회차(#173)는 추가할 동행자의 구분별로 다시 검사한다. */
    val definition: PreregisterSessionDefinition? = null,
    /** 추가할 수 있지만 [remaining]보다 많이 보냈을 때의 거절 code. [message]와 같은 원인이다. */
    val overflowReason: String = "",
) {
    /** [count]명을 지금 확정할지(false) 대기로 받을지(true). 둘 다 안 되면 null이다. */
    fun waits(count: Int): Boolean? =
        when {
            count <= seats -> false
            count <= waitRoom -> true
            else -> null
        }

    val addable: Boolean get() = blockedReason.isEmpty()

    fun require(): CompanionAvailability = if (addable) this else throw OwnerApplicationRejectedException(status, blockedReason, message)
}

/**
 * 내 신청 화면이 문구를 고를 code를 싣는 거절(#271). 응답 본문은 `{status, code}`이고 [code]는 조회의
 * `companionAddBlockedReason`과 같은 이름을 쓴다. [message]는 로그에만 남는다.
 */
class OwnerApplicationRejectedException(
    status: HttpStatusCode,
    val code: String,
    message: String,
) : ResponseStatusException(status, message)

/**
 * 확정된 사전등록 대표자 일행에 동행자를 더할 수 있는지(#169). 내 신청 조회와 추가 API가 같은 규칙을 쓴다.
 * 좌석·대기열·진행 중 추가는 회차 정원과 같은 박람회 잠금 안에서 판단해야 하므로 [evaluate]는 잠금 안에서 부른다.
 */
@Component
class PreregisterCompanionPolicy(
    private val gateway: RegistrationGateway,
    private val ledger: PreregisterSessionLedger,
    private val repository: PreregisterSessionRepository,
    private val reservations: PreregisterReservationRepository,
    private val clock: Clock,
    private val waitlist: PreregisterWaitlist,
    @Value("\${application.attendance.enabled:false}") private val attendanceEnabled: Boolean,
    @Value("\${application.registration.waitlist-intake-enabled:false}") private val waitlistIntake: Boolean,
) {
    fun observe(
        expoId: UUID,
        sessionId: Long,
    ): CompanionObservation {
        val definition = ledger.observe(expoId, sessionId)
        val form = gateway.form(expoId.toString(), "STANDARD", "PRE", true)
        val limit = form?.dynamicForm?.firstOrNull { it.formType == "COMPANION" }?.let(CompanionAnswers::limit)
        val open =
            form?.let {
                try {
                    val now = clock.instant()
                    !now.isBefore(OffsetDateTime.parse(it.startDate).toInstant()) &&
                        !now.isAfter(OffsetDateTime.parse(it.endDate).toInstant())
                } catch (ex: DateTimeParseException) {
                    throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "폼 날짜 응답이 올바르지 않습니다.", ex)
                }
            } ?: false
        return CompanionObservation(definition, limit, open, form?.id, form?.occupationOpenAt)
    }

    fun evaluate(
        expoId: UUID,
        party: OwnerApplicationParty,
        applicationId: Long,
        observation: CompanionObservation,
        persist: Boolean,
    ): CompanionAvailability {
        val representativeId = party.representativeId
        val (application, owner) =
            repository.owned(expoId, applicationId)
                ?: return blocked("NOT_REPRESENTATIVE_PREREGISTRATION", "신청을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        // 본인이 대표자인 자기 신청 행에만 더한다. 동행자 행이나 다른 일행의 신청은 찾을 수 없는 신청으로 본다.
        if (representativeId == null || owner != representativeId || application.participantId != representativeId) {
            return blocked("NOT_REPRESENTATIVE_PREREGISTRATION", "신청을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }
        if (application.status != "CONFIRMED") return blocked("APPLICATION_STATE_CHANGED", "확정된 사전등록에만 동행자를 추가할 수 있습니다.")
        if (!attendanceEnabled) {
            return blocked("EXTERNAL_CONTRACT_REQUIRED", "참여 서비스 회차 통지를 활성화해야 합니다.", HttpStatus.SERVICE_UNAVAILABLE)
        }
        val definition =
            try {
                ledger.current(expoId, application.sessionId, observation.definition, persist)
            } catch (ex: ResponseStatusException) {
                return blocked("SESSION_CHANGE_PENDING", ex.reason ?: "회차 변경 확인이 필요합니다.", HttpStatus.valueOf(ex.statusCode.value()))
            }
        if (definition.closed) return blocked("SESSION_CLOSED", "마감된 회차입니다.")
        if (!clock.instant().isBefore(definition.startedAt)) return blocked("SESSION_STARTED", "이미 시작된 회차입니다.")
        if (!observation.registrationOpen) return blocked("REGISTRATION_PERIOD_ENDED", "사전 등록 기간이 아닙니다.")
        val limit = observation.companionLimit ?: return blocked("COMPANION_NOT_ALLOWED", "동행자를 받지 않는 사전등록입니다.")
        val snapshot = reservations.registrationOf(expoId, applicationId)
        val title = snapshot?.questions?.firstOrNull { it.formType == "COMPANION" }?.title
        if (snapshot == null || title == null) return blocked("SNAPSHOT_UNAVAILABLE", "동행자를 추가할 수 없는 신청입니다.")
        if (reservations.companionAdditionInProgress(expoId, representativeId)) {
            return blocked("ADDITION_IN_PROGRESS", "처리 중인 동행자 추가가 있습니다.")
        }
        // #262: 일행(대표자 포함)은 현재 5명까지다. 모든 신청을 취소한 동행자는 이력과 ID를 남긴 채 빼고, 추가 저장 때 User에서 해제한다.
        val active = reservations.activePartySize(expoId, representativeId, party.participants.map { it.participantId })
        val slots = minOf(limit - (active - 1), CompanionAnswers.MAX_PARTY - active)
        if (slots <= 0) {
            return blocked("COMPANION_LIMIT_REACHED", "동행자는 최대 ${minOf(limit, CompanionAnswers.MAX_PARTY - 1)}명까지 입력할 수 있습니다.")
        }
        // 대기 일행이 있으면 새 인원이 대기열을 앞지르지 않는다. 자리가 모자라면 대기열 뒤에 서서 #144 규칙으로 승급한다.
        val queued = reservations.waitingApplicants(expoId, application.sessionId)
        val seats =
            if (queued > 0) {
                0
            } else {
                (
                    definition.capacity.toLong() -
                        repository.occupied(
                            expoId,
                            application.sessionId,
                        )
                ).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            }
        // 대상별 정원 회차(#173)는 대기를 쓰지 않는다.
        val waitRoom =
            if (waitlistIntake && waitlist.accepts(definition) && !definition.targeted) {
                ((definition.waitingCapacity ?: 0).toLong() - queued).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            } else {
                0
            }
        val room = maxOf(seats, waitRoom)
        if (room <= 0) return blocked("SEATS_UNAVAILABLE", "일행 전원을 수용할 수 없습니다.")
        return CompanionAvailability(
            minOf(slots, room),
            "",
            overflowReason = if (slots <= room) "COMPANION_LIMIT_REACHED" else "SEATS_UNAVAILABLE",
            message =
                if (slots <= room) {
                    "동행자는 최대 ${minOf(limit, CompanionAnswers.MAX_PARTY - 1)}명까지 입력할 수 있습니다."
                } else {
                    "일행 전원을 수용할 수 없습니다."
                },
            sessionId = application.sessionId,
            representativeId = representativeId,
            snapshot = snapshot,
            companionTitle = title,
            seats = seats,
            waitRoom = waitRoom,
            definition = definition,
        )
    }

    private fun blocked(
        reason: String,
        message: String,
        status: HttpStatus = HttpStatus.CONFLICT,
    ) = CompanionAvailability(0, reason, status, message)
}
