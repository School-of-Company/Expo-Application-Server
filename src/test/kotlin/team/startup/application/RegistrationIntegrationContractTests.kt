package team.startup.application

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import team.startup.application.domain.application.presentation.RegistrationController
import team.startup.application.domain.application.service.impl.RegisterStandardFieldServiceImpl
import team.startup.application.domain.application.service.impl.RegisterStandardPreServiceImpl
import team.startup.application.domain.application.service.impl.RegisterTraineeFieldServiceImpl
import team.startup.application.domain.application.service.impl.RegisterTraineePreServiceImpl
import team.startup.application.domain.application.service.impl.RegistrationGateway
import team.startup.application.domain.application.service.impl.RegistrationWorkflow
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.assertEquals

class RegistrationIntegrationContractTests {
    @Test
    fun `네 등록 경로는 현재 서비스 HTTP 계약과 동반자 스냅샷을 보존하고 집계 실패를 같은 키로 재시도한다`() {
        val mapper = jacksonObjectMapper()
        val expoId = "0199c000-0000-7000-8000-000000000001"
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val now = OffsetDateTime.now()
        val start = now.minusDays(1).toString()
        val end = now.plusDays(1).toString()
        val requests = mutableListOf<Pair<String, JsonNode>>()
        val counts = mutableListOf<String>()
        val queries = mutableListOf<String>()
        var countAvailable = true
        var failedParticipantId: Long? = null
        var standardResponse: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/internal/") { exchange ->
            val path = exchange.requestURI.path
            val (code, response) =
                when {
                    path.contains("/standard-registrations/") -> {
                        assertEquals("PUT", exchange.requestMethod)
                        assertEquals("expo-token", exchange.requestHeaders.getFirst("X-Internal-Token"))
                        counts.add(path)
                        (if (countAvailable && !path.endsWith("/$failedParticipantId")) 204 else 503) to ""
                    }

                    path.startsWith("/internal/expo/") -> {
                        assertEquals("GET", exchange.requestMethod)
                        assertEquals("expo-token", exchange.requestHeaders.getFirst("X-Internal-Token"))
                        200 to """{"startedDay":"$today","finishedDay":"$today"}"""
                    }

                    path.startsWith("/internal/forms/") -> {
                        assertEquals("GET", exchange.requestMethod)
                        assertEquals("form-token", exchange.requestHeaders.getFirst("X-Internal-Token"))
                        queries.add(exchange.requestURI.query)
                        if (exchange.requestURI.query.contains("applicationType=FIELD")) {
                            404 to ""
                        } else {
                            val fields =
                                if (exchange.requestURI.query.contains("type=STANDARD")) {
                                    """[{"id":9,"title":"동반자","formType":"COMPANION","jsonData":{},"otherJson":{"maxSelection":5},"dynamicFormType":"DEFAULT"}]"""
                                } else {
                                    "[]"
                                }
                            200 to """{"id":"$expoId","startDate":"$start","endDate":"$end","dynamicForm":$fields}"""
                        }
                    }

                    else -> {
                        assertEquals("POST", exchange.requestMethod)
                        assertEquals("user-token", exchange.requestHeaders.getFirst("X-Internal-Token"))
                        val sent = mapper.readTree(exchange.requestBody.bufferedReader().readText())
                        requests.add(path to sent)
                        val idField = if (path == "/internal/trainees") "traineeId" else "participantId"
                        val payload = if (path == "/internal/standard-participants") standardResponse else null
                        (if (requests.count { it.second.get("requestId") == sent.get("requestId") } > 1) 200 else 201) to
                            (payload ?: """{"$idField":42,"phoneNumber":"01012345678"}""")
                    }
                }
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}"
            val workflow =
                RegistrationWorkflow(RegistrationGateway(mapper, url, url, url, "shared", "expo-token", "form-token", "user-token"), mapper)
            val controller =
                RegistrationController(
                    RegisterTraineePreServiceImpl(workflow),
                    RegisterStandardPreServiceImpl(workflow),
                    RegisterTraineeFieldServiceImpl(workflow),
                    RegisterStandardFieldServiceImpl(workflow),
                    true,
                )
            val mvc = MockMvcBuilders.standaloneSetup(controller).build()
            val answers = """{"동반자":[{"name":"동반자","school":"광주고"}]}"""
            val body =
                mapper.writeValueAsString(
                    mapOf(
                        "name" to "홍길동",
                        "phoneNumber" to "01012345678",
                        "informationJson" to answers,
                        "personalInformationStatus" to true,
                        "trainingId" to "training-1",
                    ),
                )
            val routes = listOf("" to "TRAINEE", "/pre-standard" to "STANDARD", "/field" to "TRAINEE", "/field/standard" to "STANDARD")
            for ((index, route) in routes.withIndex()) {
                mvc
                    .perform(
                        post(
                            "/application${route.first}/$expoId",
                        ).header("Idempotency-Key", "request-$index").contentType(MediaType.APPLICATION_JSON).content(body),
                    ).andExpect(status().isCreated)
                val (path, sent) = requests.last()
                assertEquals(if (route.second == "STANDARD") "/internal/standard-participants" else "/internal/trainees", path)
                assertEquals(if (index < 2) "PRE" else "FIELD", sent.get("applicationType").stringValue())
                assertEquals("request-$index", sent.get("requestId").stringValue())
                assertEquals(answers, sent.get("informationJson").stringValue())
                assertEquals("type=${route.second}&applicationType=${if (index < 2) "PRE" else "FIELD"}", queries.last())
                if (index == 1) {
                    assertEquals(
                        "COMPANION",
                        sent
                            .get("questions")
                            .get(0)
                            .get("formType")
                            .stringValue(),
                    )
                    assertEquals(
                        0,
                        sent
                            .get("questions")
                            .get(0)
                            .get("order")
                            .intValue(),
                    )
                    assertEquals(mapper.readTree("{\"maxSelection\":5}"), sent.get("questions").get(0).get("otherJson"))
                } else if (index >= 2) {
                    assertEquals(mapper.readTree("null"), sent.get("questions"))
                    assertEquals(mapper.readTree("null"), sent.get("formId"))
                } else {
                    assertEquals(mapper.readTree("[]"), sent.get("questions"))
                }
            }
            assertEquals(List(2) { "/internal/expo/$expoId/standard-registrations/42" }, counts)
            countAvailable = false
            mvc
                .perform(
                    post(
                        "/application/field/standard/$expoId",
                    ).header("Idempotency-Key", "retry").contentType(MediaType.APPLICATION_JSON).content(body),
                ).andExpect(status().isServiceUnavailable)
            countAvailable = true
            mvc
                .perform(
                    post(
                        "/application/field/standard/$expoId",
                    ).header("Idempotency-Key", "retry").contentType(MediaType.APPLICATION_JSON).content(body),
                ).andExpect(status().isCreated)
            assertEquals(requests[4], requests[5])
            assertEquals(4, counts.size)

            standardResponse =
                """[{"id":42,"code":"representative-code","representative":true},{"id":43,"code":"companion-code-1","representative":false},{"id":44,"code":"companion-code-2","representative":false}]"""
            failedParticipantId = 43
            mvc
                .perform(
                    post(
                        "/application/field/standard/$expoId",
                    ).header("Idempotency-Key", "group-retry").contentType(MediaType.APPLICATION_JSON).content(body),
                ).andExpect(status().isServiceUnavailable)
            assertEquals(listOf(42L, 43L), counts.drop(4).map { it.substringAfterLast('/').toLong() })
            failedParticipantId = null
            mvc
                .perform(
                    post(
                        "/application/field/standard/$expoId",
                    ).header("Idempotency-Key", "group-retry").contentType(MediaType.APPLICATION_JSON).content(body),
                ).andExpect(status().isCreated)
            assertEquals(listOf(42L, 43L, 42L, 43L, 44L), counts.drop(4).map { it.substringAfterLast('/').toLong() })
            assertEquals(requests[6], requests[7])

            standardResponse =
                (42..46).joinToString(prefix = "[", postfix = "]") { "{\"id\":$it,\"code\":\"code-$it\",\"representative\":${it == 42}}" }
            mvc
                .perform(
                    post(
                        "/application/field/standard/$expoId",
                    ).header("Idempotency-Key", "group-five").contentType(MediaType.APPLICATION_JSON).content(body),
                ).andExpect(status().isCreated)
            assertEquals((42L..46L).toList(), counts.takeLast(5).map { it.substringAfterLast('/').toLong() })

            val countBeforeInvalidResponse = counts.size
            for (invalid in listOf(
                "[]",
                "[{\"id\":0}]",
                "[{\"id\":-1}]",
                "[{\"id\":42},{\"id\":42}]",
                "[{}]",
                "[{\"id\":\"42\"}]",
                "[{\"id\":1.5}]",
                "[{\"id\":9223372036854775808}]",
                "[{\"id\":1},{\"id\":2},{\"id\":3},{\"id\":4},{\"id\":5},{\"id\":6}]",
            )) {
                standardResponse = invalid
                mvc
                    .perform(
                        post(
                            "/application/field/standard/$expoId",
                        ).header("Idempotency-Key", "invalid-list").contentType(MediaType.APPLICATION_JSON).content(body),
                    ).andExpect(status().isBadGateway)
                assertEquals(countBeforeInvalidResponse, counts.size)
            }
        } finally {
            server.stop(0)
        }
    }
}
