package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import team.startup.application.domain.application.repository.SessionAttendanceMessage
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
@ConditionalOnProperty(name = ["application.attendance.enabled"], havingValue = "true")
class SessionAttendanceGateway(
    @Value("\${application.attendance.url:}") private val url: String,
    @Value("\${application.attendance.internal-token:}") private val token: String,
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    init {
        val uri = URI.create(url)
        require(uri.scheme in listOf("http", "https") && uri.host != null && uri.rawQuery == null && uri.rawFragment == null) {
            "application.attendance.url must be an HTTP service URL"
        }
        require(token.isNotBlank()) { "application.attendance.internal-token must be configured" }
        try {
            HttpRequest.newBuilder(uri).header("X-Internal-Token", token)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("application.attendance.internal-token must be a valid HTTP header value")
        }
    }

    fun send(message: SessionAttendanceMessage) {
        val path = "/internal/expos/${message.expoId}/participants/${message.participantId}/preregister-session"
        val request =
            HttpRequest
                .newBuilder(URI.create(url.trimEnd('/') + path))
                .timeout(Duration.ofSeconds(8))
                .header("X-Internal-Token", token)
        if (message.sessionId == null) {
            request.DELETE()
        } else {
            request.header("Content-Type", "application/json")
            request.PUT(HttpRequest.BodyPublishers.ofString("{\"sessionId\":${message.sessionId}}"))
        }
        val response = client.send(request.build(), HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() != 204) throw IOException("Attendance HTTP ${response.statusCode()}")
    }
}
