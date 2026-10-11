package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.ProgramFormSnapshot
import tools.jackson.databind.JsonNode

internal fun ProgramFormSnapshot.trainingId(answers: JsonNode): String? = programTrainingId(questions, answers)

internal fun programTrainingId(
    questions: JsonNode,
    answers: JsonNode,
): String? {
    val fields = questions.filter { it.path("dynamicFormType").isString && it.path("dynamicFormType").stringValue() == "TRAINING_ID" }
    if (fields.size > 1 || fields.any { !it.path("formType").isString || it.path("formType").stringValue() != "SENTENCE" }) {
        throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "연수원 ID 문항 정의가 올바르지 않습니다.")
    }
    val field = fields.singleOrNull() ?: return null
    val answer = answers.get(field.path("id").asLong().toString()) ?: return null
    if (answer.isNull || (answer.isString && answer.stringValue().isBlank())) return null
    if (!answer.isString || answer.stringValue().length > 15) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "연수원 ID 답변이 올바르지 않습니다.")
    }
    return answer.stringValue()
}
