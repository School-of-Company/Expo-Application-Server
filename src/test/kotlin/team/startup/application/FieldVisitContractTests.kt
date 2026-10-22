package team.startup.application

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.FieldVisitRegistration
import team.startup.application.domain.application.presentation.dto.NewFieldParticipant
import team.startup.application.domain.application.presentation.dto.RegisterFieldVisitRequest
import team.startup.application.domain.application.service.impl.ParticipantIdsMismatchException
import team.startup.application.domain.application.service.impl.RegistrationField
import team.startup.application.domain.application.service.impl.RegistrationForm
import team.startup.application.domain.application.service.impl.RegistrationGateway
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FieldVisitContractTests {
    @Test
    fun `신규 참가자는 client reference로 전원 매핑하고 기존 프로필 저장 경로를 호출하지 않는다`() {
        fixture { gateway, requests, respond ->
            val expoId = UUID.randomUUID()
            val request =
                RegisterFieldVisitRequest(listOf(1), listOf(NewFieldParticipant("new-1", "새사람", "GENERAL", null, "GWANGJU")), true)
            respond(200, """[{"participantId":2,"clientReference":"new-1"}]""")
            assertEquals(2, gateway.resolveFieldParticipants(expoId, "01000000000", "fixed-key", request).single().participantId)
            assertTrue(requests.single().first.endsWith("/field-visit-participants"))
            val body = jacksonObjectMapper().readTree(requests.single().second)
            assertEquals("fixed-key", body.path("requestId").stringValue())
            assertFalse(body.has("applicationType"))
            respond(200, "[]")
            assertThrows<ParticipantIdsMismatchException> { gateway.resolveFieldParticipants(expoId, "01000000000", "fixed-key", request) }
            respond(200, """[{"participantId":1,"clientReference":"new-1"}]""")
            assertThrows<ParticipantIdsMismatchException> { gateway.resolveFieldParticipants(expoId, "01000000000", "fixed-key", request) }
        }
    }

    @Test
    fun `신규 생성은 본인 답변과 제출 당시 폼 문항을 함께 전달한다`() {
        fixture { gateway, requests, respond ->
            val mapper = jacksonObjectMapper()
            val expoId = UUID.randomUUID()
            val formId = UUID.randomUUID().toString()
            val form =
                RegistrationForm(
                    formId,
                    "2000-01-01T00:00:00Z",
                    "2100-01-01T00:00:00Z",
                    listOf(RegistrationField(5, "직업", "SELECT", mapper.readTree("[]"), null, "OCCUPATION")),
                )
            val person = NewFieldParticipant("new", "신규", "GENERAL", null, "GWANGJU", """{"직업":"GENERAL"}""")
            val request = RegisterFieldVisitRequest(emptyList(), listOf(person), true)
            respond(200, """[{"participantId":2,"clientReference":"new"}]""")
            gateway.resolveFieldParticipants(expoId, "01000000000", "fixed-key", request, form)
            val payload = mapper.readTree(requests.single().second)
            assertEquals(formId, payload.path("formId").stringValue())
            assertEquals(
                5,
                payload
                    .path("questions")
                    .get(0)
                    .path("id")
                    .longValue(),
            )
            assertEquals(
                "직업",
                payload
                    .path("questions")
                    .get(0)
                    .path("title")
                    .stringValue(),
            )
            assertEquals(
                person.informationJson,
                payload
                    .path("participants")
                    .get(0)
                    .path("informationJson")
                    .stringValue(),
            )
        }
    }

    @Test
    fun `방문일 projection은 날짜와 세대를 보존하고 같은 operation으로 재전송한다`() {
        fixture { gateway, requests, respond ->
            val visit =
                FieldVisitRegistration(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    42,
                    LocalDate.of(2026, 10, 22),
                    "ACTIVE",
                    3,
                    Instant.now(),
                    null,
                    null,
                    false,
                    true,
                )
            respond(204, "")
            gateway.projectFieldVisit(visit)
            gateway.projectFieldVisit(visit)
            assertEquals(4, requests.size)
            assertTrue(requests.all { it.first.endsWith("/participants/42/field-visits/${visit.id}") })
            assertEquals(1, requests.map { it.second }.distinct().size)
            val body = jacksonObjectMapper().readTree(requests.first().second)
            assertEquals("2026-10-22", body.path("visitDate").stringValue())
            assertEquals(3, body.path("generation").longValue())
            assertTrue(body.path("operationId").isString)
            assertFalse(body.has("name"))
        }
    }

    @Test
    fun `과거 소유 일행은 활성 5명 제한과 별개로 6명 이상 조회할 수 있다`() {
        fixture { gateway, _, respond ->
            val participants =
                (1..6).joinToString(",") {
                    """{"participantId":$it,"name":"기존","occupation":"GENERAL","representative":${it == 1},"applicationType":"FIELD"}"""
                }
            respond(200, """{"registered":true,"participants":[$participants]}""")
            assertEquals(6, gateway.managedParty(UUID.randomUUID(), "session").participants.size)
        }
    }

    @Test
    fun `미구현 또는 비정상 projection은 성공을 반환하지 않는다`() {
        fixture { gateway, _, respond ->
            val visit =
                FieldVisitRegistration(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    42,
                    LocalDate.now(),
                    "ACTIVE",
                    1,
                    Instant.now(),
                    null,
                    null,
                    false,
                )
            for (status in listOf(404, 405, 501)) {
                respond(status, "{}")
                assertEquals(503, assertThrows<ResponseStatusException> { gateway.projectFieldVisit(visit) }.statusCode.value())
            }
            respond(200, "{}")
            assertEquals(502, assertThrows<ResponseStatusException> { gateway.projectFieldVisit(visit) }.statusCode.value())
        }
    }

    private fun fixture(action: (RegistrationGateway, MutableList<Pair<String, String>>, (Int, String) -> Unit) -> Unit) {
        val requests = mutableListOf<Pair<String, String>>()
        var status = 204
        var body = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/internal/") { exchange ->
            assertEquals("target-token", exchange.requestHeaders.getFirst("X-Internal-Token"))
            val sent = exchange.requestBody.bufferedReader().use { it.readText() }
            requests.add(exchange.requestURI.path to sent)
            val session = exchange.requestURI.path.endsWith("/participant-sessions/resolve")
            val response =
                if (session) {
                    val expoId = jacksonObjectMapper().readTree(sent).path("expoId").stringValue()
                    """{"expoId":"$expoId","phoneNumber":"01000000000","expiresAt":"2100-01-01T00:00:00"}"""
                } else {
                    body
                }
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(if (session) 200 else status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}"
            val gateway =
                RegistrationGateway(
                    jacksonObjectMapper(),
                    url,
                    url,
                    url,
                    "target-token",
                    "target-token",
                    "target-token",
                    attendanceUrl = url,
                    attendanceToken = "target-token",
                )
            action(gateway, requests) { code, response ->
                status = code
                body = response
            }
        } finally {
            server.stop(0)
        }
    }
}
