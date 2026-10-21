package team.startup.application

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition
import team.startup.application.domain.application.presentation.dto.ProgramSessionPolicy
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "application.internal-token=test-token",
        "application.attendance.enabled=true",
        "application.program-sessions.enabled=true",
        "application.owner.cancellation-recovery-delay-ms=3600000",
        "application.attendance.poll-delay-ms=3600000",
    ],
)
@AutoConfigureMockMvc
@Testcontainers
class AdminOperationsHttpTests {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    private val expo = UUID.randomUUID()
    private val application = UUID.randomUUID()
    private val waiting = UUID.randomUUID()
    private val group = UUID.randomUUID()
    private val path get() = "/application/admin/expos/$expo/program-session-applications/$application/cancellation"

    @BeforeEach
    fun seed() {
        calls.clear()
        accessStatus = 200
        accessBody = null
        userStatus = 200
        userBody = null
        entered = false
        linkedTrainee = null
        attendees = "[]"
        attendanceStatus = 200
        messageStatus = 200
        messageBody = """{"items":[]}"""
        historyStatus = 200
        historyBody = null
        acquireStarted = null
        acquireRelease = null
        val now = Instant.now()
        definition =
            ProgramSessionDefinition(
                expo.toString(),
                ProgramSessionType.STANDARD,
                7,
                group,
                1,
                ProgramSessionPolicy(
                    UUID.randomUUID(),
                    setOf("TEACHER"),
                    1,
                    5,
                    now.minusSeconds(3600),
                    now.plusSeconds(3600),
                    false,
                    now.plusSeconds(3600),
                    now.plusSeconds(3600),
                ),
                startedAt = now.plusSeconds(7200).toString(),
                endedAt = now.plusSeconds(10800).toString(),
            )
        jdbc.execute(
            "TRUNCATE tb_program_session_application, tb_program_session_state, tb_standard_program_application, tb_training_program_application, tb_owner_cancellation_delivery, tb_preregister_application, tb_preregister_session_state CASCADE",
        )
        // Promotion requires the waiting participant's confirmed preregistration (#117).
        jdbc.update("INSERT INTO tb_preregister_session_state (expo_id, session_id, definition) VALUES (?, 1, '{}')", expo)
        jdbc.update(
            "INSERT INTO tb_preregister_application (expo_id, session_id, representative_id, participant_id, status) " +
                "VALUES (?, 1, 1, 1, 'CONFIRMED'), (?, 1, 1, 2, 'CONFIRMED')",
            expo,
            expo,
        )
        insert(application, 1, "CONFIRMED")
        insert(waiting, 2, "WAITING")
        jdbc.update("INSERT INTO tb_standard_program_application (participant_id, standard_program_id) VALUES (1, 7)")
        observe()
    }

    private fun insert(
        id: UUID,
        participant: Long,
        status: String,
        owner: UUID = expo,
        type: String = "STANDARD",
        trainee: Long? = null,
        route: String? = null,
    ) {
        jdbc.update(
            "INSERT INTO tb_program_session_application (id, expo_id, program_type, program_id, group_id, participant_id, trainee_id, status, definition_snapshot, form_id, form_version, questions_snapshot, answers_snapshot, admission_route) VALUES (?, ?, ?, 7, ?, ?, ?, ?, '{}', ?, 1, '[]', '{}', ?)",
            id,
            owner,
            type,
            group,
            participant,
            trainee,
            status,
            UUID.randomUUID(),
            route,
        )
        jdbc.update("INSERT INTO tb_program_session_history (application_id, status) VALUES (?, ?)", id, status)
    }

    private fun observe() {
        jdbc.update("DELETE FROM tb_program_session_state")
        jdbc.update(
            "INSERT INTO tb_program_session_state (expo_id, program_type, program_id, definition) VALUES (?, ?, 7, CAST(? AS jsonb))",
            expo,
            definition.programType.name,
            mapper.writeValueAsString(definition),
        )
    }

    private fun cancel(
        authorization: String? = "Bearer original-admin-jwt",
        route: String = path,
    ) = mvc
        .perform(
            post(route).apply {
                if (authorization !=
                    null
                ) {
                    header("Authorization", authorization)
                }
            },
        ).andReturn()
        .response

    private fun status(id: UUID = application) =
        jdbc.queryForObject("SELECT status FROM tb_program_session_application WHERE id = ?", String::class.java, id)

    @Test
    fun `관리자 취소는 쓰기 권한 행사 소속과 검증 응답을 강제한다`() {
        assertEquals(401, cancel(null).status)
        assertEquals(401, cancel("Basic ignored").status)
        for (code in listOf(401, 403, 404, 503)) {
            accessStatus = code
            assertEquals(code, cancel().status)
        }
        accessStatus = 200
        for (body in listOf(
            "{}",
            "not-json",
            """{"expoId":"$expo","adminId":1,"permission":"APPLICATION_READ","policyVersion":"GLOBAL_ADMIN_APPLICATION_READ_V1","scope":"GLOBAL_ADMIN"}""",
            """{"expoId":"${UUID.randomUUID()}","adminId":1,"permission":"APPLICATION_CANCEL","policyVersion":"EXPO_ADMIN_APPLICATION_CANCEL_V1","scope":"EXPO_ADMIN"}""",
        )) {
            accessBody = body
            assertEquals(502, cancel().status)
        }
        accessBody = null
        val other = UUID.randomUUID()
        insert(other, 3, "CONFIRMED", UUID.randomUUID())
        assertEquals(404, cancel(route = path.replace(application.toString(), other.toString())).status)
        assertFalse(calls.any { it.startsWith("USER") || it.startsWith("PUT") })
        assertEquals("CONFIRMED", status())
    }

    private fun denied(
        route: String,
        body: String? = null,
    ) = mvc
        .perform(
            post(route).header("Authorization", "Bearer original-admin-jwt").apply {
                body?.let { contentType(MediaType.APPLICATION_JSON).content(it) }
            },
        ).andReturn()
        .response

    // #267: Expo가 행사 권한 행이 없다고 거절한 403만 허용한 코드와 문구로 전달하고, 다른 원천 문구는 싣지 않는다.
    @Test
    fun `행사 권한이 없는 관리자 취소와 수동 확정 403은 허용한 사유 코드만 전달한다`() {
        val preregistration = "/application/admin/expos/$expo/preregistrations/cancellation"
        val keys = """{"keys":["APPLICATION:$expo:1"]}"""
        accessStatus = 403
        accessBody = """{"status":403,"message":"신청 취소 권한이 없습니다."}"""
        for ((route, body) in listOf(path to null, preregistration to keys)) {
            val response = denied(route, body)
            assertEquals(403, response.status)
            val json = mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8))
            assertEquals("APPLICATION_CANCEL_FORBIDDEN", json["code"].asString())
            assertEquals("신청 취소 권한이 없습니다.", json["detail"].asString())
        }
        accessBody = """{"status":403,"message":"수동 확정 권한이 없습니다."}"""
        val promotion = denied("/application/admin/expos/$expo/program-session-applications/$waiting/promotion")
        assertEquals(403, promotion.status)
        assertEquals("APPLICATION_PROMOTE_FORBIDDEN", mapper.readTree(promotion.contentAsString)["code"].asString())
        for ((code, message) in listOf(
            403 to "수동 확정 권한이 없습니다.",
            403 to "접근 권한이 없습니다.",
            401 to "internal-detail",
            404 to "박람회를 찾지 못 했습니다.",
        )) {
            accessStatus = code
            accessBody = """{"status":$code,"message":"$message","code":"APPLICATION_CANCEL_FORBIDDEN"}"""
            val response = denied(path)
            assertEquals(code, response.status)
            val text = response.getContentAsString(StandardCharsets.UTF_8)
            assertFalse(text.contains(message) || text.contains("FORBIDDEN"), text)
            assertEquals(code, denied(preregistration, keys).status)
        }
        assertEquals("CONFIRMED", status())
        assertEquals("WAITING", status(waiting))
    }

    @Test
    fun `확정 취소는 관리자 이력을 기록하고 대기를 승급하며 출석 보호를 걸지 않는다`() {
        val response = cancel()
        assertEquals(200, response.status)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertEquals("CANCELLED", mapper.readTree(response.contentAsString)["status"].asString())
        assertEquals("CANCELLED", status())
        assertEquals("CONFIRMED", status(waiting))
        assertEquals(
            listOf("ADMIN:CONFIRMED:CANCELLED:true:null", "SYSTEM:WAITING:CONFIRMED:true:null"),
            jdbc.query(
                "SELECT actor_type, previous_status, status, previous_status_recorded, actor_participant_id " +
                    "FROM tb_program_session_history " +
                    "WHERE actor_type <> 'UNKNOWN' ORDER BY id",
            ) { rs, _ -> "${rs.getString(1)}:${rs.getString(2)}:${rs.getString(3)}:${rs.getBoolean(4)}:${rs.getString(5)}" },
        )
        assertEquals(
            1L,
            jdbc.queryForObject(
                "SELECT actor_id FROM tb_program_session_history WHERE application_id = ? AND status = 'CANCELLED'",
                Long::class.java,
                application,
            ),
        )
        assertEquals(listOf("GET /internal/program-attendances/standard/7"), calls.filter { it.startsWith("GET") })
        assertFalse(calls.any { it.contains("cancellation-guard") })
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM tb_owner_cancellation_delivery", Int::class.java))
        assertEquals(200, cancel().status)
        assertEquals(1, calls.count { it.startsWith("GET") })
        accessStatus = 403
        assertEquals(403, cancel().status)
    }

    @Test
    fun `대기 신청 취소는 확정 신청과 좌석을 유지한다`() {
        jdbc.update("UPDATE tb_program_session_application SET participant_id = 3 WHERE id = ?", application)
        jdbc.update("UPDATE tb_standard_program_application SET participant_id = 3")
        jdbc.update("UPDATE tb_program_session_application SET participant_id = 1 WHERE id = ?", waiting)
        assertEquals(200, cancel(route = path.replace(application.toString(), waiting.toString())).status)
        assertEquals("CANCELLED", status(waiting))
        assertEquals("CONFIRMED", status())
        assertEquals(
            1,
            jdbc.queryForObject("SELECT count(*) FROM tb_standard_program_application WHERE participant_id = 3", Int::class.java),
        )
    }

    @Test
    fun `연수 확정 취소는 현재 연결을 확인하고 연수 원장 버전을 갱신한다`() {
        jdbc.update("UPDATE tb_program_session_application SET status = 'CANCELLED' WHERE id = ?", waiting)
        jdbc.update("UPDATE tb_program_session_application SET program_type = 'TRAINING', trainee_id = 10 WHERE id = ?", application)
        jdbc.update("INSERT INTO tb_training_program_application (trainee_id, training_program_id) VALUES (10, 7)")
        definition = definition.copy(programType = ProgramSessionType.TRAINING)
        observe()
        linkedTrainee = 11
        assertEquals(409, cancel().status)
        assertFalse(calls.any { it.startsWith("PUT") })
        linkedTrainee = 10
        assertEquals(200, cancel().status)
        assertEquals("CANCELLED", status())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM tb_training_program_application WHERE trainee_id = 10", Int::class.java))
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM tb_training_application_version WHERE trainee_id = 10", Long::class.java))
    }

    @Test
    fun `취소 트랜잭션 실패는 기존 원장과 대기를 보존한다`() {
        jdbc.update("UPDATE tb_program_session_state SET deleted = true WHERE expo_id = ?", expo)
        assertEquals(409, cancel().status)
        assertEquals("CONFIRMED", status())
        assertEquals("WAITING", status(waiting))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM tb_owner_cancellation_delivery", Int::class.java))
    }

    @Test
    fun `입장한 참가자의 현장 신청도 취소 마감 뒤 취소하고 좌석은 신청 경로의 풀로 돌아간다`() {
        // 현장 1석(참가자 1), 사전 1석(참가자 3)이 차 있고 참가자 2가 사전 대기 중이다(#261).
        definition =
            definition.copy(
                policy =
                    definition.policy!!.copy(
                        capacity = 2,
                        preRegistrationCapacity = 1,
                        onsiteCapacity = 1,
                        cancelEndedAt = Instant.now().minusSeconds(1),
                    ),
            )
        observe()
        jdbc.update("DELETE FROM tb_program_session_history WHERE application_id = ?", application)
        jdbc.update("DELETE FROM tb_program_session_application WHERE id = ?", application)
        insert(application, 1, "CONFIRMED", route = "ONSITE")
        val pre = UUID.randomUUID()
        insert(pre, 3, "CONFIRMED", route = "PRE_REGISTRATION")
        jdbc.update("INSERT INTO tb_standard_program_application (participant_id, standard_program_id) VALUES (3, 7)")
        entered = true
        assertEquals(200, cancel().status)
        assertEquals("CANCELLED", status())
        assertEquals("WAITING", status(waiting))
        assertEquals(200, cancel(route = path.replace(application.toString(), pre.toString())).status)
        assertEquals("CANCELLED", status(pre))
        assertEquals("CONFIRMED", status(waiting))
    }

    @Test
    fun `신청 마감 뒤 취소는 빈자리를 승급시키지 않는다`() {
        definition = definition.copy(policy = definition.policy!!.copy(applicationEndedAt = Instant.now().minusSeconds(1)))
        observe()
        assertEquals(200, cancel().status)
        assertEquals("WAITING", status(waiting))
    }

    @Test
    fun `그 프로그램 출석과 원천 실패만 관리자 취소를 막는다`() {
        entered = true
        attendees = """[{"participantId":1,"entryTime":"09:00","leaveTime":null}]"""
        assertEquals(409, cancel().status)
        attendees = "[]"
        for (code in listOf(503, 404)) {
            attendanceStatus = code
            assertEquals(if (code == 503) 503 else 502, cancel().status)
        }
        attendanceStatus = 200
        attendees = """[{"traineeId":1}]"""
        assertEquals(502, cancel().status)
        attendees = "[]"
        for (code in listOf(404, 401, 503)) {
            userStatus = code
            assertEquals(if (code == 401) 502 else code, cancel().status)
        }
        userStatus = 200
        userBody = "{}"
        assertEquals(502, cancel().status)
        assertEquals("CONFIRMED", status())
        userBody = null
        attendees = """[{"participantId":2,"entryTime":"09:00","leaveTime":null}]"""
        assertEquals(200, cancel().status)
        assertEquals("CANCELLED", status())
        assertFalse(calls.any { it.contains("cancellation-guard") })
    }

    @Test
    fun `동시 취소는 한 출석 확인과 한 취소 이력만 남긴다`() {
        acquireStarted = CountDownLatch(1)
        acquireRelease = CountDownLatch(1)
        val first = CompletableFuture.supplyAsync { cancel().status }
        try {
            assertTrue(acquireStarted!!.await(5, TimeUnit.SECONDS))
            assertEquals(409, cancel().status)
        } finally {
            acquireRelease!!.countDown()
        }
        assertEquals(200, first.get(10, TimeUnit.SECONDS))
        assertEquals(1, calls.count { it.startsWith("GET") })
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM tb_program_session_history WHERE application_id = ? AND status = 'CANCELLED'",
                Int::class.java,
                application,
            ),
        )
        assertEquals("CONFIRMED", status(waiting))
    }

    @Test
    fun `Notification 이력은 매 페이지 JWT 재검증하고 오류를 빈 이력으로 숨기지 않는다`() {
        val route = "/application/expos/$expo/admin-group-messages/history"
        assertEquals(
            401,
            mvc
                .perform(get(route))
                .andReturn()
                .response.status,
        )
        accessStatus = 403
        assertEquals(
            403,
            mvc
                .perform(get(route).header("Authorization", "Bearer original-admin-jwt"))
                .andReturn()
                .response.status,
        )
        assertFalse(calls.any { it.startsWith("MESSAGE") })
        accessStatus = 200
        val eventId = UUID.randomUUID()
        val time = Instant.parse("2026-10-12T00:00:00Z")
        val jobId = "a".repeat(64)
        val item = """{
            "eventId":"$eventId","type":"REQUESTED","adminId":9,"occurredAt":"$time",
            "targetScope":{"kind":"EXPLICIT_TARGETS","applicationCount":2,"programTypes":["STANDARD"]},
            "counts":{"total":2,"pending":2,"accepted":0,"delivered":0,"failed":0,"unknown":0,"missing":0},
            "status":"QUEUED","jobId":"$jobId"
        }"""
        val cursor = "%013d:%s".format(time.toEpochMilli(), eventId)
        messageBody = """{"items":[$item],"nextCursor":"$cursor"}"""
        val response =
            mvc
                .perform(
                    get(
                        "$route?limit=1&type=REQUESTED&from=2026-10-11T00:00:00Z&to=2026-10-13T00:00:00Z",
                    ).header("Authorization", "Bearer original-admin-jwt"),
                ).andReturn()
                .response
        assertEquals(200, response.status)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertEquals(9, mapper.readTree(response.contentAsString)["items"][0]["adminId"].asInt())
        assertFalse(response.contentAsString.contains("phoneNumber"))
        messageBody = """{"items":[]}"""
        assertEquals(
            200,
            mvc
                .perform(
                    get("$route?limit=1&cursor=$cursor").header("Authorization", "Bearer original-admin-jwt"),
                ).andReturn()
                .response.status,
        )
        assertTrue(calls.any { it.startsWith("MESSAGE") && it.contains("cursor=") })
        accessStatus = 403
        val count = calls.count { it.startsWith("MESSAGE") }
        assertEquals(
            403,
            mvc
                .perform(get(route).header("Authorization", "Bearer original-admin-jwt"))
                .andReturn()
                .response.status,
        )
        assertEquals(count, calls.count { it.startsWith("MESSAGE") })
        accessStatus = 200
        for (code in listOf(401, 403, 400, 503)) {
            messageStatus = code
            assertEquals(
                if (code in
                    listOf(401, 403)
                ) {
                    502
                } else {
                    code
                },
                mvc
                    .perform(get(route).header("Authorization", "Bearer original-admin-jwt"))
                    .andReturn()
                    .response.status,
            )
        }
        messageStatus = 200
        for (body in listOf(
            "{}",
            "not-json",
            """{"items":[$item],"nextCursor":"bad"}""",
            """{"items":[${item.replace("EXPLICIT_TARGETS", "FILTERED") }]}""",
        )) {
            messageBody = body
            assertEquals(
                502,
                mvc
                    .perform(get(route).header("Authorization", "Bearer original-admin-jwt"))
                    .andReturn()
                    .response.status,
            )
        }
        for (query in listOf(
            "limit=101",
            "cursor=bad",
            "adminId=1",
            "from=2026-10-13T00:00:00Z&to=2026-10-12T00:00:00Z",
            "type=SENT&type=FAILED",
        )) {
            assertEquals(
                400,
                mvc
                    .perform(get("$route?$query").header("Authorization", "Bearer original-admin-jwt"))
                    .andReturn()
                    .response.status,
            )
        }
    }

    @Test
    fun `Expo 모집 이력은 원본 JWT와 행사 범위 필터 페이지를 전달한다`() {
        val route = "/application/admin/expos/$expo/recruitment-history"
        assertEquals(
            401,
            mvc
                .perform(get(route))
                .andReturn()
                .response.status,
        )
        val response =
            mvc
                .perform(
                    get("$route?type=PROGRAM_CREATE&size=1").header("Authorization", "Bearer original-admin-jwt"),
                ).andReturn()
                .response
        assertEquals(200, response.status)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertEquals(expo.toString(), mapper.readTree(response.contentAsString)["expoId"].asString())
        assertTrue(calls.any { it.startsWith("HISTORY Bearer original-admin-jwt") && it.contains("type=PROGRAM_CREATE") })
        accessStatus = 403
        val before = calls.count { it.startsWith("HISTORY") }
        assertEquals(
            403,
            mvc
                .perform(get(route).header("Authorization", "Bearer original-admin-jwt"))
                .andReturn()
                .response.status,
        )
        assertEquals(before, calls.count { it.startsWith("HISTORY") })
        accessStatus = 200
        historyStatus = 404
        assertEquals(
            404,
            mvc
                .perform(get(route).header("Authorization", "Bearer original-admin-jwt"))
                .andReturn()
                .response.status,
        )
        historyStatus = 200
        historyBody = """{"expoId":"${UUID.randomUUID()}","items":[],"page":0,"size":20,"totalElements":0}"""
        assertEquals(
            502,
            mvc
                .perform(get(route).header("Authorization", "Bearer original-admin-jwt"))
                .andReturn()
                .response.status,
        )
    }

    companion object {
        private val calls = CopyOnWriteArrayList<String>()
        private var accessStatus = 200
        private var accessBody: String? = null
        private var userStatus = 200
        private var userBody: String? = null
        private var entered = false
        private var linkedTrainee: Long? = null
        private var attendees = "[]"
        private var attendanceStatus = 200
        private var messageStatus = 200
        private var messageBody = """{"items":[]}"""
        private var historyStatus = 200
        private var historyBody: String? = null
        private var acquireStarted: CountDownLatch? = null
        private var acquireRelease: CountDownLatch? = null
        private lateinit var definition: ProgramSessionDefinition
        private val executor = Executors.newCachedThreadPool()
        private val source =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                executor = AdminOperationsHttpTests.executor
                createContext("/") { exchange ->
                    val path = exchange.requestURI.path
                    val auth = exchange.requestHeaders.getFirst("Authorization")
                    var code = 200
                    val body =
                        when {
                            path.endsWith("/admin-access") || path.endsWith("/admin-application-cancel-access") ||
                                path.endsWith("/admin-application-promote-access") -> {
                                assertEquals("expo-token", exchange.requestHeaders.getFirst("X-Internal-Token"))
                                calls.add("ACCESS $auth $path")
                                code = accessStatus
                                val expoId = path.split('/')[3]
                                val cancel = path.endsWith("/admin-application-cancel-access")
                                val permission = if (cancel) "APPLICATION_CANCEL" else "APPLICATION_READ"
                                val policy = if (cancel) "EXPO_ADMIN_APPLICATION_CANCEL_V1" else "GLOBAL_ADMIN_APPLICATION_READ_V1"
                                val scope = if (cancel) "EXPO_ADMIN" else "GLOBAL_ADMIN"
                                accessBody
                                    ?: """{
                                        "expoId":"$expoId","adminId":1,"permission":"$permission",
                                        "policyVersion":"$policy","scope":"$scope"
                                    }"""
                            }

                            path.endsWith("/admin-cancellation-status") -> {
                                assertEquals("user-token", exchange.requestHeaders.getFirst("X-Internal-Token"))
                                val request = exchange.requestBody.bufferedReader().readText()
                                calls.add("USER $request")
                                code = userStatus
                                val participantId = jacksonObjectMapper().readTree(request)["participantId"].asLong()
                                userBody
                                    ?: """{
                                        "participantId":$participantId,"entered":$entered,
                                        "traineeLinked":${linkedTrainee != null},"linkedTraineeId":$linkedTrainee
                                    }"""
                            }

                            path.contains("cancellation-guard") -> {
                                calls.add("${exchange.requestMethod} $path")
                                code = 500
                                ""
                            }

                            path.startsWith("/internal/program-attendances/") -> {
                                assertEquals("attendance-token", exchange.requestHeaders.getFirst("X-Internal-Token"))
                                calls.add("${exchange.requestMethod} $path")
                                acquireStarted?.countDown()
                                acquireRelease?.let { assertTrue(it.await(5, TimeUnit.SECONDS)) }
                                code = attendanceStatus
                                attendees
                            }

                            path.endsWith("/standard-programs/7") -> {
                                jacksonObjectMapper().writeValueAsString(definition)
                            }

                            path.endsWith("/training-programs/batch") -> {
                                "[${jacksonObjectMapper().writeValueAsString(definition)}]"
                            }

                            path.endsWith("/history") -> {
                                assertEquals("notification-token", exchange.requestHeaders.getFirst("X-Internal-Token"))
                                assertEquals(null, auth)
                                calls.add("MESSAGE ${exchange.requestURI}")
                                code = messageStatus
                                messageBody
                            }

                            path.endsWith("/admin-change-history") -> {
                                calls.add("HISTORY $auth ${exchange.requestURI}")
                                code = historyStatus
                                val expoId = path.split('/')[2]
                                val query = exchange.requestURI.query.orEmpty()
                                val size = Regex("size=(\\d+)").find(query)?.groupValues?.get(1) ?: "20"
                                historyBody
                                    ?: """{
                                        "expoId":"$expoId","items":[{
                                            "id":1,"type":"PROGRAM_CREATE","adminId":1,"occurredAt":"2026-10-12T00:00:00Z",
                                            "targetType":"STANDARD","targetId":7,"summary":"프로그램 생성"
                                        }],"page":0,"size":$size,"totalElements":1
                                    }"""
                            }

                            else -> {
                                code = 404
                                "{}"
                            }
                        }
                    val bytes = body.toByteArray()
                    exchange.sendResponseHeaders(code, if (code == 204) -1 else bytes.size.toLong())
                    if (code != 204) exchange.responseBody.use { it.write(bytes) }
                    exchange.close()
                }
                start()
            }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            val url = "http://127.0.0.1:${source.address.port}"
            for (prefix in listOf("application.registration", "application.admin-group-messaging")) {
                registry.add("$prefix.expo-url") { url }
                registry.add("$prefix.expo-internal-token") { "expo-token" }
                registry.add("$prefix.user-url") { url }
                registry.add("$prefix.user-internal-token") { "user-token" }
            }
            registry.add("application.admin-group-messaging.notification-url") { url }
            registry.add("application.admin-group-messaging.notification-internal-token") { "notification-token" }
            registry.add("application.attendance.url") { url }
            registry.add("application.attendance.internal-token") { "attendance-token" }
        }

        @AfterAll
        @JvmStatic
        fun stop() {
            source.stop(0)
            executor.shutdownNow()
        }

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
