package team.startup.application

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.service.impl.AttendanceCancellationAdapter
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.util.UUID

class AttendanceCancellationAdapterTests {
    private lateinit var server: HttpServer
    private lateinit var adapter: AttendanceCancellationAdapter
    private val requests = mutableListOf<Triple<String, String, String>>()

    @Volatile private var code = 204

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/internal/") { exchange ->
            assertEquals("attendance-token", exchange.requestHeaders.getFirst("X-Internal-Token"))
            assertEquals(null, exchange.requestURI.query)
            if (exchange.requestMethod == "PUT") assertEquals("application/json", exchange.requestHeaders.getFirst("Content-Type"))
            requests.add(
                Triple(exchange.requestMethod, exchange.requestURI.path, exchange.requestBody.bufferedReader().use { it.readText() }),
            )
            exchange.sendResponseHeaders(code, -1)
            exchange.close()
        }
        server.start()
        adapter = AttendanceCancellationAdapter(jacksonObjectMapper(), "http://127.0.0.1:${server.address.port}/", "attendance-token")
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    @Test
    fun `acquire retries and explicit commit or abort preserve operation identity`() {
        adapter.acquire(EXPO, 2, OPERATION, "01012345678")
        adapter.acquire(EXPO, 2, OPERATION, "01012345678")
        adapter.commit(EXPO, 2, OPERATION)
        adapter.abort(EXPO, 2, OPERATION)
        val path = "/internal/expos/$EXPO/participants/2/cancellation-guard/$OPERATION"
        assertEquals(
            listOf(
                Triple("PUT", path, """{"phoneNumber":"01012345678"}"""),
                Triple("PUT", path, """{"phoneNumber":"01012345678"}"""),
                Triple("POST", "$path/commit", ""),
                Triple("DELETE", path, ""),
            ),
            requests,
        )
    }

    @Test
    fun `commit carries the immutable cancellation revision and rejects nonpositive revisions`() {
        adapter.commit(EXPO, 2, OPERATION, 123)
        adapter.commit(EXPO, 2, OPERATION, 123, 7)
        val path = "/internal/expos/$EXPO/participants/2/cancellation-guard/$OPERATION/commit"
        assertEquals(
            listOf(Triple("POST", path, """{"revision":123}"""), Triple("POST", path, """{"revision":123,"sessionId":7}""")),
            requests,
        )
        assertThrows(IllegalArgumentException::class.java) { adapter.commit(EXPO, 2, OPERATION, 0) }
        assertThrows(IllegalArgumentException::class.java) { adapter.commit(EXPO, 2, OPERATION, -1) }
        assertEquals(2, requests.size)
    }

    @Test
    fun `blocked and uncertain responses cannot imply successful fencing`() {
        for ((response, expected) in listOf(
            200 to HttpStatus.BAD_GATEWAY,
            201 to HttpStatus.BAD_GATEWAY,
            400 to HttpStatus.BAD_GATEWAY,
            401 to HttpStatus.BAD_GATEWAY,
            404 to HttpStatus.NOT_FOUND,
            409 to HttpStatus.CONFLICT,
            500 to HttpStatus.SERVICE_UNAVAILABLE,
            503 to HttpStatus.SERVICE_UNAVAILABLE,
        )) {
            code = response
            failure(expected) { adapter.acquire(EXPO, 2, OPERATION, "01012345678") }
            failure(expected) { adapter.commit(EXPO, 2, OPERATION) }
            failure(expected) { adapter.abort(EXPO, 2, OPERATION) }
        }
        assertEquals(24, requests.size)
        assertEquals(8, requests.count { it.first == "DELETE" })
    }

    @Test
    fun `invalid input missing configuration and transport failure fail closed`() {
        assertThrows(IllegalArgumentException::class.java) { adapter.acquire(EXPO, 2, OPERATION, " ") }
        assertThrows(IllegalArgumentException::class.java) { adapter.acquire(EXPO, 2, OPERATION, "1".repeat(21)) }
        assertThrows(IllegalArgumentException::class.java) { adapter.abort(EXPO, 0, OPERATION) }
        failure(HttpStatus.SERVICE_UNAVAILABLE) {
            AttendanceCancellationAdapter(jacksonObjectMapper(), "", "attendance-token").acquire(EXPO, 2, OPERATION, "01012345678")
        }
        failure(HttpStatus.SERVICE_UNAVAILABLE) {
            AttendanceCancellationAdapter(jacksonObjectMapper(), "http://127.0.0.1:${server.address.port}", "").commit(EXPO, 2, OPERATION)
        }
        assertEquals(0, requests.size)
        server.stop(0)
        failure(HttpStatus.SERVICE_UNAVAILABLE) { adapter.acquire(EXPO, 2, OPERATION, "01012345678") }
        assertEquals(0, requests.size)
    }

    private fun failure(
        expected: HttpStatus,
        action: () -> Unit,
    ) {
        assertEquals(expected, assertThrows(ResponseStatusException::class.java, action).statusCode)
    }

    companion object {
        private val EXPO = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000045")
        private val OPERATION = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000045")
    }
}
