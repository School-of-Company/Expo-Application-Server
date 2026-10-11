package team.startup.application

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.service.impl.AttendanceCancellationStatusGateway
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.util.UUID

class AttendanceCancellationStatusGatewayTests {
    private lateinit var server: HttpServer
    private lateinit var gateway: AttendanceCancellationStatusGateway
    private var calls = 0

    @Volatile private var code = 200

    @Volatile private var body = VALID

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/internal/") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            assertEquals("/internal/expos/$EXPO/participants/2/cancellation-status", exchange.requestURI.path)
            assertEquals(null, exchange.requestURI.query)
            assertEquals("attendance-token", exchange.requestHeaders.getFirst("X-Internal-Token"))
            assertEquals("application/json", exchange.requestHeaders.getFirst("Content-Type"))
            assertEquals("""{"phoneNumber":"01012345678"}""", exchange.requestBody.bufferedReader().use { it.readText() })
            calls++
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(code, if (code == 204) -1 else bytes.size.toLong())
            exchange.responseBody.use { if (code != 204) it.write(bytes) }
        }
        server.start()
        gateway = AttendanceCancellationStatusGateway(jacksonObjectMapper(), "http://127.0.0.1:${server.address.port}/", "attendance-token")
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    @Test
    fun `merged Attendance 93 read-only status contract carries identity attendance and fence state`() {
        assertEquals(
            AttendanceCancellationStatusGateway.Status(2, false, false, false, false, null, "NONE"),
            gateway.find(EXPO, 2, "01012345678"),
        )
        body =
            VALID
                .replace("\"entered\":false", "\"entered\":true")
                .replace("\"traineeLinked\":false", "\"traineeLinked\":true")
                .replace("\"programAttended\":false", "\"programAttended\":true")
                .replace("\"linkedTraineeId\":null", "\"linkedTraineeId\":502")
                .replace("\"guardState\":null", "\"guardState\":\"HELD\"")
        assertEquals(
            AttendanceCancellationStatusGateway.Status(2, false, true, true, true, 502, "HELD"),
            gateway.find(EXPO, 2, "01012345678"),
        )
        assertEquals(2, calls)
        body = VALID.replace(",\"guardState\":null", "")
        assertEquals("NONE", gateway.find(EXPO, 2, "01012345678").guardState)
    }

    @Test
    fun `malformed incomplete inconsistent and wrong-identity snapshots never imply eligibility`() {
        for (response in listOf(
            "{}",
            "null",
            "[]",
            "{",
            VALID.replace("\"participantId\":2", "\"participantId\":3"),
            VALID.replace("\"participantId\":2", "\"participantId\":\"2\""),
            VALID.replace("\"participantId\":2", "\"participantId\":9223372036854775808"),
            VALID.replace("\"entered\":false,", ""),
            VALID.replace("\"entered\":false", "\"entered\":\"false\""),
            VALID.replace("\"programAttended\":false", "\"programAttended\":\"true\""),
            VALID.replace("\"representative\":false,", ""),
            VALID.replace("\"traineeLinked\":false", "\"traineeLinked\":true"),
            VALID.replace("\"linkedTraineeId\":null", "\"linkedTraineeId\":0"),
            VALID.replace("\"linkedTraineeId\":null", "\"linkedTraineeId\":9223372036854775808"),
            VALID.replace(",\"linkedTraineeId\":null", ""),
            VALID.replace("\"guardState\":null", "\"guardState\":\"UNKNOWN\""),
        )) {
            body = response
            failure(HttpStatus.BAD_GATEWAY) { gateway.find(EXPO, 2, "01012345678") }
        }
    }

    @Test
    fun `legacy endpoint absence provider failure and unexpected success fail closed`() {
        for ((response, expected) in listOf(
            201 to HttpStatus.BAD_GATEWAY,
            204 to HttpStatus.BAD_GATEWAY,
            400 to HttpStatus.BAD_GATEWAY,
            401 to HttpStatus.BAD_GATEWAY,
            409 to HttpStatus.BAD_GATEWAY,
            404 to HttpStatus.SERVICE_UNAVAILABLE,
            500 to HttpStatus.SERVICE_UNAVAILABLE,
            503 to HttpStatus.SERVICE_UNAVAILABLE,
        )) {
            code = response
            failure(expected) { gateway.find(EXPO, 2, "01012345678") }
        }
    }

    @Test
    fun `invalid input missing configuration and lost connection cannot yield a snapshot`() {
        assertThrows(IllegalArgumentException::class.java) { gateway.find(EXPO, 0, "01012345678") }
        assertThrows(IllegalArgumentException::class.java) { gateway.find(EXPO, 2, " ") }
        assertThrows(IllegalArgumentException::class.java) { gateway.find(EXPO, 2, "1".repeat(21)) }
        failure(HttpStatus.SERVICE_UNAVAILABLE) {
            AttendanceCancellationStatusGateway(jacksonObjectMapper(), "", "attendance-token").find(EXPO, 2, "01012345678")
        }
        assertEquals(0, calls)
        server.stop(0)
        failure(HttpStatus.SERVICE_UNAVAILABLE) { gateway.find(EXPO, 2, "01012345678") }
    }

    private fun failure(
        expected: HttpStatus,
        action: () -> Unit,
    ) {
        assertEquals(expected, assertThrows(ResponseStatusException::class.java, action).statusCode)
    }

    companion object {
        private val EXPO = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000045")
        private val VALID =
            """{"participantId":2,"representative":false,"entered":false,"programAttended":false,"traineeLinked":false,"linkedTraineeId":null,"guardState":null}"""
    }
}
