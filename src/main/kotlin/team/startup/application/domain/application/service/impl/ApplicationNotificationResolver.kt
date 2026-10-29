package team.startup.application.domain.application.service.impl

import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.stereotype.Component
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.repository.ApplicationNotificationRepository.Row
import team.startup.application.domain.application.repository.PreregisterReservation
import team.startup.application.domain.application.repository.PreregisterReservationRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.repository.ProgramWaitlistRepository
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// Notification `notification.sms.requested`의 신청 결과 항목(Notification #110 `application-result.schema.ts`와 같은 이름).
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApplicationNotificationItem(
    val participantName: String,
    val title: String,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val location: String? = null,
    val result: String? = null,
)

sealed interface ResolvedNotification {
    val row: Row

    data class Ready(
        override val row: Row,
        // 대표자 번호. 학생 개인 번호로 대신하지 않는다.
        val phoneNumber: String,
        val senderType: String,
        val item: ApplicationNotificationItem,
        val scope: String? = null,
    ) : ResolvedNotification

    // 보내기 직전 상태가 바뀌었거나 보낼 수 없는 값이면 보내지 않고 끝낸다(지연된 확정 알림이 취소 뒤에 나가지 않게).
    data class Skipped(
        override val row: Row,
        val reason: String,
    ) : ResolvedNotification
}

/**
 * 보내기 직전의 현재 상태로 outbox 행을 검증하고 문자에 넣을 값을 만든다. 외부 조회 실패는 던져서 나중에 다시 시도한다.
 * 확정 안내는 아직 확정이고, 대기 안내는 아직 대기이며, 취소·만료 안내는 실제로 취소된 신청에만 나간다.
 */
@Component
class ApplicationNotificationResolver(
    private val source: ApplicationNoticeSource,
    private val programs: ProgramWaitlistRepository,
    private val preregister: PreregisterSessionRepository,
    private val reservations: PreregisterReservationRepository,
) {
    fun resolveBatch(rows: List<Row>): List<ResolvedNotification> {
        val batchResolver = ApplicationNotificationResolver(source.forBatch(), programs, preregister, reservations)
        return rows.map(batchResolver::resolve)
    }

    fun resolve(row: Row): ResolvedNotification =
        when {
            row.applicationId != null -> program(row)
            row.preregisterApplicationId != null -> preregisterApplication(row)
            row.requestId != null && row.result == "WAITLISTED" -> waitlistedAddition(row)
            row.requestId != null -> withdrawnReservation(row)
            else -> skip(row, "NO_SUBJECT")
        }

    private fun program(row: Row): ResolvedNotification {
        val entry = programs.entry(requireNotNull(row.applicationId)) ?: return skip(row, "APPLICATION_MISSING")
        val required =
            when (row.type) {
                "PROGRAM_CONFIRMED" -> "CONFIRMED"
                "PROGRAM_WAITLISTED" -> "WAITING"
                else -> "CANCELLED"
            }
        if (entry.status != required) return skip(row, "STATE_CHANGED")
        val contact = source.contact(row.expoId, entry.participantId)
        val phone = phone(contact.representativePhone) ?: return skip(row, "NO_REPRESENTATIVE_PHONE")
        val notice = source.programNotice(row.expoId, entry.key.programType, entry.key.programId)
        val name = label(contact.name, 100)
        val title = label(notice.title, 100)
        if (name == null || title == null) return skip(row, "UNSENDABLE_TEXT")
        return ResolvedNotification.Ready(
            row,
            phone,
            if (entry.key.programType == ProgramSessionType.TRAINING) "TRAINEE" else "STANDARD",
            ApplicationNotificationItem(name, title, notice.startedAt, notice.endedAt, notice.location?.let { label(it, 255) }),
        )
    }

    private fun preregisterApplication(row: Row): ResolvedNotification {
        val app = preregister.application(requireNotNull(row.preregisterApplicationId)) ?: return skip(row, "APPLICATION_MISSING")
        val required = if (row.type == "PREREGISTER_CANCELLED") "CANCELLED" else "CONFIRMED"
        if (app.status != required) return skip(row, "STATE_CHANGED")
        val contact = source.contact(app.expoId, app.participantId)
        val phone = phone(contact.representativePhone) ?: return skip(row, "NO_REPRESENTATIVE_PHONE")
        val session = session(row, app.sessionId, label(contact.name, 100), result = row.result) ?: return skip(row, "UNSENDABLE_TEXT")
        // 일행에 다른 활성 신청이 남아 있으면 일부 취소다. 전체 취소처럼 안내하지 않는다.
        val scope =
            if (row.type == "PREREGISTER_CANCELLED") {
                if (preregister.activeUnderRepresentative(app.expoId, app.representativeId) == 0) "ALL" else "PARTIAL"
            } else {
                null
            }
        return ResolvedNotification.Ready(row, phone, "STANDARD", session, scope)
    }

    // 대기로 접수한 회차 추가. 그사이 승급·철회되었으면 대기 안내가 사실과 달라 보내지 않는다.
    private fun waitlistedAddition(row: Row): ResolvedNotification {
        val reservation = reservations.find(row.expoId, row.requestId, "") ?: return skip(row, "RESERVATION_MISSING")
        if (reservation.state != "WAITING" || reservation.withdrawn) return skip(row, "STATE_CHANGED")
        val contact = source.contact(row.expoId, requireNotNull(row.participantId))
        val phone = phone(contact.representativePhone) ?: return skip(row, "NO_REPRESENTATIVE_PHONE")
        val item =
            session(row, reservation.sessionId, label(contact.name, 100), result = "WAITLISTED") ?: return skip(row, "UNSENDABLE_TEXT")
        return ResolvedNotification.Ready(row, phone, "STANDARD", item)
    }

    // 철회된 대기 일행(User에 저장되기 전)의 취소·만료. 이름은 접수한 대표자와 인원수로 보여 준다.
    private fun withdrawnReservation(row: Row): ResolvedNotification {
        val reservation = reservations.find(row.expoId, row.requestId, "") ?: return skip(row, "RESERVATION_MISSING")
        if (!reservation.withdrawn) return skip(row, "STATE_CHANGED")
        val phone = phone(reservation.userRequest.phoneNumber.filter { it in '0'..'9' }) ?: return skip(row, "NO_REPRESENTATIVE_PHONE")
        val name = label(partyName(reservation), 100) ?: return skip(row, "UNSENDABLE_TEXT")
        val item = session(row, reservation.sessionId, name, result = null) ?: return skip(row, "UNSENDABLE_TEXT")
        val scope =
            if (row.type == "PREREGISTER_CANCELLED") {
                if (reservation.sessionAddition || reservation.companionOf != null) "PARTIAL" else "ALL"
            } else {
                null
            }
        return ResolvedNotification.Ready(row, phone, "STANDARD", item, scope)
    }

    private fun partyName(reservation: PreregisterReservation): String =
        reservation.userRequest.name + if (reservation.applicants > 1) " 외 ${reservation.applicants - 1}명" else ""

    private fun session(
        row: Row,
        sessionId: Long,
        name: String?,
        result: String?,
    ): ApplicationNotificationItem? {
        val definition = preregister.definition(row.expoId, sessionId)
        val title = definition?.title?.let { label(it, 100) }
        if (name == null || definition == null || title == null) return null
        return ApplicationNotificationItem(
            name,
            title,
            kst(definition.startedAt),
            kst(definition.endedAt),
            label(definition.place, 255),
            result,
        )
    }

    private fun skip(
        row: Row,
        reason: String,
    ): ResolvedNotification = ResolvedNotification.Skipped(row, reason)

    private companion object {
        val KST: ZoneOffset = ZoneOffset.ofHours(9)
        val PHONE = Regex("01[0-9]{8,9}")

        fun kst(instant: Instant): String = instant.atOffset(KST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        fun phone(value: String?): String? = value?.takeIf { PHONE.matches(it) }

        // Notification 라벨 규칙(앞뒤 공백 제거, 제어·줄바꿈 문자 없음, 최대 길이)에 맞춘다. 줄바꿈은 공백이 된다.
        val BREAKS = Regex("""[\p{Cc}\p{Zl}\p{Zp}]+""")
        val HIDDEN = Regex("""\p{C}""")

        fun label(
            value: String,
            max: Int,
        ): String? =
            value
                .replace(BREAKS, " ")
                .replace(HIDDEN, "")
                .trim()
                .takeIf { it.isNotEmpty() && it.length <= max }
    }
}
