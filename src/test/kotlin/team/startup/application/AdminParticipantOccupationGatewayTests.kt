package team.startup.application

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AdminOccupation
import team.startup.application.domain.application.service.impl.AdminParticipantGateway
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class AdminParticipantOccupationGatewayTests {
    private val mapper = jacksonObjectMapper()
    private val expoId = UUID.randomUUID()
    private val requests = CopyOnWriteArrayList<JsonNode>()
    private val paths = CopyOnWriteArrayList<String>()
    private val tokens = CopyOnWriteArrayList<String>()
    private lateinit var server: HttpServer
    private lateinit var gateway: AdminParticipantGateway
    private var status = 200
    private var responseBody: String? = null

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            paths.add("${exchange.requestMethod}:${exchange.requestURI}")
            tokens.add(exchange.requestHeaders.getFirst("X-Internal-Token"))
            val request = mapper.readTree(exchange.requestBody.readAllBytes())
            requests.add(request)
            val ids = request["participantIds"]
            val body =
                responseBody ?: mapper.writeValueAsString(
                    (0 until ids.size()).map { mapOf("participantId" to ids[it].asLong(), "occupation" to "TEACHER") },
                )
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        gateway = AdminParticipantGateway(mapper, "http://127.0.0.1:${server.address.port}", "user-token")
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    @Test
    fun `직업 조회는 중복을 제거하고 행사와 전용 토큰을 담아 만 명씩 배치로 읽는다`() {
        val ids = (1L..10001L).toList()
        val result = gateway.occupations(expoId, ids.reversed() + 1L)

        assertEquals(ids.associateWith { AdminOccupation.TEACHER }, result)
        assertEquals(listOf(10000, 1), requests.map { it["participantIds"].size() })
        assertEquals(ids, requests.flatMap { it["participantIds"].toList() }.map { it.asLong() })
        assertTrue(requests.all { it["expoId"].stringValue() == expoId.toString() })
        assertEquals(List(2) { "POST:/internal/standard-participants/details" }, paths)
        assertEquals(List(2) { "user-token" }, tokens)
    }

    @Test
    fun `명시적인 null 직업은 미상으로 유지하고 매번 최신 원천을 읽는다`() {
        responseBody = """[{"participantId":1,"occupation":"TEACHER"},{"participantId":2,"occupation":null}]"""
        assertEquals(mapOf(1L to AdminOccupation.TEACHER, 2L to null), gateway.occupations(expoId, listOf(1, 2)))
        responseBody = """[{"participantId":1,"occupation":"GENERAL"},{"participantId":2,"occupation":null}]"""
        assertEquals(mapOf(1L to AdminOccupation.GENERAL, 2L to null), gateway.occupations(expoId, listOf(1, 2)))
        assertEquals(2, requests.size)
    }

    @Test
    fun `확정자가 없으면 User 설정 없이도 빈 결과를 반환한다`() {
        assertEquals(emptyMap<Long, AdminOccupation?>(), AdminParticipantGateway(mapper, "", "").occupations(expoId, emptyList()))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `참가자 누락 중복 순서와 ID 직업 타입 계약 오류는 요약용 조회 전체를 실패시킨다`() {
        val second = """{"participantId":2,"occupation":null}"""
        val first = """{"participantId":1,"occupation":"TEACHER"}"""
        val invalid =
            listOf(
                "not-json",
                "{}",
                "[]",
                "[$first]",
                "[$first,$first]",
                "[$second,$first]",
                "[$first,$second,$second]",
                """[{"participantId":3,"occupation":"TEACHER"},$second]""",
                """[{"occupation":"TEACHER"},$second]""",
                """[{"participantId":"1","occupation":"TEACHER"},$second]""",
                """[{"participantId":1.0,"occupation":"TEACHER"},$second]""",
                """[{"participantId":9223372036854775808,"occupation":"TEACHER"},$second]""",
                """[{"participantId":1},$second]""",
                """[{"participantId":1,"occupation":1},$second]""",
                """[{"participantId":1,"occupation":false},$second]""",
                """[{"participantId":1,"occupation":{}},$second]""",
                """[{"participantId":1,"occupation":[]},$second]""",
                """[{"participantId":1,"occupation":"UNKNOWN"},$second]""",
                """[{"participantId":1,"occupation":""},$second]""",
            )
        invalid.forEach { body ->
            responseBody = body
            val failure = assertThrows(ResponseStatusException::class.java) { gateway.occupations(expoId, listOf(1, 2)) }
            assertEquals(HttpStatus.BAD_GATEWAY, failure.statusCode, body)
        }
    }

    @Test
    fun `다른 행사의 ID를 포함한 원천 거절은 502 원천 장애는 503으로 유지한다`() {
        for (code in listOf(400, 401, 403, 404, 409, 429, 500, 502, 503)) {
            status = code
            val failure = assertThrows(ResponseStatusException::class.java) { gateway.occupations(expoId, listOf(1)) }
            assertEquals(if (code >= 500) HttpStatus.SERVICE_UNAVAILABLE else HttpStatus.BAD_GATEWAY, failure.statusCode)
        }
    }

    @Test
    fun `연결 실패는 503이며 유효하지 않은 참가자 ID는 원천 호출 전에 거부한다`() {
        val unavailable = AdminParticipantGateway(mapper, "http://127.0.0.1:1", "user-token")
        val failure = assertThrows(ResponseStatusException::class.java) { unavailable.occupations(expoId, listOf(1)) }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.statusCode)
        for (id in listOf(0L, -1L)) {
            assertThrows(IllegalArgumentException::class.java) { gateway.occupations(expoId, listOf(id)) }
        }
        assertTrue(requests.isEmpty())
    }
}
