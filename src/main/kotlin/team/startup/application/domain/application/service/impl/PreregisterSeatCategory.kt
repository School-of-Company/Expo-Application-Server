package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AdminPreregisterSeatUsage
import team.startup.application.domain.application.presentation.dto.PreregisterSessionDefinition
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class OccupationNotOpenException(
    openAt: OffsetDateTime,
) : ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "교원·예비교사 외 유형은 ${openAt.atZoneSameInstant(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}" +
            "(KST)부터 신청할 수 있습니다.",
    )

/** #173 사전등록 좌석 구분. 직업 코드로만 판정하며 표시 문구는 보지 않는다. 직업이 없으면 null(미상)이다. */
internal object PreregisterSeatCategory {
    const val TEACHER = "TEACHER"
    const val OTHER = "OTHER"

    fun of(occupation: String?): String? =
        when (occupation) {
            null -> null
            "TEACHER", "PRE_SERVICE_TEACHER" -> TEACHER
            else -> OTHER
        }

    /**
     * #209: [openAt] 전에는 교원·예비교사만 새로 신청한다. 직업을 모르는 사람도 막는다. null이면 제한 없다.
     * 일반 신청·동행자 추가·다른 회차 추가가 같이 쓰며, 기존 예약의 멱등·복구 뒤에 부른다.
     */
    fun requireOpen(
        openAt: OffsetDateTime?,
        now: Instant,
        occupations: List<String?>,
    ) {
        if (openAt != null && now.isBefore(openAt.toInstant()) && occupations.any { of(it) != TEACHER }) {
            throw OccupationNotOpenException(openAt)
        }
    }

    // Mirrors RegistrationWorkflow: the representative plus distinct (name, occupation, school) companions form `applicants`.
    fun partyOccupations(
        registration: ParticipantRegistration,
        mapper: ObjectMapper,
    ): List<String?> {
        val answers = mapper.readTree(registration.informationJson)
        val companions =
            registration.questions
                .orEmpty()
                .filter { it.formType == "COMPANION" }
                .flatMap { answers.get(it.title)?.toList().orEmpty() }
                .map {
                    listOf(
                        it.path("name").asString().trim(),
                        it.path("occupation").asString(),
                        it
                            .path("school")
                            .asString()
                            .trim()
                            .takeIf(String::isNotEmpty),
                    )
                }.toSet()
        return listOf(registration.occupation) + companions.map { it[1] }
    }

    /** 스냅샷의 (교사, 그 외) 인원. 나머지는 미상이다. */
    fun counts(
        registration: ParticipantRegistration,
        mapper: ObjectMapper,
    ): Pair<Int, Int> {
        val categories = partyOccupations(registration, mapper).map(::of)
        return categories.count { it == TEACHER } to categories.count { it == OTHER }
    }

    /** (교사, 그 외) 빈 좌석. 미상 좌석은 구분이 밝혀질 때까지 양쪽에서 모두 뺀다. 구분 정원 회차에서만 호출한다. */
    fun remaining(
        definition: PreregisterSessionDefinition,
        usage: AdminPreregisterSeatUsage,
    ): Pair<Long, Long> =
        requireNotNull(definition.teacherCapacity) - usage.teacher - usage.unknown to
            requireNotNull(definition.nonTeacherCapacity) - usage.other - usage.unknown
}
