package team.startup.application.domain.application.service.impl

import team.startup.application.domain.application.presentation.dto.AdminPreregisterSeatUsage
import team.startup.application.domain.application.presentation.dto.PreregisterSessionDefinition
import tools.jackson.databind.ObjectMapper

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
