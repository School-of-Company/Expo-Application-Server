package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

@Component
class AttendanceCancellationStatusGateway(
    private val mapper: ObjectMapper,
    @Value("\${application.attendance.url:}") private val baseUrl: String,
    @Value("\${application.attendance.internal-token:}") private val token: String,
) {
    data class Status(
        val participantId: Long,
        val representative: Boolean,
        val entered: Boolean,
        val programAttended: Boolean,
        val traineeLinked: Boolean,
        val linkedTraineeId: Long?,
        val guardState: String,
    )

    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    @Transactional(propagation = Propagation.NEVER)
    fun find(
        expoId: UUID,
        participantId: Long,
        verifiedPhoneNumber: String,
    ): Status {
        require(participantId > 0 && verifiedPhoneNumber.isNotBlank() && verifiedPhoneNumber.length <= 20)
        if (baseUrl.isBlank() || token.isBlank()) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "출석 취소 상태 연동이 설정되지 않았습니다.")
        }
        val response =
            try {
                client.send(
                    HttpRequest
                        .newBuilder(
                            URI.create("${baseUrl.trimEnd('/')}/internal/expos/$expoId/participants/$participantId/cancellation-status"),
                        ).timeout(Duration.ofSeconds(8))
                        .header("X-Internal-Token", token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mapOf("phoneNumber" to verifiedPhoneNumber))))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            } catch (exception: Exception) {
                if (exception is InterruptedException) Thread.currentThread().interrupt()
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "출석 취소 상태 서비스와 연결할 수 없습니다.", exception)
            }
        when (response.statusCode()) {
            200 -> Unit
            404, in 500..599 -> throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "출석 취소 상태 계약을 확인할 수 없습니다.")
            else -> invalidResponse()
        }
        val status =
            try {
                mapper.readTree(response.body())
            } catch (_: Exception) {
                invalidResponse()
            }
        val id = status.get("participantId")
        val representative = status.get("representative")
        val entered = status.get("entered")
        val linked = status.get("traineeLinked")
        val attended = status.get("programAttended")
        val trainee = status.get("linkedTraineeId")
        val state = status.get("guardState")
        if (!status.isObject || id == null || !id.isIntegralNumber || !id.canConvertToLong() || id.asLong() != participantId ||
            representative == null || !representative.isBoolean || entered == null || !entered.isBoolean ||
            linked == null || !linked.isBoolean || attended == null || !attended.isBoolean ||
            trainee == null || (!trainee.isNull && (!trainee.isIntegralNumber || !trainee.canConvertToLong() || trainee.asLong() <= 0)) ||
            linked.asBoolean() != !trainee.isNull ||
            (state != null && !state.isNull && (!state.isString || state.stringValue() !in setOf("HELD", "COMMITTED")))
        ) {
            invalidResponse()
        }
        return Status(
            participantId,
            representative.asBoolean(),
            entered.asBoolean(),
            attended.asBoolean(),
            linked.asBoolean(),
            if (trainee.isNull) null else trainee.asLong(),
            if (state == null || state.isNull) "NONE" else state.stringValue(),
        )
    }

    private fun invalidResponse(): Nothing = throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "출석 취소 상태 응답이 올바르지 않습니다.")
}
