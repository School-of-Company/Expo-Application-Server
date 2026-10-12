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

/** Attendance fencing only; Expo's authoritative cancellation deadline is still required. */
@Component
@Transactional(propagation = Propagation.NEVER)
class AttendanceCancellationAdapter(
    private val mapper: ObjectMapper,
    @Value("\${application.attendance.url:}") private val baseUrl: String,
    @Value("\${application.attendance.internal-token:}") private val token: String,
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    fun acquireAdmin(
        expoId: UUID,
        participantId: Long,
        operationId: UUID,
    ) {
        send(expoId, participantId, operationId, "PUT", admin = true)
    }

    fun acquire(
        expoId: UUID,
        participantId: Long,
        operationId: UUID,
        verifiedPhoneNumber: String,
    ) {
        require(verifiedPhoneNumber.isNotBlank() && verifiedPhoneNumber.length <= 20)
        send(expoId, participantId, operationId, "PUT", mapper.writeValueAsString(mapOf("phoneNumber" to verifiedPhoneNumber)))
    }

    fun commit(
        expoId: UUID,
        participantId: Long,
        operationId: UUID,
        revision: Long? = null,
    ) {
        require(revision == null || revision > 0)
        send(expoId, participantId, operationId, "POST", revision?.let { mapper.writeValueAsString(mapOf("revision" to it)) })
    }

    fun abort(
        expoId: UUID,
        participantId: Long,
        operationId: UUID,
    ) = send(expoId, participantId, operationId, "DELETE")

    private fun send(
        expoId: UUID,
        participantId: Long,
        operationId: UUID,
        method: String,
        body: String? = null,
        admin: Boolean = false,
    ) {
        require(participantId > 0)
        if (baseUrl.isBlank() || token.isBlank()) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "취소 보호 연동이 설정되지 않았습니다.")
        }
        val guard = if (admin) "admin-cancellation-guard" else "cancellation-guard"
        val path = "/internal/expos/$expoId/participants/$participantId/$guard/$operationId"
        val response =
            try {
                val request =
                    HttpRequest
                        .newBuilder(URI.create(baseUrl.trimEnd('/') + path + if (method == "POST") "/commit" else ""))
                        .timeout(Duration.ofSeconds(8))
                        .header("X-Internal-Token", token)
                        .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
                if (body != null) request.header("Content-Type", "application/json")
                client.send(request.build(), HttpResponse.BodyHandlers.discarding())
            } catch (exception: Exception) {
                if (exception is InterruptedException) Thread.currentThread().interrupt()
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "취소 보호 서비스와 연결할 수 없습니다.", exception)
            }
        when (response.statusCode()) {
            204 -> Unit
            404 -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
            409 -> throw ResponseStatusException(HttpStatus.CONFLICT, "입장·출석했거나 다른 취소 작업이 진행 중입니다.")
            in 500..599 -> throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "취소 보호 서비스가 응답하지 않습니다.")
            else -> throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "취소 보호 응답이 올바르지 않습니다.")
        }
    }
}
