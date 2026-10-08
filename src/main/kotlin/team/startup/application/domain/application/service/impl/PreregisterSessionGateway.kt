package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.PreregisterSessionDefinition
import team.startup.application.domain.application.presentation.dto.isValidDefinition
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

@Component
class PreregisterSessionGateway(
    private val mapper: ObjectMapper,
    @Value("\${application.registration.expo-url:}") private val baseUrl: String,
    @Value("\${application.registration.expo-internal-token:}") private val token: String,
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    @Transactional(propagation = Propagation.NEVER)
    fun find(
        expoId: UUID,
        sessionId: Long,
    ): PreregisterSessionDefinition? {
        if (baseUrl.isBlank() || token.isBlank()) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "회차 조회 연동이 설정되지 않았습니다.")
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
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "회차 정의를 조회할 수 없습니다.", exception)
            }
        return when (response.statusCode()) {
            200 -> {
                try {
                    mapper.readValue(response.body(), PreregisterSessionDefinition::class.java).also {
                        if (it.expoId != expoId || it.id != sessionId || !it.isValidDefinition()) {
                            throw IllegalArgumentException("Invalid session definition")
                        }
                    }
                } catch (exception: Exception) {
                    throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "회차 정의 응답이 올바르지 않습니다.", exception)
                }
            }

            404 -> {
                null
            }

            409 -> {
                throw ResponseStatusException(HttpStatus.CONFLICT, "박람회 삭제 또는 회차 변경 중입니다.")
            }

            in 500..599 -> {
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "회차 조회 서비스가 응답하지 않습니다.")
            }

            else -> {
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "회차 조회 응답 상태가 올바르지 않습니다.")
            }
        }
    }
}
