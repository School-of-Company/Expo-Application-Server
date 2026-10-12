package team.startup.application

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.ProgramFormSnapshot
import team.startup.application.domain.application.service.impl.RegistrationGateway
import team.startup.application.domain.application.service.impl.trainingId
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID

class ManagedParticipantContractTests {
    private val mapper = ObjectMapper()

    @Test
    fun `participant trainee link sends nullable training ID and reuses authoritative User ID`() {
        val requests = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            assertEquals("/internal/trainees/resolve-or-create-by-participant", exchange.requestURI.path)
            requests.add(exchange.requestBody.bufferedReader().use { it.readText() })
            val bytes = """{"traineeId":501,"created":false}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val gateway =
                RegistrationGateway(mapper, "", "", "http://127.0.0.1:${server.address.port}", "expo-token", "form-token", "user-token")
            assertEquals(501L, gateway.resolveTrainee("expo", 1))
            assertEquals(501L, gateway.resolveTrainee("expo", 1, "academy-1"))
            assertEquals(501L, gateway.resolveTrainee("expo", 1, "academy-1"))
            assertEquals(null, mapper.readTree(requests[0]).get("trainingId").stringValue())
            assertEquals("academy-1", mapper.readTree(requests[1]).path("trainingId").stringValue())
            assertEquals(requests[1], requests[2])
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `QR uses Attendance token and representative ownership with strict participant binding`() {
        val expo = UUID.randomUUID()
        var response = """{"participantId":2,"code":"private-code"}"""
        val requests = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.add(exchange.requestURI.path)
            assertEquals("attendance-token", exchange.requestHeaders.getFirst("X-Internal-Token"))
            assertEquals("POST", exchange.requestMethod)
            assertEquals(mapper.readTree("""{"representativeId":1}"""), mapper.readTree(exchange.requestBody.readAllBytes()))
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val gateway =
                RegistrationGateway(
                    mapper,
                    "",
                    "",
                    "",
                    "expo-token",
                    "form-token",
                    "user-token",
                    attendanceUrl = "http://127.0.0.1:${server.address.port}",
                    attendanceToken = "attendance-token",
                )
            val qr = gateway.participantQr(expo, 2, 1)
            assertEquals("private-code", qr.code)
            assertFalse(qr.toString().contains(qr.code))
            for (invalid in listOf("""{"participantId":3,"code":"private-code"}""", """{"participantId":2,"code":""}""")) {
                response = invalid
                assertEquals(
                    502,
                    assertThrows(ResponseStatusException::class.java) { gateway.participantQr(expo, 2, 1) }.statusCode.value(),
                )
            }
            assertEquals(List(3) { "/internal/expos/$expo/participants/2/qr" }, requests)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `training ID comes only from typed program form question and remains nullable`() {
        val form =
            ProgramFormSnapshot(
                UUID.randomUUID().toString(),
                UUID.randomUUID(),
                "PROGRAM",
                1,
                Instant.EPOCH,
                Instant.MAX,
                mapper.readTree("""[{"id":7,"dynamicFormType":"TRAINING_ID","formType":"SENTENCE"}]"""),
            )
        assertEquals("academy-1", form.trainingId(mapper.readTree("""{"7":"academy-1"}""")))
        for (answers in listOf("{}", """{"7":null}""", """{"7":""}""")) {
            assertEquals(null, form.trainingId(mapper.readTree(answers)))
        }
        assertThrows(ResponseStatusException::class.java) { form.trainingId(mapper.readTree("""{"7":123}""")) }
        assertThrows(ResponseStatusException::class.java) { form.trainingId(mapper.valueToTree(mapOf("7" to "x".repeat(16)))) }
        assertEquals(null, form.copy(questions = mapper.readTree("[]")).trainingId(mapper.readTree("""{"trainingId":"untrusted"}""")))
        assertEquals(null, form.copy(questions = mapper.readTree("""[{"id":7,"title":"Legacy"}]""")).trainingId(mapper.readTree("{}")))
    }
}
