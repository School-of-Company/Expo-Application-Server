package team.startup.application

import com.sun.net.httpserver.HttpServer
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.RegistrationController
import team.startup.application.domain.application.repository.PreregisterReservationRepository
import team.startup.application.domain.application.service.impl.ParticipantRegistration
import team.startup.application.domain.application.service.impl.PreregisterRegistrationWorkflow
import team.startup.application.domain.application.service.impl.RegisterStandardFieldServiceImpl
import team.startup.application.domain.application.service.impl.RegisterStandardPreServiceImpl
import team.startup.application.domain.application.service.impl.RegisterTraineeFieldServiceImpl
import team.startup.application.domain.application.service.impl.RegisterTraineePreServiceImpl
import team.startup.application.domain.application.service.impl.RegistrationGateway
import team.startup.application.domain.application.service.impl.RegistrationWorkflow
import team.startup.application.domain.application.service.impl.SessionAttendanceOutbox
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals

class RegistrationIntegrationContractTests {
    @Test
    fun `네 등록 경로는 행사 설정과 세션을 확인한 후 인증 전화번호만 저장한다`() {
        val mapper = jacksonObjectMapper()
        val expoId = "0199c000-0000-7000-8000-000000000001"
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val requests = mutableListOf<String>()
        val saved = mutableListOf<JsonNode>()
        var expoStatus = 200
        var dates = """"startedDay":"$today","finishedDay":"$today""""
        var setting = """"participantPhoneVerificationRequired":true,"participantPhoneVerificationRevision":1"""
        var sessionStatus = 200
        var sessionBody = """{"expoId":"$expoId","phoneNumber":"01099998888","expiresAt":"2100-01-01T00:00:00"}"""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/internal/") { exchange ->
            val path = exchange.requestURI.path
            requests.add(path)
            val (code, body) =
                when {
                    path == "/internal/expo/$expoId" -> {
                        expoStatus to "{$dates,$setting}"
                    }

                    path == "/internal/participant-sessions/resolve" -> {
                        assertEquals("user-token", exchange.requestHeaders.getFirst("X-Internal-Token"))
                        val sent = mapper.readTree(exchange.requestBody.bufferedReader().use { it.readText() })
                        assertEquals(expoId, sent.path("expoId").stringValue())
                        sessionStatus to sessionBody
                    }

                    path.startsWith("/internal/forms/") -> {
                        200 to
                            """{"id":"$expoId","startDate":"2000-01-01T00:00:00Z","endDate":"2100-01-01T00:00:00Z","dynamicForm":[]}"""
                    }

                    path.endsWith("/standard-registrations/42") -> {
                        204 to ""
                    }

                    else -> {
                        saved.add(mapper.readTree(exchange.requestBody.bufferedReader().use { it.readText() }))
                        val id = if (path == "/internal/trainees") "traineeId" else "participantId"
                        201 to """{"$id":42,"phoneNumber":"01099998888"}"""
                    }
                }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}"
            val gateway = RegistrationGateway(mapper, url, url, url, "expo-token", "form-token", "user-token")
            val preregistration = org.mockito.Mockito.mock(PreregisterRegistrationWorkflow::class.java)
            org.mockito.Mockito
                .doAnswer { invocation ->
                    gateway.participant("STANDARD", invocation.getArgument(1))
                    null
                }.`when`(preregistration)
                .register(
                    org.mockito.ArgumentMatchers.any() ?: team.startup.application.domain.application.service.RegistrationCommand(
                        expoId,
                        team.startup.application.domain.application.presentation
                            .RegistrationRequest("person", "010", "{}", true),
                        null,
                    ),
                    org.mockito.ArgumentMatchers.any() ?: ParticipantRegistration(
                        expoId,
                        "person",
                        "010",
                        "{}",
                        true,
                        "PRE",
                        null,
                        null,
                        null,
                        "key",
                        null,
                        null,
                    ),
                    org.mockito.ArgumentMatchers.anyInt(),
                )
            val workflow =
                RegistrationWorkflow(
                    gateway,
                    mapper,
                    preregistration,
                    org.mockito.Mockito.mock(SessionAttendanceOutbox::class.java),
                    org.mockito.Mockito.mock(PreregisterReservationRepository::class.java),
                )
            val mvc =
                MockMvcBuilders
                    .standaloneSetup(
                        RegistrationController(
                            RegisterTraineePreServiceImpl(workflow),
                            RegisterStandardPreServiceImpl(workflow),
                            RegisterTraineeFieldServiceImpl(workflow),
                            RegisterStandardFieldServiceImpl(workflow),
                            true,
                            "CUSTOM_SESSION",
                        ),
                    ).build()
            val body =
                """{"name":"person","phoneNumber":"01012345678","informationJson":"{}","personalInformationStatus":true,"trainingId":"training-1","sessionId":1}"""
            for (route in listOf("", "/pre-standard", "/field", "/field/standard")) {
                // 일반 사전등록은 행사 전날까지만 신규 접수한다(#171).
                val startedDay = if (route == "/pre-standard") today.plusDays(1) else today
                dates = """"startedDay":"$startedDay","finishedDay":"$startedDay""""

                fun submit(sessionCookie: String? = "valid") =
                    mvc.perform(
                        post("/application$route/$expoId")
                            .header("Idempotency-Key", "fixed-key")
                            .apply { if (sessionCookie != null) cookie(Cookie("CUSTOM_SESSION", sessionCookie)) }
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body),
                    )
                requests.clear()
                val savedBefore = saved.size
                submit(null).andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("SESSION_REQUIRED"))
                assertEquals(listOf("/internal/expo/$expoId"), requests)
                for (cookie in listOf("", "expired", "wrong-event")) {
                    requests.clear()
                    sessionStatus = 401
                    sessionBody = """{"code":"${if (cookie == "expired") "SESSION_EXPIRED" else "SESSION_REQUIRED"}"}"""
                    val result =
                        submit(cookie)
                            .andExpect(status().isUnauthorized)
                            .andExpect(jsonPath("$.code").value(if (cookie == "expired") "SESSION_EXPIRED" else "SESSION_REQUIRED"))
                            .andReturn()
                    assertEquals(
                        if (cookie == "expired") "SESSION_EXPIRED" else "SESSION_REQUIRED",
                        (result.resolvedException as ResponseStatusException).reason,
                    )
                    assertEquals(if (cookie.isEmpty()) 1 else 2, requests.size)
                }
                for ((code, payload, expected) in listOf(
                    Triple(503, "{}", 503),
                    Triple(403, "{}", 502),
                    Triple(200, "{}", 502),
                    Triple(200, """{"expoId":"wrong-event","phoneNumber":"01099998888","expiresAt":"2100-01-01T00:00:00"}""", 502),
                    Triple(200, """{"expoId":"$expoId","phoneNumber":"01099998888","expiresAt":"2000-01-01T00:00:00"}""", 401),
                )) {
                    requests.clear()
                    sessionStatus = code
                    sessionBody = payload
                    submit().andExpect(status().`is`(expected))
                    assertEquals(listOf("/internal/expo/$expoId", "/internal/participant-sessions/resolve"), requests)
                }
                assertEquals(savedBefore, saved.size)
                sessionStatus = 200
                sessionBody = """{"expoId":"$expoId","phoneNumber":"01099998888","expiresAt":"2100-01-01T00:00:00"}"""
                requests.clear()
                submit().andExpect(status().isCreated)
                assertEquals("01099998888", saved.last().path("phoneNumber").stringValue())
                assertEquals("fixed-key", saved.last().path("requestId").stringValue())
                assertEquals(listOf("/internal/expo/$expoId", "/internal/participant-sessions/resolve"), requests.take(2))

                for (invalid in listOf(
                    "\"participantPhoneVerificationRequired\":false",
                    "\"participantPhoneVerificationRequired\":\"false\",\"participantPhoneVerificationRevision\":0",
                    "\"participantPhoneVerificationRequired\":null,\"participantPhoneVerificationRevision\":0",
                    "\"participantPhoneVerificationRequired\":false,\"participantPhoneVerificationRevision\":-1",
                    "\"participantPhoneVerificationRequired\":false,\"participantPhoneVerificationRevision\":0.5",
                    "\"participantPhoneVerificationRequired\":false,\"participantPhoneVerificationRevision\":9223372036854775808",
                )) {
                    requests.clear()
                    setting = invalid
                    submit().andExpect(status().isBadGateway)
                    assertEquals(listOf("/internal/expo/$expoId"), requests)
                }
                setting = """"participantPhoneVerificationRequired":false,"participantPhoneVerificationRevision":0"""
                for ((code, expected) in listOf(401 to 502, 404 to 404, 409 to 409, 503 to 503, 201 to 502)) {
                    requests.clear()
                    expoStatus = code
                    submit().andExpect(status().`is`(expected))
                    assertEquals(listOf("/internal/expo/$expoId"), requests)
                }
                expoStatus = 200
                requests.clear()
                submit(null).andExpect(status().isCreated)
                assertEquals("01012345678", saved.last().path("phoneNumber").stringValue())
                kotlin.test.assertFalse(requests.contains("/internal/participant-sessions/resolve"))
                assertEquals(savedBefore + 2, saved.size)
                setting = """"participantPhoneVerificationRequired":true,"participantPhoneVerificationRevision":1"""
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `User 세션 조회는 행사 전화번호 만료를 검증하고 실패 뒤 소유권 조회를 하지 않는다`() {
        val mapper = jacksonObjectMapper()
        val expoId = UUID.fromString("0199c000-0000-7000-8000-000000000001")
        val requests = mutableListOf<String>()
        var sessionStatus = 200
        var sessionBody = """{"expoId":"$expoId","phoneNumber":"01012345678","expiresAt":"2100-01-01T00:00:00"}"""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/internal/") { exchange ->
            val path = exchange.requestURI.path
            requests.add(path)
            assertEquals("POST", exchange.requestMethod)
            assertEquals("user-token", exchange.requestHeaders.getFirst("X-Internal-Token"))
            assertEquals(null, exchange.requestURI.query)
            val sent = mapper.readTree(exchange.requestBody.bufferedReader().use { it.readText() })
            assertEquals(expoId.toString(), sent.path("expoId").stringValue())
            val resolving = path == "/internal/participant-sessions/resolve"
            if (resolving) {
                assertEquals("opaque-session", sent.path("sessionId").stringValue())
            } else {
                assertEquals("01012345678", sent.path("phoneNumber").stringValue())
            }
            val body = if (resolving) sessionBody else """{"registered":false,"participants":[]}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(if (resolving) sessionStatus else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}"
            val gateway = RegistrationGateway(mapper, url, url, url, "expo-token", "form-token", "user-token")
            gateway.managedParty(expoId, "opaque-session")
            assertEquals(listOf("/internal/participant-sessions/resolve", "/internal/standard-participants/managed"), requests)
            requests.clear()
            for (invalid in listOf(
                "null",
                "{}",
                """{"expoId":"${UUID.randomUUID()}","phoneNumber":"01012345678","expiresAt":"2100-01-01T00:00:00"}""",
                """{"expoId":"$expoId","phoneNumber":123,"expiresAt":"2100-01-01T00:00:00"}""",
                """{"expoId":"$expoId","phoneNumber":"010-1234-5678","expiresAt":"2100-01-01T00:00:00"}""",
                """{"expoId":"$expoId","phoneNumber":"01012345678"}""",
                """{"expoId":"$expoId","phoneNumber":"01012345678","expiresAt":null}""",
                """{"expoId":"$expoId","phoneNumber":"01012345678","expiresAt":"invalid"}""",
            )) {
                sessionBody = invalid
                val failure = assertThrows<ResponseStatusException> { gateway.managedParty(expoId, "opaque-session") }
                assertEquals(HttpStatus.BAD_GATEWAY, failure.statusCode)
                assertEquals(listOf("/internal/participant-sessions/resolve"), requests)
                requests.clear()
            }
            sessionBody = """{"expoId":"$expoId","phoneNumber":"01012345678","expiresAt":"2000-01-01T00:00:00"}"""
            val expired = assertThrows<ResponseStatusException> { gateway.managedParty(expoId, "opaque-session") }
            assertEquals(HttpStatus.UNAUTHORIZED, expired.statusCode)
            assertEquals("SESSION_EXPIRED", expired.reason)
            requests.clear()
            for ((upstream, expected, reason) in listOf(
                Triple(401, HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED"),
                Triple(401, HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED"),
                Triple(503, HttpStatus.SERVICE_UNAVAILABLE, null),
                Triple(403, HttpStatus.BAD_GATEWAY, null),
            )) {
                sessionStatus = upstream
                sessionBody = mapper.writeValueAsString(mapOf("code" to reason))
                val failure = assertThrows<ResponseStatusException> { gateway.managedParty(expoId, "opaque-session") }
                assertEquals(expected, failure.statusCode)
                if (reason != null) assertEquals(reason, failure.reason)
                assertEquals(listOf("/internal/participant-sessions/resolve"), requests)
                requests.clear()
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `네 등록 경로는 현재 서비스 HTTP 계약과 동반자 스냅샷을 보존하고 집계 실패를 같은 키로 재시도한다`() {
        val mapper = jacksonObjectMapper()
        val expoId = "0199c000-0000-7000-8000-000000000001"
        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val now = OffsetDateTime.now()
        val start = now.minusDays(1).toString()
        val end = now.plusDays(1).toString()
        var startedDay = today
        val requests = mutableListOf<Pair<String, JsonNode>>()
        val counts = mutableListOf<String>()
        val queries = mutableListOf<String>()
        var countAvailable = true
        var failedParticipantId: Long? = null
        var standardResponse: String? = null
        var traineeResponse: String? = null
        var userStatus: Int? = null
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
                        200 to
                            """{"startedDay":"$startedDay","finishedDay":"$startedDay","participantPhoneVerificationRequired":false,"participantPhoneVerificationRevision":0}"""
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
                                    """[{"id":9,"title":"동반자","formType":"COMPANION","jsonData":{},"otherJson":{"maxSelection":5},"dynamicFormType":"DEFAULT"},{"id":10,"title":"거주 지역","formType":"REGION","requiredStatus":true,"jsonData":{},"otherJson":null,"dynamicFormType":"DEFAULT"}]"""
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
                        val payload = if (path == "/internal/standard-participants") standardResponse else traineeResponse
                        (userStatus ?: if (requests.count { it.second.get("requestId") == sent.get("requestId") } > 1) 200 else 201) to
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
            val gateway = RegistrationGateway(mapper, url, url, url, "expo-token", "form-token", "user-token")
            val preregistration = org.mockito.Mockito.mock(PreregisterRegistrationWorkflow::class.java)
            org.mockito.Mockito
                .doAnswer { invocation ->
                    val registration = invocation.getArgument<ParticipantRegistration>(1)
                    gateway.participant("STANDARD", registration).groupParticipantIds.forEach { gateway.countStandard(expoId, it) }
                    null
                }.`when`(preregistration)
                .register(
                    org.mockito.ArgumentMatchers.any() ?: team.startup.application.domain.application.service.RegistrationCommand(
                        expoId,
                        team.startup.application.domain.application.presentation
                            .RegistrationRequest("person", "010", "{}", true),
                        null,
                    ),
                    org.mockito.ArgumentMatchers.any() ?: ParticipantRegistration(
                        expoId,
                        "person",
                        "010",
                        "{}",
                        true,
                        "PRE",
                        null,
                        null,
                        null,
                        "key",
                        null,
                        null,
                    ),
                    org.mockito.ArgumentMatchers.anyInt(),
                )
            val workflow =
                RegistrationWorkflow(
                    gateway,
                    mapper,
                    preregistration,
                    org.mockito.Mockito.mock(SessionAttendanceOutbox::class.java),
                    org.mockito.Mockito.mock(PreregisterReservationRepository::class.java),
                )
            val controller =
                RegistrationController(
                    RegisterTraineePreServiceImpl(workflow),
                    RegisterStandardPreServiceImpl(workflow),
                    RegisterTraineeFieldServiceImpl(workflow),
                    RegisterStandardFieldServiceImpl(workflow),
                    true,
                )
            val mvc = MockMvcBuilders.standaloneSetup(controller).build()
            val answers = """{"거주 지역":"JEONNAM","동반자":[{"name":"동반자","occupation":"TEACHER","region":"GWANGJU","school":"광주고"}]}"""
            val body =
                mapper.writeValueAsString(
                    mapOf(
                        "name" to "홍길동",
                        "phoneNumber" to "01012345678",
                        "informationJson" to answers,
                        "personalInformationStatus" to true,
                        "trainingId" to "training-1",
                        "sessionId" to 1,
                    ),
                )
            val routes = listOf("" to "TRAINEE", "/pre-standard" to "STANDARD", "/field" to "TRAINEE", "/field/standard" to "STANDARD")
            for ((index, route) in routes.withIndex()) {
                // 일반 사전등록은 행사 전날까지만 신규 접수한다(#171).
                startedDay = if (route.first == "/pre-standard") today.plusDays(1) else today
                mvc
                    .perform(
                        post(
                            "/application${route.first}/$expoId",
                        ).header("Idempotency-Key", "request-$index").contentType(MediaType.APPLICATION_JSON).content(body),
                    ).andExpect(status().isCreated)
                    .andExpect(content().string(""))
                val (path, sent) = requests.last()
                assertEquals(if (route.second == "STANDARD") "/internal/standard-participants" else "/internal/trainees", path)
                assertEquals(if (index < 2) "PRE" else "FIELD", sent.get("applicationType").stringValue())
                assertEquals("request-$index", sent.get("requestId").stringValue())
                assertEquals(answers, sent.get("informationJson").stringValue())
                assertEquals("type=${route.second}&applicationType=${if (index < 2) "PRE" else "FIELD"}", queries.last())
                if (index == 1) {
                    assertEquals("JEONNAM", sent.path("region").stringValue())
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
            startedDay = today
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
                """{"participantId":42,"phoneNumber":"01012345678","participantIds":[42,43,44]}"""
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
                .andExpect(content().string(""))
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
                .andExpect(content().string(""))
            assertEquals((42L..46L).toList(), counts.takeLast(5).map { it.substringAfterLast('/').toLong() })

            standardResponse = """{"participantId":42,"phoneNumber":"01012345678","participantIds":[44],"groupParticipantIds":[42,43,44]}"""
            mvc
                .perform(
                    post("/application/field/standard/$expoId")
                        .header("Idempotency-Key", "new-companions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                ).andExpect(status().isCreated)
            assertEquals(listOf(42L, 43L, 44L), counts.takeLast(3).map { it.substringAfterLast('/').toLong() })

            val countBeforeInvalidResponse = counts.size
            for (invalid in listOf(
                "null",
                "{\"participantId\":42,\"phoneNumber\":\"01012345678\",\"participantIds\":[]}",
                "{\"participantId\":0,\"phoneNumber\":\"01012345678\",\"participantIds\":[43]}",
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
            val requestsBeforeInvalidRegion = requests.size
            startedDay = today.plusDays(1)
            for (region in listOf("\"SEOUL\"", "\" JEONNAM \"", "123", "null", "[]", "{}", "\"\"")) {
                mvc
                    .perform(
                        post("/application/pre-standard/$expoId")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                mapper.writeValueAsString(
                                    mapOf(
                                        "name" to "홍길동",
                                        "phoneNumber" to "01012345678",
                                        "informationJson" to "{\"거주 지역\":$region}",
                                        "personalInformationStatus" to true,
                                        "sessionId" to 1,
                                    ),
                                ),
                            ),
                    ).andExpect(status().isBadRequest)
            }
            assertEquals(requestsBeforeInvalidRegion, requests.size)
            startedDay = today

            for (invalid in listOf(
                "null",
                "{}",
                """{"traineeId":0,"phoneNumber":"01012345678"}""",
                """{"traineeId":-1,"phoneNumber":"01012345678"}""",
            )) {
                traineeResponse = invalid
                mvc
                    .perform(post("/application/field/$expoId").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadGateway)
            }
            for ((upstream, expected) in listOf(
                400 to 502,
                422 to 502,
                401 to 502,
                403 to 502,
                404 to 404,
                409 to 409,
                500 to 503,
                503 to 503,
            )) {
                userStatus = upstream
                for (route in listOf("/field/standard", "/field")) {
                    mvc
                        .perform(post("/application$route/$expoId").contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().`is`(expected))
                }
            }
            assertEquals(countBeforeInvalidResponse, counts.size)
        } finally {
            server.stop(0)
        }
    }
}
