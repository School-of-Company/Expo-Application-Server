package team.startup.application

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.service.impl.OwnerCancellationDeadlineGateway
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID

class OwnerCancellationDeadlineGatewayTests {
    private lateinit var server: HttpServer
    private lateinit var gateway: OwnerCancellationDeadlineGateway

    @Volatile private var code = 200

    @Volatile private var body = VALID

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/internal/") { exchange ->
            assertEquals("GET", exchange.requestMethod)
            assertEquals("expo-token", exchange.requestHeaders.getFirst("X-Internal-Token"))
            assertEquals("/internal/expo/$EXPO/preregister-sessions/7", exchange.requestURI.path)
            assertEquals(null, exchange.requestURI.query)
            assertEquals("", exchange.requestBody.bufferedReader().use { it.readText() })
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(code, if (code == 204) -1 else bytes.size.toLong())
            exchange.responseBody.use { if (code != 204) it.write(bytes) }
        }
        server.start()
        gateway = OwnerCancellationDeadlineGateway(jacksonObjectMapper(), "http://127.0.0.1:${server.address.port}/", "expo-token")
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    @Test
    fun `reads supplied deadline with offset independently of registration end`() {
        assertEquals(Instant.parse("2026-10-12T01:00:00Z"), gateway.preregister(EXPO, 7))
        body = VALID.replace("2026-10-12T10:00:00+09:00", "2026-10-12T01:00:00Z")
        assertEquals(Instant.parse("2026-10-12T01:00:00Z"), gateway.preregister(EXPO, 7))
    }

    @Test
    fun `legacy missing and explicitly unset deadlines cannot enable cancellation`() {
        for (response in listOf(
            VALID.replace(",\"cancelEndedAt\":\"2026-10-12T10:00:00+09:00\"", ""),
            VALID.replace("\"2026-10-12T10:00:00+09:00\"", "null"),
        )) {
            body = response
            failure(HttpStatus.CONFLICT)
        }
    }

    @Test
    fun `wrong identity malformed and timezone-less deadlines fail closed`() {
        for (response in listOf(
            "{}",
            "null",
            "[]",
            "{",
            VALID.replace("\"id\":7", "\"id\":8"),
            VALID.replace("\"id\":7", "\"id\":\"7\""),
            VALID.replace("\"id\":7", "\"id\":9223372036854775808"),
            VALID.replace(EXPO.toString(), UUID.randomUUID().toString()),
            VALID.replace("2026-10-12T10:00:00+09:00", "2026-10-12T10:00:00"),
            VALID.replace("2026-10-12T10:00:00+09:00", "invalid"),
            VALID.replace("\"2026-10-12T10:00:00+09:00\"", "123"),
        )) {
            body = response
            failure(HttpStatus.BAD_GATEWAY)
        }
    }

    @Test
    fun `provider denial unexpected success and outage cannot imply a deadline`() {
        for (response in listOf(201, 204, 400, 401, 404, 409, 500, 503)) {
            code = response
            failure(HttpStatus.SERVICE_UNAVAILABLE)
        }
        server.stop(0)
        failure(HttpStatus.SERVICE_UNAVAILABLE)
        assertEquals(
            HttpStatus.SERVICE_UNAVAILABLE,
            assertThrows(ResponseStatusException::class.java) {
                OwnerCancellationDeadlineGateway(jacksonObjectMapper(), "", "expo-token").preregister(EXPO, 7)
            }.statusCode,
        )
        assertEquals(
            HttpStatus.SERVICE_UNAVAILABLE,
            assertThrows(ResponseStatusException::class.java) {
                OwnerCancellationDeadlineGateway(jacksonObjectMapper(), "http://127.0.0.1:${server.address.port}", "").preregister(EXPO, 7)
            }.statusCode,
        )
    }

    private fun failure(expected: HttpStatus) {
        assertEquals(expected, assertThrows(ResponseStatusException::class.java) { gateway.preregister(EXPO, 7) }.statusCode)
    }

    companion object {
        private val EXPO = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000045")
        private val VALID =
            """{"id":7,"expoId":"$EXPO","endedAt":"2027-01-01T00:00:00Z","cancelEndedAt":"2026-10-12T10:00:00+09:00"}"""
    }
}
