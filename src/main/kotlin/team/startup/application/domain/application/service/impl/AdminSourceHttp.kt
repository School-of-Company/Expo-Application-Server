package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal class AdminSourceHttp(
    private val mapper: ObjectMapper,
    private val url: String,
    private val token: String,
    private val circuits: DownstreamCircuits,
    private val service: String,
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    fun read(
        path: String,
        body: Any? = null,
        authorization: String? = null,
        propagatedStatuses: Set<Int> = emptySet(),
        reasons: Map<Pair<Int, String>, String> = emptyMap(),
    ): JsonNode {
        if (url.isBlank() || token.isBlank()) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "관리자 원천 연동이 설정되지 않았습니다.")
        val response =
            try {
                val request =
                    HttpRequest
                        .newBuilder(URI.create("${url.trimEnd('/')}$path"))
                        .timeout(Duration.ofSeconds(8))
                        .header("X-Internal-Token", token)
                if (authorization != null) request.header("Authorization", authorization)
                if (body == null) {
                    request.GET()
                } else {
                    request
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                }
                circuits.send(service, client, request.build(), HttpResponse.BodyHandlers.ofString())
            } catch (exception: Exception) {
                if (exception is InterruptedException) Thread.currentThread().interrupt()
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "관리자 원천을 조회할 수 없습니다.")
            }
        val status = response.statusCode()
        if (status in propagatedStatuses) {
            // #267: only an allowlisted (status, source message) becomes a reason; any other source text stays hidden.
            val message = runCatching { mapper.readTree(response.body()).path("message").stringValue() }.getOrNull()
            throw ResponseStatusException(HttpStatus.valueOf(status), reasons[status to message] ?: "관리자 원천이 요청을 거부했습니다.")
        }
        if (status in 500..599) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "관리자 원천이 응답하지 않습니다.")
        if (status != 200) throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "관리자 원천 응답 상태가 올바르지 않습니다.")
        return valid { mapper.readTree(response.body()) }
    }

    fun <T> valid(parse: () -> T): T =
        try {
            parse()
        } catch (exception: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "관리자 원천 응답이 올바르지 않습니다.")
        }
}
