package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode

/** 사전등록 동행자 답변 규칙. 최초 일행 신청과 동행자 추가(#169)가 같은 검증을 쓴다. */
internal object CompanionAnswers {
    val occupations =
        setOf(
            "KINDERGARTEN_STUDENT",
            "ELEMENTARY_STUDENT",
            "MIDDLE_SCHOOL_STUDENT",
            "HIGH_SCHOOL_STUDENT",
            "SCHOOL_STAFF",
            "PARENT",
            "GENERAL",
            "TEACHER",
            "PRE_SERVICE_TEACHER",
        )
    val regions = setOf("GWANGJU", "JEONNAM", "OTHER")

    /** 회차 참석자 중 이들만 있으면 안 된다(#199 Q1). */
    val students = setOf("KINDERGARTEN_STUDENT", "ELEMENTARY_STUDENT", "MIDDLE_SCHOOL_STUDENT", "HIGH_SCHOOL_STUDENT")

    /** 대표자를 포함한 일행 최대 인원. User도 같은 상한을 누적으로 적용한다. */
    const val MAX_PARTY = 5

    /** 폼 동행자 문항의 `maxSelection`(대표자 제외 동행자 수). 없으면 4, 4보다 크면 4다. */
    fun limit(field: RegistrationField): Int {
        val limit = field.otherJson?.get("maxSelection")
        if (limit != null && (!limit.isIntegralNumber || !limit.canConvertToInt() || limit.intValue() <= 0)) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "폼의 동행자 최대 인원 응답이 올바르지 않습니다.")
        }
        return minOf(limit?.intValue() ?: MAX_PARTY - 1, MAX_PARTY - 1)
    }

    fun validate(companion: JsonNode) {
        if (!companion.isObject) badRequest("동행자 답변은 JSON 객체여야 합니다.")

        fun text(key: String) = companion.get(key)?.takeIf { it.isString }?.stringValue()
        val name = text("name")?.trim()
        if (name.isNullOrEmpty() || name.length > 10) badRequest("동행자 이름은 1~10자여야 합니다.")
        val occupation = text("occupation")
        if (occupation !in occupations) {
            badRequest("동행자 직업이 올바르지 않습니다.")
        }
        if (text("region") !in regions) badRequest("동행자 지역이 올바르지 않습니다.")
        if (occupation in setOf("TEACHER", "PRE_SERVICE_TEACHER")) {
            val school = text("school")?.trim()
            if (school.isNullOrEmpty() || school.length > 100) badRequest("동행자 학교는 1~100자여야 합니다.")
        } else if (companion.has("school")) {
            badRequest("이 직업은 동행자 학교를 받지 않습니다.")
        }
    }

    /** User가 같은 동행자로 보는 기준(이름·구분·소속). */
    fun key(companion: JsonNode): List<String?> =
        listOf(
            companion.path("name").asString().trim(),
            companion.path("occupation").asString(),
            companion
                .path("school")
                .asString()
                .trim()
                .takeIf(String::isNotEmpty),
        )

    private fun badRequest(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
}
