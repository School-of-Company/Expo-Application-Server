package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class ExpoPeriod(
    val startedDay: String,
    val finishedDay: String,
)

data class RegistrationForm(
    val id: String,
    val startDate: String,
    val endDate: String,
    val dynamicForm: List<RegistrationField>,
)

data class RegistrationField(
    val id: Long,
    val title: String,
    val formType: String,
    val jsonData: JsonNode,
    val otherJson: JsonNode?,
    val dynamicFormType: String,
)

data class RegistrationQuestion(
    val id: Long,
    val title: String,
    val order: Int,
    val formType: String,
    val jsonData: JsonNode,
    val otherJson: JsonNode?,
    val dynamicFormType: String,
)

data class ParticipantRegistration(
    val expoId: String,
    val name: String,
    val phoneNumber: String,
    val informationJson: String,
    val personalInformationStatus: Boolean,
    val applicationType: String,
    val trainingId: String?,
    val occupation: String?,
    val school: String?,
    val requestId: String,
    val formId: String?,
    val questions: List<RegistrationQuestion>?,
)

data class ParticipantRegistrationResult(
    val participantId: Long,
    val phoneNumber: String,
)

data class TraineeRegistrationResult(
    val traineeId: Long,
    val phoneNumber: String,
)

@Component
class RegistrationGateway(
    private val mapper: ObjectMapper,
    @Value("\${application.registration.expo-url:}") private val expoUrl: String,
    @Value("\${application.registration.form-url:}") private val formUrl: String,
    @Value("\${application.registration.user-url:}") private val userUrl: String,
    @Value("\${application.internal-token:}") private val internalToken: String,
    @Value("\${application.registration.expo-internal-token:}") private val expoInternalToken: String = internalToken,
    @Value("\${application.registration.form-internal-token:}") private val formInternalToken: String = internalToken,
    @Value("\${application.registration.user-internal-token:}") private val userInternalToken: String = internalToken,
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    fun expoPeriod(expoId: String): ExpoPeriod =
        decode(call(expoUrl, "/internal/expo/$expoId", token = expoInternalToken).body(), ExpoPeriod::class.java)

    fun form(
        expoId: String,
        participantType: String,
        applicationType: String,
        required: Boolean,
    ): RegistrationForm? {
        val path = "/internal/forms/$expoId?type=$participantType&applicationType=$applicationType"
        val response = call(formUrl, path, allowNotFound = !required, token = formInternalToken)
        return if (response.statusCode() == 404) null else decode(response.body(), RegistrationForm::class.java)
    }

    fun participant(
        type: String,
        registration: ParticipantRegistration,
    ): ParticipantRegistrationResult {
        val path = if (type == "STANDARD") "/internal/standard-participants" else "/internal/trainees"
        val response = call(userUrl, path, "POST", mapper.writeValueAsString(registration), token = userInternalToken)
        return if (type == "STANDARD") {
            decode(response.body(), ParticipantRegistrationResult::class.java)
        } else {
            val trainee = decode(response.body(), TraineeRegistrationResult::class.java)
            ParticipantRegistrationResult(trainee.traineeId, trainee.phoneNumber)
        }
    }

    fun countStandard(
        expoId: String,
        participantId: Long,
    ) {
        try {
            call(expoUrl, "/internal/expo/$expoId/standard-registrations/$participantId", "PUT", token = expoInternalToken)
        } catch (ex: ResponseStatusException) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "신청 인원을 반영할 수 없습니다.", ex)
        }
    }

    private fun <T> decode(
        body: String,
        type: Class<T>,
    ): T =
        try {
            mapper.readValue(body, type)
        } catch (ex: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "등록 서비스 응답이 올바르지 않습니다.", ex)
        }

    private fun call(
        base: String,
        path: String,
        method: String = "GET",
        body: String? = null,
        allowNotFound: Boolean = false,
        token: String,
    ): HttpResponse<String> {
        if (base.isBlank()) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "등록 서비스 연결 주소가 없습니다.")
        val request =
            HttpRequest
                .newBuilder(URI.create(base.trimEnd('/') + path))
                .timeout(Duration.ofSeconds(8))
                .header("X-Internal-Token", token)
                .header("Content-Type", "application/json")
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
                .build()
        val response =
            try {
                client.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (ex: Exception) {
                Thread.currentThread().interruptIfInterrupted(ex)
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "등록 서비스와 연결할 수 없습니다.", ex)
            }
        when (response.statusCode()) {
            in 200..299 -> return response
            404 -> if (allowNotFound) return response else throw ResponseStatusException(HttpStatus.NOT_FOUND, "등록 대상을 찾을 수 없습니다.")
            409 -> throw ResponseStatusException(HttpStatus.CONFLICT, "이미 등록된 참가자입니다.")
            in 500..599 -> throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "등록 서비스가 응답하지 않습니다.")
            else -> throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "등록 서비스 응답이 올바르지 않습니다.")
        }
    }
}

private fun Thread.interruptIfInterrupted(ex: Exception) {
    if (ex is InterruptedException) interrupt()
}
