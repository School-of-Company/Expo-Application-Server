package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.service.RegistrationCommand
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.UUID

@Component
class RegistrationWorkflow(
    private val gateway: RegistrationGateway,
    private val mapper: ObjectMapper,
) {
    fun register(
        command: RegistrationCommand,
        participantType: String,
        applicationType: String,
    ) {
        val request = command.request
        val expoId =
            try {
                UUID.fromString(command.expoId).toString()
            } catch (ex: IllegalArgumentException) {
                badRequest("박람회 ID가 올바르지 않습니다.")
            }
        if (expoId != command.expoId.lowercase()) {
            badRequest("박람회 ID가 올바르지 않습니다.")
        }
        if (!request.personalInformationStatus) badRequest("개인정보 수집에 동의해야 합니다.")
        if (participantType == "TRAINEE" && request.trainingId.isNullOrBlank()) badRequest("연수 ID가 필요합니다.")
        if (command.idempotencyKey != null && (command.idempotencyKey.isBlank() || command.idempotencyKey.length > 100)) {
            badRequest("Idempotency-Key는 1~100자여야 합니다.")
        }
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val expo = gateway.expoPeriod(expoId)
        val (started, finished) =
            try {
                LocalDate.parse(expo.startedDay) to LocalDate.parse(expo.finishedDay)
            } catch (ex: DateTimeParseException) {
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "박람회 날짜 응답이 올바르지 않습니다.", ex)
            }
        if (today.isBefore(started) || today.isAfter(finished)) badRequest("박람회 등록 기간이 아닙니다.")

        val form = gateway.form(expoId, participantType, applicationType, applicationType == "PRE")
        if (applicationType == "PRE" && form != null) {
            val now = OffsetDateTime.now(ZoneId.of("Asia/Seoul"))
            val (start, end) =
                try {
                    OffsetDateTime.parse(form.startDate) to OffsetDateTime.parse(form.endDate)
                } catch (ex: DateTimeParseException) {
                    throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "폼 날짜 응답이 올바르지 않습니다.", ex)
                }
            if (now.isBefore(start) || now.isAfter(end)) {
                badRequest("사전 등록 기간이 아닙니다.")
            }
        }
        val answers =
            try {
                mapper.readTree(request.informationJson)
            } catch (ex: Exception) {
                badRequest("폼 답변이 올바른 JSON이 아닙니다.")
            }
        if (!answers.isObject) badRequest("폼 답변은 JSON 객체여야 합니다.")

        fun answer(type: String): String? {
            val title = form?.dynamicForm?.firstOrNull { it.dynamicFormType == type }?.title ?: return null
            return answers
                .get(title)
                ?.takeIf { it.isString }
                ?.stringValue()
                ?.takeIf { it.isNotBlank() }
        }
        val occupation = answer("OCCUPATION")
        val school = answer("SCHOOL")
        if (school != null && school.length > 100) badRequest("학교는 100자 이하여야 합니다.")
        if (occupation in setOf("TEACHER", "PRE_SERVICE_TEACHER") && school == null) {
            badRequest("직업에 해당하는 학교를 입력해야 합니다.")
        }
        var companionCount = 0
        form?.dynamicForm?.filter { it.formType == "COMPANION" }?.forEach { field ->
            val companions = answers.get(field.title) ?: return@forEach
            val limit = field.otherJson?.get("maxSelection")
            if (limit != null && (!limit.isIntegralNumber || !limit.canConvertToInt() || limit.intValue() <= 0)) {
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "폼의 동행자 최대 인원 응답이 올바르지 않습니다.")
            }
            val maxCount = minOf(limit?.intValue() ?: 4, 4)
            if (!companions.isArray) badRequest("동행자 답변은 JSON 배열이어야 합니다.")
            if (companions.size() > maxCount) badRequest("동행자는 최대 ${maxCount}명까지 입력할 수 있습니다.")
            companionCount += companions.size()
            if (companionCount > 4) badRequest("대표자를 포함해 최대 5명까지 신청할 수 있습니다.")
            companions.forEach(::validateCompanion)
        }
        val requestId = command.idempotencyKey ?: UUID.randomUUID().toString()
        val questions =
            form?.dynamicForm?.mapIndexed { order, field ->
                RegistrationQuestion(field.id, field.title, order, field.formType, field.jsonData, field.otherJson, field.dynamicFormType)
            }
        val participant =
            gateway.participant(
                participantType,
                ParticipantRegistration(
                    expoId,
                    request.name,
                    request.phoneNumber,
                    request.informationJson,
                    request.personalInformationStatus,
                    applicationType,
                    request.trainingId,
                    occupation,
                    school,
                    requestId,
                    form?.id,
                    questions,
                ),
            )
        if (participantType == "STANDARD") participant.participantIds.forEach { gateway.countStandard(expoId, it) }
    }

    private fun validateCompanion(companion: JsonNode) {
        if (!companion.isObject) badRequest("동행자 답변은 JSON 객체여야 합니다.")

        fun text(key: String) = companion.get(key)?.takeIf { it.isString }?.stringValue()
        val name = text("name")?.trim()
        if (name.isNullOrEmpty() || name.length > 10) badRequest("동행자 이름은 1~10자여야 합니다.")
        val occupation = text("occupation")
        if (occupation !in
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
        ) {
            badRequest("동행자 직업이 올바르지 않습니다.")
        }
        if (text("region") !in setOf("GWANGJU", "JEONNAM", "OTHER")) badRequest("동행자 지역이 올바르지 않습니다.")
        if (occupation in setOf("TEACHER", "PRE_SERVICE_TEACHER")) {
            val school = text("school")?.trim()
            if (school.isNullOrEmpty() || school.length > 100) badRequest("동행자 학교는 1~100자여야 합니다.")
        } else if (companion.has("school")) {
            badRequest("이 직업은 동행자 학교를 받지 않습니다.")
        }
    }

    private fun badRequest(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
}
