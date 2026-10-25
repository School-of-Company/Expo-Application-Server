package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.GetOwnerCancellationStatusCommand
import team.startup.application.domain.application.presentation.dto.OwnerCancellationStatus
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
class OwnerCancellationGateway(
    private val mapper: ObjectMapper,
    @Value("\${application.registration.user-url:}") private val baseUrl: String,
    @Value("\${application.registration.user-internal-token:}") private val token: String,
    private val circuits: DownstreamCircuits = DownstreamCircuits(),
) {
    data class VerifiedStatus(
        val phoneNumber: String,
        val status: OwnerCancellationStatus,
    ) {
        override fun toString() = "VerifiedStatus(status=$status)"
    }

    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    @Transactional(propagation = Propagation.NEVER)
    fun find(command: GetOwnerCancellationStatusCommand): OwnerCancellationStatus = verify(command).status

    @Transactional(propagation = Propagation.NEVER)
    fun verify(command: GetOwnerCancellationStatusCommand): VerifiedStatus {
        if (command.sessionId.isBlank()) throw ParticipantSessionException("SESSION_REQUIRED")
        if (command.participantId <= 0) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val session =
            post(
                "/internal/participant-sessions/resolve",
                mapOf("expoId" to command.expoId, "sessionId" to command.sessionId),
                session = true,
            )
        val expo = session.get("expoId")
        val phone = session.get("phoneNumber")
        if (expo == null || !expo.isString || expo.stringValue() != command.expoId.toString() ||
            phone == null || !phone.isString || phone.stringValue().isBlank() || phone.stringValue().length > 20
        ) {
            invalidResponse()
        }
        val status =
            post(
                "/internal/standard-participants/cancellation-status",
                mapOf("expoId" to command.expoId, "phoneNumber" to phone.stringValue(), "participantId" to command.participantId),
            )
        val id = status.get("participantId")
        val representative = status.get("representative")
        val entered = status.get("entered")
        val linked = status.get("traineeLinked")
        val trainee = status.get("linkedTraineeId")
        if (id == null || !id.isIntegralNumber || !id.canConvertToLong() || id.asLong() != command.participantId ||
            representative == null || !representative.isBoolean || entered == null || !entered.isBoolean ||
            linked == null || !linked.isBoolean || trainee == null ||
            (!trainee.isNull && (!trainee.isIntegralNumber || !trainee.canConvertToLong() || trainee.asLong() <= 0)) ||
            linked.asBoolean() == trainee.isNull
        ) {
            invalidResponse()
        }
        return VerifiedStatus(
            phone.stringValue(),
            OwnerCancellationStatus(
                id.asLong(),
                representative.asBoolean(),
                entered.asBoolean(),
                linked.asBoolean(),
                if (trainee.isNull) null else trainee.asLong(),
            ),
        )
    }

    private fun post(
        path: String,
        body: Map<String, Any>,
        session: Boolean = false,
    ): JsonNode {
        if (baseUrl.isBlank() || token.isBlank()) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "취소 상태 조회 연동이 설정되지 않았습니다.")
        }
        val response =
            try {
                circuits.send(
                    Downstream.USER,
                    client,
                    HttpRequest
                        .newBuilder(URI.create(baseUrl.trimEnd('/') + path))
                        .timeout(Duration.ofSeconds(8))
                        .header("X-Internal-Token", token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            } catch (exception: Exception) {
                if (exception is InterruptedException) Thread.currentThread().interrupt()
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "취소 상태 조회 서비스와 연결할 수 없습니다.", exception)
            }
        when (response.statusCode()) {
            200 -> Unit
            401 -> if (session) throw ParticipantSessionException(sessionCode(response.body())) else invalidResponse()
            404 -> if (!session) throw ResponseStatusException(HttpStatus.NOT_FOUND) else invalidResponse()
            in 500..599 -> throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "취소 상태 조회 서비스가 응답하지 않습니다.")
            else -> invalidResponse()
        }
        return try {
            mapper.readTree(response.body()).also { if (!it.isObject) invalidResponse() }
        } catch (_: Exception) {
            invalidResponse()
        }
    }

    // User가 만료(SESSION_EXPIRED)를 구분해 주면 그대로 전달하고, 그 밖에는 SESSION_REQUIRED로 둔다.
    private fun sessionCode(body: String?): String =
        try {
            if (mapper.readTree(body).path("code").stringValue() == "SESSION_EXPIRED") "SESSION_EXPIRED" else "SESSION_REQUIRED"
        } catch (_: Exception) {
            "SESSION_REQUIRED"
        }

    private fun invalidResponse(): Nothing = throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "취소 상태 조회 응답이 올바르지 않습니다.")
}
