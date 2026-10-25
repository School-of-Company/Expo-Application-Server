package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

data class FieldCancellationTarget(
    val registrationId: UUID,
    val expoId: UUID,
    val participantId: Long,
    val visitDate: LocalDate,
    val generation: Long,
)

@Component
@Transactional(propagation = Propagation.NEVER)
class FieldCancellationGateway(
    private val mapper: ObjectMapper,
    @Value("\${application.attendance.url:}") private val attendanceUrl: String,
    @Value("\${application.attendance.internal-token:}") private val attendanceToken: String,
    @Value("\${application.registration.user-url:}") private val userUrl: String,
    @Value("\${application.registration.user-internal-token:}") private val userToken: String,
    private val circuits: DownstreamCircuits = DownstreamCircuits(),
) {
    data class Status(
        val enteredOnVisitDate: Boolean,
        val programAttended: Boolean,
        val guardState: String,
        val linkedTraineeId: Long?,
    )

    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    fun status(
        target: FieldCancellationTarget,
        phone: String,
    ): Status {
        require(phone.isNotBlank() && phone.length <= 20)
        val json = send(target, "/cancellation-status", "POST", body(target) + ("phoneNumber" to phone), 200)

        fun flag(name: String): Boolean = json.get(name)?.takeIf { it.isBoolean }?.asBoolean() ?: invalid()
        val trainee = json.get("linkedTraineeId") ?: invalid()
        if (!trainee.isNull && (!trainee.isIntegralNumber || !trainee.canConvertToLong() || trainee.asLong() <= 0)) invalid()
        val guard = json.get("guardState")?.takeIf { it.isString }?.stringValue()
        if (guard !in setOf("NONE", "HELD", "COMMITTED")) invalid()
        if (json.path("registrationId").asString() != target.registrationId.toString() ||
            json.path("participantId").let { !it.isIntegralNumber || !it.canConvertToLong() || it.asLong() != target.participantId } ||
            json.path("visitDate").asString() != target.visitDate.toString() ||
            json.path("generation").let { !it.isIntegralNumber || !it.canConvertToLong() || it.asLong() != target.generation }
        ) {
            invalid()
        }
        return Status(
            flag("enteredOnVisitDate"),
            flag("programAttended"),
            requireNotNull(guard),
            if (trainee.isNull) null else trainee.asLong(),
        )
    }

    fun acquire(
        target: FieldCancellationTarget,
        operationId: UUID,
        phone: String,
    ) {
        require(phone.isNotBlank() && phone.length <= 20)
        send(target, "/cancellation-guards/$operationId", "PUT", body(target) + ("phoneNumber" to phone))
    }

    fun commit(
        target: FieldCancellationTarget,
        operationId: UUID,
    ) {
        send(target, "/cancellation-guards/$operationId/commit", "POST", body(target))
    }

    fun abort(
        target: FieldCancellationTarget,
        operationId: UUID,
    ) {
        send(target, "/cancellation-guards/$operationId", "DELETE", body(target))
    }

    fun projectCancellation(
        target: FieldCancellationTarget,
        operationId: UUID,
    ) {
        require(target.participantId > 0 && target.generation > 0)
        request(
            Downstream.USER,
            userUrl,
            userToken,
            path(target),
            "PUT",
            body(target) + mapOf("status" to "CANCELLED", "operationId" to operationId.toString()),
            204,
        )
    }

    private fun path(target: FieldCancellationTarget) =
        "/internal/expos/${target.expoId}/participants/${target.participantId}/field-visits/${target.registrationId}"

    private fun body(target: FieldCancellationTarget): Map<String, Any> =
        mapOf("visitDate" to target.visitDate.toString(), "generation" to target.generation)

    private fun send(
        target: FieldCancellationTarget,
        suffix: String,
        method: String,
        body: Map<String, Any>,
        expected: Int = 204,
    ): JsonNode {
        require(target.participantId > 0 && target.generation > 0)
        return request(Downstream.ATTENDANCE, attendanceUrl, attendanceToken, path(target) + suffix, method, body, expected)
    }

    private fun request(
        service: String,
        baseUrl: String,
        token: String,
        path: String,
        method: String,
        body: Map<String, Any>,
        expected: Int,
    ): JsonNode {
        if (baseUrl.isBlank() || token.isBlank()) unavailable()
        val response =
            try {
                circuits.send(
                    service,
                    client,
                    HttpRequest
                        .newBuilder(URI.create(baseUrl.trimEnd('/') + path))
                        .timeout(Duration.ofSeconds(8))
                        .header("X-Internal-Token", token)
                        .header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            } catch (exception: Exception) {
                if (exception is InterruptedException) Thread.currentThread().interrupt()
                unavailable()
            }
        when (response.statusCode()) {
            expected -> Unit
            409 -> throw OwnerApplicationRejectedException(HttpStatus.CONFLICT, "FIELD_CANCELLATION_CONFLICT", "현장등록 취소 상태가 충돌했습니다.")
            404, in 500..599 -> unavailable()
            else -> invalid()
        }
        if (expected == 204) return mapper.createObjectNode()
        return try {
            mapper.readTree(response.body()).also { if (!it.isObject) invalid() }
        } catch (_: Exception) {
            invalid()
        }
    }

    private fun unavailable(): Nothing = throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "방문일별 현장등록 취소 공급자 계약을 확인할 수 없습니다.")

    private fun invalid(): Nothing = throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "방문일별 현장등록 취소 응답이 올바르지 않습니다.")
}
