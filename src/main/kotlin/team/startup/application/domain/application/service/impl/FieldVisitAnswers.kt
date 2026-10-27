package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.NewFieldParticipant
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * 방문일 신규 참가자(#333)의 폼 답변을 검증하고, FIELD 폼의 직업·지역·학교 문항 답이 없으면 그 사람의 프로필 값으로 채운다.
 * 명단·명찰은 프로필을, 엑셀은 문항 답을 읽으므로 둘이 다르면 거절한다. 다른 커스텀 답변은 그대로 둔다.
 * 동행자는 신규 항목으로만 만들어지므로(User는 이 경로에서 동행자 문항을 읽지 않는다) 동행자 문항 답은 받지 않는다.
 */
internal object FieldVisitAnswers {
    fun assemble(
        form: RegistrationForm?,
        person: NewFieldParticipant,
        mapper: ObjectMapper,
    ): String {
        val answers =
            try {
                mapper.readTree(person.informationJson)
            } catch (ex: Exception) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "폼 답변이 올바른 JSON이 아닙니다.", ex)
            }
        if (answers !is ObjectNode) badRequest("폼 답변은 JSON 객체여야 합니다.")
        val fields = form?.dynamicForm.orEmpty()
        var changed = false
        listOf(
            fields.firstOrNull { it.dynamicFormType == "OCCUPATION" } to person.occupation,
            fields.firstOrNull { it.formType == "REGION" } to person.region,
            fields.firstOrNull { it.dynamicFormType == "SCHOOL" } to person.school?.trim()?.takeIf(String::isNotEmpty),
        ).forEach { (field, profile) ->
            if (field == null) return@forEach
            val answer = answers.get(field.title)
            if (answer == null || answer.isNull || (answer.isString && answer.stringValue().isBlank())) {
                if (profile != null) {
                    answers.put(field.title, profile)
                    changed = true
                }
            } else if (!answer.isString || answer.stringValue().trim() != profile) {
                badRequest("${field.title} 답변이 프로필과 다릅니다.")
            }
        }
        fields.filter { it.formType == "COMPANION" }.forEach { field ->
            val companions = answers.get(field.title)
            if (companions != null && !companions.isNull && !(companions.isArray && companions.size() == 0)) {
                badRequest("동행자는 newParticipants 항목으로 보내야 합니다.")
            }
        }
        return if (changed) mapper.writeValueAsString(answers) else person.informationJson
    }

    private fun badRequest(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
}
