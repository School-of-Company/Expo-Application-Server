package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.ApplicationActorType
import team.startup.application.domain.application.repository.ApplicationNotificationRepository
import java.util.UUID

/**
 * #308: 신청 결과 문자(Notification #110)를 상태 변경과 같은 트랜잭션에서 outbox에 쌓는다.
 * 꺼져 있거나 허용 박람회가 아니면 아무것도 쌓지 않아 과거 이력이 나중에 발송 대상이 되지 않는다.
 * 문자 실패는 이 행의 발행에만 영향을 주고 신청·취소를 되돌리지 않는다. 실제 상태 전이의 성공 지점에서만 부른다.
 */
@Component
class ApplicationNotifications(
    private val repository: ApplicationNotificationRepository,
    @Value("\${application.notifications.enabled:false}") private val enabled: Boolean,
    @Value("\${application.notifications.expo-ids:}") expoIds: List<String>,
) {
    private val allowed: Set<UUID> = expoIds.filter { it.isNotBlank() }.map { UUID.fromString(it.trim()) }.toSet()

    private fun on(expoId: UUID): Boolean = enabled && (allowed.isEmpty() || expoId in allowed)

    // 새 신청 결과. 승급(WAITING→CONFIRMED)은 기존 승급 문자이므로 부르지 않는다.
    @Transactional(propagation = Propagation.MANDATORY)
    fun programAdmitted(
        expoId: UUID,
        applicationId: UUID,
        status: String,
    ) {
        if (!on(expoId)) return
        val type = if (status == "WAITING") "PROGRAM_WAITLISTED" else "PROGRAM_CONFIRMED"
        repository.record("$type:$applicationId", type, expoId, applicationId = applicationId)
    }

    // 소유자·관리자 취소만. 시스템·내부(UNKNOWN) 취소와 연동 취소, 참여 제안 거절은 부르지 않는다.
    @Transactional(propagation = Propagation.MANDATORY)
    fun programCancelled(
        expoId: UUID,
        applicationId: UUID,
        actorType: ApplicationActorType,
    ) {
        val by = cancelledBy(actorType)
        if (by == null || !on(expoId)) return
        repository.record("PROGRAM_CANCELLED:$applicationId", "PROGRAM_CANCELLED", expoId, applicationId = applicationId, cancelledBy = by)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun preregisterCancelled(
        expoId: UUID,
        applicationId: Long,
        sessionId: Long,
        participantId: Long,
        actorType: ApplicationActorType,
    ) {
        val by = cancelledBy(actorType)
        if (by == null || !on(expoId)) return
        repository.record(
            "PREREGISTER_CANCELLED:$applicationId",
            "PREREGISTER_CANCELLED",
            expoId,
            preregisterApplicationId = applicationId,
            sessionId = sessionId,
            participantId = participantId,
            cancelledBy = by,
        )
    }

    // 대기 중인 사전등록 일행의 철회. 일행은 User에 저장되기 전이라 예약으로 안내한다.
    @Transactional(propagation = Propagation.MANDATORY)
    fun reservationWithdrawn(
        expoId: UUID,
        requestId: String,
        sessionId: Long,
        actorType: ApplicationActorType,
    ) {
        val by = cancelledBy(actorType)
        if (by == null || !on(expoId)) return
        repository.record(
            "PREREGISTER_CANCELLED:RESERVATION:$requestId",
            "PREREGISTER_CANCELLED",
            expoId,
            requestId = requestId,
            sessionId = sessionId,
            cancelledBy = by,
        )
    }

    // 다른 회차 추가의 최종 결과. 예약 중간 상태(RESERVED)가 아니라 확정 또는 대기 접수가 정해진 지점에서 부른다.
    @Transactional(propagation = Propagation.MANDATORY)
    fun sessionAdded(
        expoId: UUID,
        requestId: String,
        sessionId: Long,
        participantId: Long,
        result: String,
        applicationId: Long? = null,
    ) {
        if (!on(expoId)) return
        repository.record(
            "PREREGISTER_SESSION_ADDED:$requestId:$participantId",
            "PREREGISTER_SESSION_ADDED",
            expoId,
            preregisterApplicationId = applicationId,
            requestId = requestId,
            sessionId = sessionId,
            participantId = participantId,
            result = result,
        )
    }

    // 실제 EXPIRED 전이에서만 부른다. 참여 제안이 VOIDED로 끝난 경우(마감 상한·대상 소멸)는 만료가 아니다.
    @Transactional(propagation = Propagation.MANDATORY)
    fun programOfferExpired(
        expoId: UUID,
        applicationId: UUID,
    ) {
        if (!on(expoId)) return
        repository.record("OFFER_EXPIRED:PROGRAM:$applicationId", "OFFER_EXPIRED", expoId, applicationId = applicationId)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun reservationOfferExpired(
        expoId: UUID,
        requestId: String,
        sessionId: Long,
    ) {
        if (!on(expoId)) return
        repository.record(
            "OFFER_EXPIRED:RESERVATION:$requestId",
            "OFFER_EXPIRED",
            expoId,
            requestId = requestId,
            sessionId = sessionId,
        )
    }

    private fun cancelledBy(actorType: ApplicationActorType): String? =
        when (actorType) {
            ApplicationActorType.USER -> "USER"
            ApplicationActorType.ADMIN -> "ADMIN"
            else -> null
        }
}
