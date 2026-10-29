package team.startup.application.domain.application.service.impl

import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.PreregisterRegistrationReceipt
import team.startup.application.domain.application.repository.PreregisterReservationRepository
import team.startup.application.domain.application.service.RegistrationCommand
import tools.jackson.databind.ObjectMapper
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.UUID

@Component
class RegistrationWorkflow(
    private val gateway: RegistrationGateway,
    private val mapper: ObjectMapper,
    private val preregistration: PreregisterRegistrationWorkflow,
    private val attendance: SessionAttendanceOutbox,
    private val reservations: PreregisterReservationRepository,
    @org.springframework.beans.factory.annotation.Value("\${application.field-visits.enabled:false}") private val fieldVisitsEnabled:
        Boolean = false,
) {
    private val occupations = CompanionAnswers.occupations
    private val regions = CompanionAnswers.regions

    fun register(
        command: RegistrationCommand,
        participantType: String,
        applicationType: String,
    ): PreregisterRegistrationReceipt? =
        try {
            registerRequest(command, participantType, applicationType)
        } catch (ex: DataAccessException) {
            if (participantType == "STANDARD" && applicationType == "PRE" &&
                generateSequence<Throwable>(ex) { it.cause }.filterIsInstance<SQLException>().any { it.sqlState == "55P03" }
            ) {
                val retry = if (command.idempotencyKey != null) " 같은 멱등 키로 재시도해 주세요." else " 잠시 후 다시 시도해 주세요."
                throw ResponseStatusException(HttpStatus.CONFLICT, "신청이 몰려 처리하지 못했습니다.$retry", ex)
            }
            throw ex
        }

    private fun registerRequest(
        command: RegistrationCommand,
        participantType: String,
        applicationType: String,
    ): PreregisterRegistrationReceipt? {
        if (participantType == "STANDARD" && applicationType == "FIELD" && fieldVisitsEnabled) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "FIELD_VISIT_SELECTION_REQUIRED")
        }
        var request = command.request
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
        val sessionRegistration = participantType == "STANDARD" && applicationType == "PRE"
        if (sessionRegistration && (request.sessionId == null || request.sessionId <= 0)) {
            badRequest("사전등록 회차 ID가 필요합니다.")
        }
        val expo = gateway.expoPeriod(expoId)
        if (expo.participantPhoneVerificationRequired) {
            val session =
                command.participantSession?.takeIf { it.isNotBlank() }
                    ?: throw ParticipantSessionException("SESSION_REQUIRED")
            val phone = gateway.participantSessionPhone(expoId, session)
            request = request.copy(phoneNumber = phone)
        } else {
            // Notification drops QR events whose phone is not 01X digits (#214), so reject before reserving or saving.
            val phone = request.phoneNumber.filter { it in '0'..'9' }.replace(Regex("^82"), "0")
            if (!phone.matches(Regex("01[0-9]{8,9}"))) badRequest("휴대전화 번호가 올바르지 않습니다.")
            request = request.copy(phoneNumber = phone)
        }
        val authenticatedCommand = command.copy(expoId = expoId, request = request)
        if (sessionRegistration) {
            preregistration.resume(authenticatedCommand)?.let { return it.pendingOrNull() }
        }
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val (started, finished) =
            try {
                LocalDate.parse(expo.startedDay) to LocalDate.parse(expo.finishedDay)
            } catch (ex: DateTimeParseException) {
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "박람회 날짜 응답이 올바르지 않습니다.", ex)
            }
        if (applicationType == "FIELD" && (today.isBefore(started) || today.isAfter(finished))) {
            badRequest("박람회 등록 기간이 아닙니다.")
        }
        // #261: 사전등록 이력이 있는 번호의 현장등록. 그날 회차 확정이면 받은 QR로 입장하게 거절하고, 아니면 applicationType FIELD 저장으로 User가 기존 PRE 대표자를 FIELD로 바꾼다.
        val reregistration =
            if (participantType == "STANDARD" && applicationType == "FIELD") {
                reservations.preregisteredOn(UUID.fromString(expoId), request.phoneNumber.filter { it in '0'..'9' }, today)
            } else {
                null
            }
        if (reregistration == true) throw ResponseStatusException(HttpStatus.CONFLICT, PREREGISTERED_TODAY)
        // 일반 사전등록 신규 접수는 행사 시작일 00:00 KST에 닫는다(#171). 기존 예약의 멱등·복구는 위 resume이 먼저 처리한다.
        if (sessionRegistration && !today.isBefore(started)) {
            badRequest("사전 등록 기간이 아닙니다.")
        }

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
            val value = answers.get(title) ?: return null
            return value
                .takeIf { it.isString }
                ?.stringValue()
                ?.takeIf { it.isNotBlank() }
        }

        fun choice(
            field: RegistrationField?,
            allowed: Set<String>,
        ): String? {
            if (field == null) return null
            val value = answers.get(field.title)
            if (value == null || value.isNull || (value.isString && value.stringValue().isBlank())) {
                if (field.requiredStatus) badRequest("${field.title} 답변이 필요합니다.")
                return null
            }
            if (!value.isString || value.stringValue() !in allowed) badRequest("${field.title} 답변이 올바르지 않습니다.")
            return value.stringValue()
        }
        val occupation = choice(form?.dynamicForm?.firstOrNull { it.dynamicFormType == "OCCUPATION" }, occupations)
        val school = answer("SCHOOL")
        val region = choice(form?.dynamicForm?.firstOrNull { it.formType == "REGION" }, regions)
        if (school != null && school.length > 100) badRequest("학교는 100자 이하여야 합니다.")
        if (occupation in setOf("TEACHER", "PRE_SERVICE_TEACHER") && school == null) {
            badRequest("직업에 해당하는 학교를 입력해야 합니다.")
        }
        var companionCount = 0
        val companionKeys = mutableSetOf<List<String?>>()
        val companionOccupations = mutableListOf<String?>()
        form?.dynamicForm?.filter { it.formType == "COMPANION" }?.forEach { field ->
            val companions = answers.get(field.title) ?: return@forEach
            val maxCount = CompanionAnswers.limit(field)
            if (!companions.isArray) badRequest("동행자 답변은 JSON 배열이어야 합니다.")
            if (companions.size() > maxCount) badRequest("동행자는 최대 ${maxCount}명까지 입력할 수 있습니다.")
            companionCount += companions.size()
            if (companionCount > 4) badRequest("대표자를 포함해 최대 5명까지 신청할 수 있습니다.")
            companions.forEach {
                CompanionAnswers.validate(it)
                companionKeys.add(CompanionAnswers.key(it))
                companionOccupations.add(it.path("occupation").asString())
            }
        }
        if (sessionRegistration) {
            PreregisterSeatCategory.requireOpen(form?.occupationOpenAt, Instant.now(), listOf(occupation) + companionOccupations)
        }
        val requestId = command.idempotencyKey ?: UUID.randomUUID().toString()
        val questions =
            form?.dynamicForm?.mapIndexed { order, field ->
                RegistrationQuestion(field.id, field.title, order, field.formType, field.jsonData, field.otherJson, field.dynamicFormType)
            }
        val registration =
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
                region,
            )
        if (sessionRegistration) {
            return preregistration.register(authenticatedCommand, registration, 1 + companionKeys.size).pendingOrNull()
        }
        val participant = gateway.participant(participantType, registration)
        if (participantType == "STANDARD") {
            val field = applicationType == "FIELD"
            if (field) attendance.fieldRegistered(UUID.fromString(expoId), fieldRegistered(participant))
            participant.groupParticipantIds.forEach { gateway.countStandard(expoId, it) }
        }
        return null
    }

    // #275: User names who became FIELD by this request; an older User response without it leaves only the representative.
    // ponytail: the representative stays in even when User omits it, as before, so a retry after a lost outbox insert still
    // releases it; Attendance's field-registration PUT is idempotent and the outbox only queues participants with history here.
    private fun fieldRegistered(result: ParticipantRegistrationResult): List<Long> =
        (listOf(result.participantId) + result.fieldParticipantIds.orEmpty()).distinct()

    // Null keeps the existing 201 contract for confirmed registrations.
    private fun PreregisterRegistrationReceipt?.pendingOrNull() = this?.takeIf { it.status != "CONFIRMED" }

    private fun badRequest(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)

    companion object {
        // RegistrationController가 이 사유를 409 ALREADY_PREREGISTERED_TODAY로 바꾼다.
        const val PREREGISTERED_TODAY = "오늘 회차에 확정된 사전등록이 있습니다."
    }
}
