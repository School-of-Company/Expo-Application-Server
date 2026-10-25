package team.startup.application

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.service.impl.FieldCancellationGateway
import team.startup.application.domain.application.service.impl.FieldCancellationTarget
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.time.LocalDate
import java.util.UUID

class FieldCancellationGatewayTests {
    private val mapper = jacksonObjectMapper()
    private lateinit var server: HttpServer
    private lateinit var gateway: FieldCancellationGateway
    private val requests = mutableListOf<Triple<String, String, String>>()
    private var code = 204
    private var response = ""
    private val target = FieldCancellationTarget(UUID.randomUUID(), UUID.randomUUID(), 2, LocalDate.of(2026, 10, 22), 3)
    private val operation = UUID.randomUUID()

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/internal/") { exchange ->
            assertEquals("target-token", exchange.requestHeaders.getFirst("X-Internal-Token"))
            assertEquals(null, exchange.requestURI.query)
            requests.add(
                Triple(exchange.requestMethod, exchange.requestURI.path, exchange.requestBody.bufferedReader().use { it.readText() }),
            )
            if (code == 200) {
                val bytes = response.toByteArray()
                exchange.sendResponseHeaders(code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                exchange.sendResponseHeaders(code, -1)
            }
            exchange.close()
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}"
        gateway = FieldCancellationGateway(mapper, url, "target-token", url, "target-token")
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `guard and User projection retain exact visit generation and operation on every retry`() {
        gateway.acquire(target, operation, "01000000000")
        gateway.commit(target, operation)
        gateway.commit(target, operation)
        gateway.projectCancellation(target, operation)
        gateway.abort(target, operation)
        val prefix = "/internal/expos/${target.expoId}/participants/2/field-visits/${target.registrationId}"
        assertEquals(listOf("PUT", "POST", "POST", "PUT", "DELETE"), requests.map { it.first })
        assertEquals(
            listOf(
                "$prefix/cancellation-guards/$operation",
                "$prefix/cancellation-guards/$operation/commit",
                "$prefix/cancellation-guards/$operation/commit",
                prefix,
                "$prefix/cancellation-guards/$operation",
            ),
            requests.map { it.second },
        )
        requests.forEach {
            val body = mapper.readTree(it.third)
            assertEquals("2026-10-22", body.path("visitDate").asString())
            assertEquals(3, body.path("generation").asInt())
        }
        val projection = mapper.readTree(requests[3].third)
        assertEquals("CANCELLED", projection.path("status").asString())
        assertEquals(operation.toString(), projection.path("operationId").asString())
    }

    @Test
    fun `status requires echoed identity date generation and explicit authoritative flags`() {
        code = 200
        val valid =
            mapOf(
                "registrationId" to target.registrationId.toString(),
                "participantId" to 2,
                "visitDate" to "2026-10-22",
                "generation" to 3,
                "enteredOnVisitDate" to false,
                "programAttended" to false,
                "guardState" to "NONE",
                "linkedTraineeId" to null,
            )
        response = mapper.writeValueAsString(valid)
        assertEquals(false, gateway.status(target, "01000000000").enteredOnVisitDate)
        for ((key, value) in listOf(
            "visitDate" to "2026-10-21",
            "generation" to 2,
            "participantId" to 99,
            "registrationId" to UUID.randomUUID().toString(),
            "enteredOnVisitDate" to "false",
            "linkedTraineeId" to -1,
            "guardState" to "UNKNOWN",
        )) {
            response = mapper.writeValueAsString(valid + (key to value))
            failure(HttpStatus.BAD_GATEWAY) { gateway.status(target, "01000000000") }
        }
        response = mapper.writeValueAsString(valid - "enteredOnVisitDate")
        failure(HttpStatus.BAD_GATEWAY) { gateway.status(target, "01000000000") }
        response = "{"
        failure(HttpStatus.BAD_GATEWAY) { gateway.status(target, "01000000000") }
    }

    @Test
    fun `missing providers authentication and uncertain responses never pretend cancellation succeeded`() {
        for ((value, expected) in listOf(
            404 to HttpStatus.SERVICE_UNAVAILABLE,
            501 to HttpStatus.SERVICE_UNAVAILABLE,
            503 to HttpStatus.SERVICE_UNAVAILABLE,
            409 to HttpStatus.CONFLICT,
            401 to HttpStatus.BAD_GATEWAY,
            200 to HttpStatus.BAD_GATEWAY,
        )) {
            code = value
            // 상태마다 새 게이트웨이를 쓴다. 하나로 이어 부르면 앞의 5xx가 회로를 열어 뒤의 정상 거절이 503으로 보인다.
            gateway =
                FieldCancellationGateway(
                    mapper,
                    "http://127.0.0.1:${server.address.port}",
                    "target-token",
                    "http://127.0.0.1:${server.address.port}",
                    "target-token",
                )
            failure(expected) { gateway.acquire(target, operation, "01000000000") }
            failure(expected) { gateway.commit(target, operation) }
            failure(expected) { gateway.projectCancellation(target, operation) }
            failure(expected) { gateway.abort(target, operation) }
        }
        failure(HttpStatus.SERVICE_UNAVAILABLE) {
            FieldCancellationGateway(mapper, "", "", "", "").commit(target, operation)
        }
    }

    private fun failure(
        expected: HttpStatus,
        action: () -> Unit,
    ) = assertEquals(expected, assertThrows(ResponseStatusException::class.java, action).statusCode)
}
