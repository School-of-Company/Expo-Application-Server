package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.OwnerApplicationType
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

@Component
class OwnerCancellationDeadlineGateway(
    private val mapper: ObjectMapper,
    @Value("\${application.registration.expo-url:}") private val baseUrl: String,
    @Value("\${application.registration.expo-internal-token:}") private val token: String,
    private val programs: RegistrationGateway? = null,
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    @Transactional(propagation = Propagation.NEVER)
    fun program(
        expoId: UUID,
        type: OwnerApplicationType,
        programId: Long,
    ): Instant {
        val programType =
            when (type) {
                OwnerApplicationType.STANDARD -> ProgramSessionType.STANDARD
                OwnerApplicationType.TRAINING -> ProgramSessionType.TRAINING
                OwnerApplicationType.PREREGISTER -> throw IllegalArgumentException("Not a program application")
            }
        return programs?.programDefinition(expoId.toString(), programType, programId)?.policy?.cancelEndedAt
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "프로그램 취소 기한을 확인할 수 없습니다.")
    }

    @Transactional(propagation = Propagation.NEVER)
    fun preregister(
        expoId: UUID,
        sessionId: Long,
    ): Instant {
        if (baseUrl.isBlank() || token.isBlank()) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "취소 기한 연동이 설정되지 않았습니다.")
        }
        val response =
            try {
                client.send(
                    HttpRequest
                        .newBuilder(URI.create("${baseUrl.trimEnd('/')}/internal/expo/$expoId/preregister-sessions/$sessionId"))
                        .timeout(Duration.ofSeconds(8))
                        .header("X-Internal-Token", token)
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            } catch (exception: Exception) {
                if (exception is InterruptedException) Thread.currentThread().interrupt()
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "취소 기한 서비스와 연결할 수 없습니다.", exception)
            }
        if (response.statusCode() != 200) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "현재 취소 기한을 확인할 수 없습니다.")
        }
        val node =
            try {
                mapper.readTree(response.body())
            } catch (exception: Exception) {
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "취소 기한 응답이 올바르지 않습니다.", exception)
            }
        if (!node.isObject || !node.path("id").isIntegralNumber || !node.path("id").canConvertToLong() ||
            node.path("id").asLong() != sessionId || !node.path("expoId").isString || node.path("expoId").stringValue() != expoId.toString()
        ) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "취소 기한 응답이 올바르지 않습니다.")
        }
        val deadline = node.get("cancelEndedAt")
        if (deadline == null || deadline.isNull) throw ResponseStatusException(HttpStatus.CONFLICT, "취소 기한이 설정되지 않았습니다.")
        return try {
            if (!deadline.isString) throw IllegalArgumentException()
            OffsetDateTime.parse(deadline.stringValue()).toInstant()
        } catch (exception: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "취소 기한 응답이 올바르지 않습니다.", exception)
        }
    }
}
