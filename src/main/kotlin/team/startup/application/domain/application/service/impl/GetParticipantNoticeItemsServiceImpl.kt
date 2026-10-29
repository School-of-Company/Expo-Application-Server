package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.ParticipantNoticeItems
import team.startup.application.domain.application.presentation.dto.ParticipantNoticeProgram
import team.startup.application.domain.application.presentation.dto.ParticipantNoticeSession
import team.startup.application.domain.application.presentation.dto.PreregisterSessionDefinition
import team.startup.application.domain.application.repository.ParticipantNoticeRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.service.GetParticipantNoticeItemsService
import team.startup.application.domain.application.time.ExpoProgramTime
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Notification QR 문자(#136)가 참가자별로 읽는 확정 회차와 프로그램. 확정된 것만 담고 이름·전화번호는 담지 않는다.
 * 문구 규칙은 신청 결과 문자 항목(`ApplicationNotificationResolver`)과 같다: 제어 문자는 공백으로 바꾸고 길이를 넘는 제목은 그 항목을 뺀다.
 */
@Service
class GetParticipantNoticeItemsServiceImpl(
    private val items: ParticipantNoticeRepository,
    private val preregister: PreregisterSessionRepository,
) : GetParticipantNoticeItemsService {
    @Transactional(readOnly = true)
    override fun execute(
        expoId: UUID,
        participantIds: List<Long>,
    ): List<ParticipantNoticeItems> {
        if (participantIds.any { it <= 0 } || participantIds.distinct().size != participantIds.size) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        }
        if (preregister.expoDeleted(expoId)) throw ResponseStatusException(HttpStatus.NOT_FOUND)

        val sessions =
            items
                .confirmedSessions(expoId, participantIds)
                .groupBy({ it.participantId }, { it.definition })
        val programs = items.confirmedPrograms(expoId, participantIds).groupBy { it.participantId }
        return participantIds.map { participantId ->
            ParticipantNoticeItems(
                participantId,
                sessions[participantId].orEmpty().sortedWith(compareBy({ it.startedAt }, { it.id })).mapNotNull { session(it) },
                programs[participantId].orEmpty().mapNotNull { program(it) }.sortedWith(compareBy(nullsLast()) { it.startedAt }),
            )
        }
    }

    private fun session(definition: PreregisterSessionDefinition) =
        label(definition.title, 100)?.let {
            ParticipantNoticeSession(it, kst(definition.startedAt), kst(definition.endedAt), label(definition.place, 255))
        }

    private fun program(row: ParticipantNoticeRepository.ProgramRow): ParticipantNoticeProgram? {
        val title = label(row.snapshot.path("title").asString(""), 100) ?: return null
        val started = instant(row.snapshot.path("startedAt").asString(""))
        val ended = instant(row.snapshot.path("endedAt").asString(""))
        val both = started != null && ended != null
        return ParticipantNoticeProgram(
            row.programType,
            title,
            started?.takeIf { both }?.let(::kst),
            ended?.takeIf { both }?.let(::kst),
            label(row.snapshot.path("place").asString(""), 255),
        )
    }

    // 프로그램 정의의 일시는 오프셋 없는 한국 시간 문자열일 수 있다. 읽을 수 없으면 시각 없이 싣는다.
    private fun instant(value: String): Instant? =
        value.takeIf { it.isNotBlank() }?.let { runCatching { ExpoProgramTime.parse(it).toInstant() }.getOrNull() }

    private companion object {
        val KST: ZoneOffset = ZoneOffset.ofHours(9)
        val BREAKS = Regex("""[\p{Cc}\p{Zl}\p{Zp}]+""")
        val HIDDEN = Regex("""\p{C}""")

        fun kst(instant: Instant): String = OffsetDateTime.ofInstant(instant, KST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        // Notification 라벨 규칙(앞뒤 공백 제거, 제어·줄바꿈 문자 없음, 최대 길이)에 맞춘다. 줄바꿈은 공백이 된다.
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
