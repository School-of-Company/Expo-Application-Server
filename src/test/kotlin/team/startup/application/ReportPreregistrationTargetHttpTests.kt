package team.startup.application

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.util.Base64
import java.util.UUID

/** #284 사전등록 원장 Report 원천. 합성 데이터만 사용한다. */
@SpringBootTest(properties = ["eureka.client.enabled=false", "application.internal-token=test-token"])
@AutoConfigureMockMvc
@Testcontainers
class ReportPreregistrationTargetHttpTests {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    private val expo = UUID.randomUUID()
    private val otherExpo = UUID.randomUUID()

    private fun base(expoId: UUID = expo) = "/internal/expos/$expoId/preregistrations/report-targets"

    private val morning =
        """{"id":1,"title":"오전","startedAt":"2026-10-21T00:00:00Z","endedAt":"2026-10-21T03:00:00Z","place":"A",""" +
            """"capacity":10,"teacherCapacity":6,"nonTeacherCapacity":4}"""
    private val afternoon =
        """{"id":2,"title":"오후","startedAt":"2026-10-21T05:00:00Z","endedAt":"2026-10-21T08:00:00Z","place":"B","capacity":10}"""
    private val submitted =
        """{"name":"합성대표","phoneNumber":"010-0000-0001","occupation":"TEACHER","informationJson":"{\"q1\":\"a\"}",""" +
            """"questions":[{"title":"q1"}],"personalInformationStatus":true,"requestId":"secret-user-request"}"""

    @BeforeEach
    fun seed() {
        userStatus = 200
        userParticipants = listOf(1L, 2L)
        jdbc.execute("TRUNCATE tb_preregister_session_state, tb_preregister_reservation, tb_preregister_deleted_expo CASCADE")
        jdbc.execute("TRUNCATE tb_report_application_snapshot")
        listOf(expo to 1L to morning, expo to 2L to afternoon, otherExpo to 1L to morning).forEach { (pair, definition) ->
            jdbc.update(
                "INSERT INTO tb_preregister_session_state (expo_id, session_id, definition) VALUES (?, ?, ?)",
                pair.first,
                pair.second,
                definition,
            )
        }
        // 대기열을 거쳐 확정된 일행(대표 1, 동행자 2)의 예약.
        reservation(expo, "promoted", 1, "COMPLETED", queue = 1, applicants = 2, teacher = 1, other = 1)
        reservation(expo, "waiting", 1, "WAITING", queue = 2, applicants = 3, teacher = null, other = null)
        reservation(expo, "addition", 2, "WAITING", queue = 3, applicants = 1, teacher = 0, other = 1, companionOf = 1)
        reservation(otherExpo, "waiting", 1, "WAITING", queue = 4, applicants = 1, teacher = 1, other = 0)
        application(expo, 1, 1, 1, "CONFIRMED", "promoted", "TEACHER")
        application(expo, 1, 1, 2, "CONFIRMED", "promoted", null)
        // 같은 사람 오후 회차는 별도 행이며 취소 이력을 남긴다. 연결 예약이 없는 과거 행이다.
        val cancelled = application(expo, 2, 1, 1, "CANCELLED", null, "OTHER")
        jdbc.update(
            "INSERT INTO tb_preregister_transition " +
                "(application_id, expo_id, session_id, participant_id, status, created_at, actor_type, actor_id) " +
                "VALUES (?, ?, 2, 1, 'CANCELLED', '2026-10-20T01:00:00Z', 'ADMIN', 9)",
            cancelled,
            expo,
        )
        application(otherExpo, 1, 1, 1, "CONFIRMED", null, null)
    }

    private fun reservation(
        expoId: UUID,
        id: String,
        session: Long,
        state: String,
        queue: Long?,
        applicants: Int,
        teacher: Int?,
        other: Int?,
        companionOf: Long? = null,
    ) {
        jdbc.update(
            "INSERT INTO tb_preregister_reservation (expo_id, request_id, session_id, command, user_request, applicants, state, " +
                "queue_seq, teacher_applicants, other_applicants, companion_representative_id) VALUES (?, ?, ?, '{}', ?, ?, ?, ?, ?, ?, ?)",
            expoId,
            id,
            session,
            submitted,
            applicants,
            state,
            queue,
            teacher,
            other,
            companionOf,
        )
    }

    private fun application(
        expoId: UUID,
        session: Long,
        representative: Long,
        participant: Long,
        status: String,
        reservation: String?,
        category: String?,
    ): Long {
        val id =
            jdbc.queryForObject(
                "INSERT INTO tb_preregister_application (expo_id, session_id, representative_id, participant_id, status, " +
                    "reservation_request_id, seat_category, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, '2026-10-19T00:00:00Z') RETURNING id",
                Long::class.java,
                expoId,
                session,
                representative,
                participant,
                status,
                reservation,
                category,
            )!!
        jdbc.update(
            "INSERT INTO tb_preregister_transition (application_id, expo_id, session_id, participant_id, status, created_at, actor_type) " +
                "VALUES (?, ?, ?, ?, 'CONFIRMED', '2026-10-19T00:00:00Z', 'SYSTEM')",
            id,
            expoId,
            session,
            participant,
        )
        return id
    }

    private fun send(
        path: String,
        body: String,
    ): MockHttpServletResponse =
        mvc
            .perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body).header("X-Internal-Token", "test-token"))
            .andReturn()
            .response

    private fun create(body: String = "{}") = send(base(), body)

    private fun next(
        cursor: String,
        expoId: UUID = expo,
    ) = mvc.perform(get(base(expoId)).param("cursor", cursor).header("X-Internal-Token", "test-token")).andReturn().response

    private fun json(response: MockHttpServletResponse): JsonNode {
        assertEquals(200, response.status, response.contentAsString)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        return mapper.readTree(response.contentAsString)
    }

    private fun JsonNode.list() = (0 until size()).map { this[it] }

    private fun JsonNode.value(field: String) = this[field].takeUnless { it.isNull }?.asString()

    private fun items(body: JsonNode) = (0 until body["items"].size()).map { body["items"][it] }

    @Test
    fun `참가자 회차 행과 예약 일행 행을 구분하고 이력 좌석 구분 스냅샷과 현재 프로필을 분리한다`() {
        val body = json(create())
        assertEquals(5, body["totalElements"].asInt())
        val rows = items(body)
        assertEquals(rows.map { it["key"].asString() }.distinct().size, rows.size)
        val confirmed = rows.filter { it.value("participantId") == "1" }
        // 같은 사람 오전 확정과 오후 취소는 별도 행이다.
        assertEquals(
            listOf(1L to "CONFIRMED", 2L to "CANCELLED"),
            confirmed.map {
                it["session"]["sessionId"].asLong() to
                    it["status"].asString()
            },
        )
        val rep = confirmed[0]
        assertTrue(rep["representative"].asBoolean())
        assertEquals("TEACHER", rep["seatCategory"].asString())
        assertEquals("오전", rep["session"]["title"].asString())
        assertEquals(6, rep["session"]["teacherCapacity"].asInt())
        assertTrue(rep["promotedFromWaiting"].asBoolean())
        assertEquals("합성대표", rep["registrationSnapshot"]["name"].asString())
        assertFalse(rep["registrationSnapshot"].has("requestId"))
        assertFalse(rep["registrationSnapshot"].has("personalInformationStatus"))
        assertEquals("Person 1", rep["participant"]["name"].asString())
        assertEquals(listOf("CONFIRMED"), rep["history"].list().map { it["status"].asString() })
        val companion = rows.single { it.value("participantId") == "2" }
        assertFalse(companion["representative"].asBoolean())
        assertEquals("UNRECORDED", companion["seatCategory"].asString())
        val afternoon = confirmed[1]
        assertTrue(afternoon["session"]["teacherCapacity"].isNull)
        assertTrue(afternoon["promotedFromWaiting"].isNull)
        assertTrue(afternoon["registrationSnapshot"].isNull)
        assertEquals(listOf("CONFIRMED", "CANCELLED"), afternoon["history"].list().map { it["status"].asString() })
        assertEquals(9, afternoon["history"][1]["actorId"].asInt())
        // 대기 예약은 User 참가자가 없고 QR 대상인 참가자 ID로 보이지 않는다.
        val waiting = rows.single { it.value("reservationId") == "waiting" }
        assertEquals("RESERVATION", waiting["rowType"].asString())
        assertEquals("WAITING", waiting["status"].asString())
        assertTrue(waiting["participantId"].isNull)
        assertTrue(waiting["representativeId"].isNull)
        assertTrue(waiting["participant"].isNull)
        assertTrue(waiting["applicationId"].isNull)
        assertEquals("PARTY", waiting["reservationKind"].asString())
        assertEquals(3, waiting["applicants"].asInt())
        assertEquals(listOf(0, 0, 3), listOf("teacher", "other", "unrecorded").map { waiting["seatCounts"][it].asInt() })
        assertFalse(waiting["promotedFromWaiting"].asBoolean())
        assertTrue(waiting["createdAt"].isNull)
        val addition = rows.single { it.value("reservationId") == "addition" }
        assertEquals("COMPANION_ADDITION", addition["reservationKind"].asString())
        assertEquals(1, addition["representativeId"].asInt())
        assertTrue(addition["participantId"].isNull)
        assertTrue(rows.none { it.value("reservationId") == "promoted" })
    }

    @Test
    fun `선택과 필터 전체는 같은 행을 내고 타 행사 없는 key는 전체 거절한다`() {
        val filtered = json(create("""{"filter":{"status":["WAITING","CANCELLED"],"sessionId":1}}"""))
        val keys = items(filtered).map { it["key"].asString() }
        assertEquals(listOf("RESERVATION:$expo:waiting"), keys)
        val selected = json(send("${base()}/selection", mapper.writeValueAsString(mapOf("keys" to keys))))
        assertEquals(filtered["items"], selected["items"])
        assertEquals(1, selected["totalElements"].asInt())
        listOf(
            "RESERVATION:$otherExpo:waiting",
            "APPLICATION:$expo:999999",
            UUID.randomUUID().toString(),
        ).forEach {
            val response = send("${base()}/selection", mapper.writeValueAsString(mapOf("keys" to keys + it)))
            assertEquals(404, response.status, it)
        }
        listOf(
            """{"keys":[]}""",
            """{"keys":["a","a"]}""",
            """{"keys":["a"],"filter":{}}""",
        ).forEach { assertEquals(400, send("${base()}/selection", it).status, it) }
        listOf("""{"limit":0}""", """{"limit":501}""", """{"filter":{"programType":"STANDARD"}}""", """{"filter":{"page":1}}""")
            .forEach { assertEquals(400, create(it).status, it) }
    }

    @Test
    fun `스냅샷은 커서 경계에서 행을 누락 중복하지 않고 변경 후에도 고정되며 원천과 행사를 분리한다`() {
        val first = json(create("""{"limit":4}"""))
        assertEquals(4, first["items"].size())
        jdbc.update(
            "UPDATE tb_preregister_reservation SET withdrawn_at = now(), state = 'REJECTED' WHERE expo_id = ? AND request_id = 'waiting'",
            expo,
        )
        application(expo, 2, 5, 5, "CONFIRMED", null, null)
        val cursor = first["nextCursor"].asString()
        val last = json(next(cursor))
        assertEquals(first["snapshotId"], last["snapshotId"])
        assertTrue(last["nextCursor"].isNull)
        val all = items(first) + items(last)
        assertEquals(5, all.size)
        assertEquals(5, all.map { it["key"].asString() }.distinct().size)
        assertEquals("WAITING", all.single { it.value("reservationId") == "waiting" }["status"].asString())
        assertEquals(404, next(cursor, otherExpo).status)
        assertEquals(400, next("invalid").status)
        // 프로그램 보고 커서 경로로 사전등록 스냅샷을 읽지 못한다.
        val program =
            mvc
                .perform(
                    get("/internal/expos/$expo/program-session-applications/report-targets")
                        .param("cursor", cursor)
                        .header("X-Internal-Token", "test-token"),
                ).andReturn()
                .response
        assertEquals(404, program.status)
        val offset = Base64.getUrlEncoder().withoutPadding().encodeToString("${first["snapshotId"].asString()}:3".toByteArray())
        assertEquals(400, next(offset).status)
        jdbc.update("UPDATE tb_report_application_snapshot SET expires_at = now() - interval '1 second'")
        assertEquals(410, next(cursor).status)
    }

    @Test
    fun `상한 초과 User 장애 삭제 행사는 부분 스냅샷 없이 실패한다`() {
        userStatus = 503
        assertEquals(503, create().status)
        userStatus = 200
        userParticipants = listOf(1L)
        assertEquals(502, create().status)
        userParticipants = listOf(1L, 2L)
        jdbc.update(
            "INSERT INTO tb_preregister_reservation " +
                "(expo_id, request_id, session_id, command, user_request, applicants, state, queue_seq) " +
                "SELECT ?, 'bulk-' || n, 1, '{}', '{}', 1, 'WAITING', 100 + n FROM generate_series(1, 4996) n",
            expo,
        )
        assertEquals(422, create().status)
        assertEquals(200, create("""{"filter":{"sessionId":2}}""").status)
        jdbc.update("INSERT INTO tb_preregister_deleted_expo (expo_id) VALUES (?)", expo)
        assertEquals(409, create().status)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM tb_report_application_snapshot", Int::class.java))
    }

    @Test
    fun `관리자 경로는 행사 권한을 요구하고 내부 경로는 토큰을 요구한다`() {
        assertEquals(
            401,
            mvc
                .perform(post(base()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn()
                .response.status,
        )
        val admin = "/application/admin/expos/$expo/preregistrations/report-targets"
        assertEquals(
            401,
            mvc
                .perform(post(admin).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn()
                .response.status,
        )
        val allowed =
            mvc
                .perform(post(admin).header("Authorization", "Bearer admin").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn()
                .response
        assertEquals(5, json(allowed)["totalElements"].asInt())
        assertNull(expoAuthorization.firstOrNull { it != "Bearer admin" })
    }

    companion object {
        @Volatile private var userStatus = 200

        @Volatile private var userParticipants = listOf(1L, 2L)
        private val expoAuthorization = java.util.concurrent.CopyOnWriteArrayList<String>()
        private val sources =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/internal/expos/") { exchange ->
                    val cursor = Regex("cursor=(-?[0-9]+)").find(exchange.requestURI.rawQuery)!!.groupValues[1].toLong()
                    val body =
                        userParticipants.filter { it > cursor }.joinToString(",", "{\"items\":[", "],\"nextCursor\":null}") { id ->
                            """{"participantId":$id,"name":"Person $id","phoneNumber":null,"notificationPhoneNumber":null,""" +
                                """"occupation":"TEACHER","representativeId":1,"linkedTraineeId":null,"applicationType":"PRE",""" +
                                """"information":{},"surveyAnswer":null,"personalInformationStatus":true}"""
                        }
                    val bytes = body.toByteArray()
                    exchange.sendResponseHeaders(userStatus, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                    exchange.close()
                }
                createContext("/internal/standard-participants/details") { exchange ->
                    val ids = ObjectMapper().readTree(exchange.requestBody.readAllBytes())["participantIds"]
                    val body = (0 until ids.size()).joinToString(",", "[", "]") { """{"participantId":${ids[it]}}""" }
                    exchange.sendResponseHeaders(200, body.length.toLong())
                    exchange.responseBody.use { it.write(body.toByteArray()) }
                    exchange.close()
                }
                createContext("/internal/standard-participants/names") { exchange ->
                    val ids = ObjectMapper().readTree(exchange.requestBody.readAllBytes())["participantIds"]
                    val body = (0 until ids.size()).joinToString(",", "[", "]") { """{"participantId":${ids[it]},"name":"Now"}""" }
                    exchange.sendResponseHeaders(200, body.length.toLong())
                    exchange.responseBody.use { it.write(body.toByteArray()) }
                    exchange.close()
                }
                createContext("/internal/expo/") { exchange ->
                    expoAuthorization.add(exchange.requestHeaders.getFirst("Authorization"))
                    val id = exchange.requestURI.path.split("/")[3]
                    val body =
                        """{"expoId":"$id","adminId":1,"permission":"APPLICATION_READ","policyVersion":"GLOBAL_ADMIN_APPLICATION_READ_V1","scope":"GLOBAL_ADMIN"}"""
                    exchange.sendResponseHeaders(200, body.length.toLong())
                    exchange.responseBody.use { it.write(body.toByteArray()) }
                    exchange.close()
                }
                start()
            }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("application.registration.user-url") { "http://127.0.0.1:${sources.address.port}" }
            registry.add("application.registration.user-internal-token") { "user-token" }
            registry.add("application.registration.expo-url") { "http://127.0.0.1:${sources.address.port}" }
            registry.add("application.registration.expo-internal-token") { "expo-token" }
        }

        @AfterAll
        @JvmStatic
        fun stop() {
            sources.stop(0)
        }

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
