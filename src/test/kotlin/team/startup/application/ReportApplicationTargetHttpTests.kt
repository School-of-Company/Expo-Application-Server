package team.startup.application

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.web.server.ResponseStatusException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.service.impl.AdminExpoGateway
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

@SpringBootTest(properties = ["eureka.client.enabled=false", "application.internal-token=test-token"])
@AutoConfigureMockMvc
@Testcontainers
class ReportApplicationTargetHttpTests {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var dataSource: DataSource

    @MockitoBean private lateinit var authority: AdminExpoGateway

    private val expo = UUID.randomUUID()
    private val otherExpo = UUID.randomUUID()
    private val group = UUID.randomUUID()
    private val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val second = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val third = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val foreign = UUID.fromString("00000000-0000-0000-0000-000000000004")

    private fun base(expoId: UUID = expo) = "/internal/expos/$expoId/program-session-applications/report-targets"

    @BeforeEach
    fun seed() {
        userPages.clear()
        userRequests.clear()
        userStatus = 200
        userBatchStatus = 200
        batchBody = null
        observeUser = null
        jdbc.execute("TRUNCATE tb_program_session_application CASCADE")
        jdbc.execute("TRUNCATE tb_report_application_snapshot")
        insert(first, expo, 1, "CONFIRMED")
        insert(second, expo, 2, "WAITING")
        insert(third, expo, 1, "CANCELLED", "TRAINING", 8)
        insert(foreign, otherExpo, 3, "CONFIRMED")
        jdbc.update("INSERT INTO tb_program_session_history (application_id, status) VALUES (?, 'WAITING')", second)
    }

    private fun insert(
        id: UUID,
        expoId: UUID,
        participant: Long,
        status: String,
        type: String = "STANDARD",
        program: Long = 7,
    ) {
        jdbc.update(
            "INSERT INTO tb_program_session_application " +
                "(id, expo_id, program_type, program_id, group_id, participant_id, status, definition_snapshot, " +
                "form_id, form_version, questions_snapshot, answers_snapshot, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, '{\"title\":\"100% Kotlin\"}', ?, 2, '[{\"id\":1,\"title\":\"old\"}]', " +
                "'{\"1\":\"saved\"}', '2026-10-11T00:00:00.123456Z')",
            id,
            expoId,
            type,
            program,
            group,
            participant,
            status,
            UUID.randomUUID(),
        )
    }

    private fun create(body: String = "{}") = send(base(), body)

    private fun select(ids: List<UUID>) = send(base() + "/selection", mapper.writeValueAsString(mapOf("applicationIds" to ids)))

    private fun send(
        path: String,
        body: String,
    ): MockHttpServletResponse =
        mvc
            .perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body).header("X-Internal-Token", "test-token"))
            .andReturn()
            .response

    private fun next(
        cursor: String,
        expoId: UUID = expo,
    ): MockHttpServletResponse =
        mvc
            .perform(get(base(expoId)).param("cursor", cursor).header("X-Internal-Token", "test-token"))
            .andReturn()
            .response

    private fun json(response: MockHttpServletResponse): JsonNode {
        assertEquals(200, response.status, response.contentAsString)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        return mapper.readTree(response.contentAsString)
    }

    private fun items(body: JsonNode) = (0 until body["items"].size()).map { body["items"][it] }

    private fun ids(body: JsonNode) = items(body).map { it["application"]["applicationId"].asString() }

    private fun error(
        status: Int,
        response: MockHttpServletResponse,
    ) {
        assertEquals(status, response.status, response.contentAsString)
        assertEquals(status, mapper.readTree(response.contentAsString)["status"].asInt())
        assertTrue(response.contentType!!.startsWith("application/problem+json"))
        assertEquals("no-store", response.getHeader("Cache-Control"))
    }

    @Test
    fun `선택은 UUID 신청 전부와 당시 프로그램 답변 및 본인 등록 원천 참조를 반환한다`() {
        jdbc.update(
            "UPDATE tb_program_session_application SET trainee_id = 77, form_version = 3, " +
                "questions_snapshot = '[{\"id\":1,\"title\":\"other version\"}]', answers_snapshot = '{\"1\":\"other answer\"}' WHERE id = ?",
            third,
        )
        val body = json(select(listOf(third, second, first)))
        assertEquals(listOf(first, second, third).map(UUID::toString), ids(body))
        assertEquals(3, body["totalElements"].asInt())
        val item = body["items"][0]
        assertEquals(expo.toString(), item["expoId"].asString())
        assertEquals(expo.toString(), item["registrationSource"]["expoId"].asString())
        assertEquals(1, item["registrationSource"]["participantId"].asInt())
        assertEquals("saved", item["application"]["answers"]["1"].asString())
        assertEquals("old", item["application"]["questions"][0]["title"].asString())
        assertEquals(2, item["application"]["formVersion"].asInt())
        assertEquals("2026-10-11T00:00:00.123456Z", item["application"]["createdAt"].asString())
        assertTrue(item["application"]["traineeId"].isNull)
        val training = body["items"][2]["application"]
        assertEquals(77, training["traineeId"].asInt())
        assertEquals(3, training["formVersion"].asInt())
        assertEquals("other version", training["questions"][0]["title"].asString())
        assertEquals("other answer", training["answers"]["1"].asString())
        assertEquals(listOf("CONFIRMED", "WAITING", "CANCELLED"), items(body).map { it["application"]["status"].asString() })
        assertEquals(2, items(body).map { it["application"]["participantId"].asInt() }.distinct().size)
        assertFalse(item.has("legacyApplicationId"))
        assertFalse(item["registrationSource"].has("answers"))
    }

    @Test
    fun `누락 타 행사 혼합 선택은 부분 성공이나 ID 노출 없이 전체 거절한다`() {
        listOf(listOf(first, UUID.randomUUID()), listOf(first, foreign), listOf(foreign)).forEach {
            val response = select(it)
            error(404, response)
            assertFalse(response.contentAsString.contains(foreign.toString()))
            assertFalse(response.contentAsString.contains(first.toString()))
        }
    }

    @Test
    fun `선택의 빈 목록 중복 Long ID null 상한 필터 혼합을 거절한다`() {
        val invalid =
            listOf(
                "{}",
                "{\"applicationIds\":[]}",
                "{\"applicationIds\":[\"$first\",\"$first\"]}",
                "{\"applicationIds\":[7]}",
                "{\"applicationIds\":[null]}",
                "{\"applicationIds\":null}",
                "{\"applicationIds\":[\"$first\"],\"filter\":{\"status\":\"CONFIRMED\"}}",
                mapper.writeValueAsString(mapOf("applicationIds" to List(1001) { UUID.randomUUID() })),
            )
        invalid.forEach { error(400, send(base() + "/selection", it)) }
        error(400, send(base() + "/selection?status=CONFIRMED", "{\"applicationIds\":[\"$first\"]}"))
    }

    @Test
    fun `같은 필터의 관리자 총건수와 전체 대상은 화면 페이지와 무관하게 일치한다`() {
        val filter =
            "\"programType\":\"STANDARD\",\"programId\":7,\"groupId\":\"$group\",\"search\":\"KOTLIN\"," +
                "\"from\":\"2026-10-11T00:00:00Z\",\"until\":\"2026-10-12T00:00:00Z\",\"sort\":\"CREATED_ASC\""
        val page = json(create("{\"filter\":{$filter},\"limit\":1}"))
        assertEquals(2, page["totalElements"].asInt())
        assertEquals(listOf(first.toString()), ids(page))
        val last = json(next(page["nextCursor"].asString()))
        assertEquals(listOf(second.toString()), ids(last))
        assertTrue(last["nextCursor"].isNull)
        assertEquals(page["snapshotId"], last["snapshotId"])
        assertEquals(page["snapshotAt"], last["snapshotAt"])
        userCandidates(listOf(1, 2))
        val admin =
            json(
                mvc
                    .perform(
                        get("/internal/expos/$expo/program-session-applications")
                            .param("programType", "STANDARD")
                            .param("programId", "7")
                            .param("groupId", group.toString())
                            .param("search", "KOTLIN")
                            .param("from", "2026-10-11T00:00:00Z")
                            .param("until", "2026-10-12T00:00:00Z")
                            .param("sort", "CREATED_ASC")
                            .param("size", "1")
                            .header("X-Internal-Token", "test-token"),
                    ).andReturn()
                    .response,
            )
        assertEquals(admin["totalElements"].asInt(), page["totalElements"].asInt())
        (admin["items"][0] as tools.jackson.databind.node.ObjectNode).putNull("participant")
        assertEquals(admin["items"][0], page["items"][0]["application"])
    }

    @Test
    fun `내보내기 스냅샷은 페이지 사이의 승급 취소 삭제 새 신청과 답변 변경에도 고정된다`() {
        val page = json(create("{\"filter\":{\"programType\":\"STANDARD\",\"sort\":\"CREATED_ASC\"},\"limit\":1}"))
        val cursor = page["nextCursor"].asString()
        jdbc.update("UPDATE tb_program_session_application SET status = 'CANCELLED' WHERE id = ?", first)
        jdbc.update(
            "UPDATE tb_program_session_application SET status = 'CONFIRMED', answers_snapshot = '{\"1\":\"changed\"}', " +
                "questions_snapshot = '[]', form_version = 99 WHERE id = ?",
            second,
        )
        jdbc.update("INSERT INTO tb_program_session_history (application_id, status) VALUES (?, 'CONFIRMED')", second)
        jdbc.update("DELETE FROM tb_program_session_application WHERE id = ?", first)
        insert(UUID.randomUUID(), expo, 5, "CONFIRMED")
        val last = json(next(cursor))
        val item = last["items"][0]["application"]
        assertEquals("WAITING", item["status"].asString())
        assertEquals("saved", item["answers"]["1"].asString())
        assertEquals("old", item["questions"][0]["title"].asString())
        assertEquals(2, item["formVersion"].asInt())
        assertEquals(1, item["history"].size())
        assertEquals("WAITING", item["history"][0]["status"].asString())
        assertEquals(2, last["totalElements"].asInt())
        assertEquals(last, json(next(cursor)))
        assertEquals("CONFIRMED", json(select(listOf(second)))["items"][0]["application"]["status"].asString())
    }

    @Test
    fun `보고 대상은 목록과 같은 복수 상태와 행사일 필터를 받고 단일 상태 문자열도 유지한다`() {
        jdbc.update(
            "UPDATE tb_program_session_application SET definition_snapshot = '{\"title\":\"A\",\"startedAt\":\"2026-10-13 09:00\"}' " +
                "WHERE id IN (?, ?)",
            first,
            third,
        )
        assertEquals(
            listOf(second, first).map(UUID::toString),
            ids(json(create("{\"filter\":{\"status\":[\"CONFIRMED\",\"WAITING\"]}}"))),
        )
        assertEquals(listOf(first.toString()), ids(json(create("{\"filter\":{\"status\":\"CONFIRMED\"}}"))))
        assertEquals(
            listOf(third, first).map(UUID::toString),
            ids(json(create("{\"filter\":{\"eventDate\":\"2026-10-13\"}}"))),
        )
        assertEquals(
            listOf(first.toString()),
            ids(json(create("{\"filter\":{\"eventDate\":\"2026-10-13\",\"status\":[\"CONFIRMED\",\"WAITING\"]}}"))),
        )
        listOf("{\"status\":[]}", "{\"status\":[\"OTHER\"]}", "{\"eventDate\":\"2026-13-01\"}").forEach {
            assertEquals(400, create("{\"filter\":$it}").status, it)
        }
    }

    @Test
    fun `기본 상태 전체와 양방향 시간 UUID 정렬 날짜 경계 및 리터럴 검색을 유지한다`() {
        assertEquals(listOf(third, second, first).map(UUID::toString), ids(json(create())))
        assertEquals(3, json(create("{\"filter\":{\"search\":\"%\"}}"))["totalElements"].asInt())
        assertEquals(0, json(create("{\"filter\":{\"search\":\"_\"}}"))["totalElements"].asInt())
        assertEquals(
            0,
            json(create("{\"filter\":{\"until\":\"2026-10-11T00:00:00.123456Z\"}}"))["totalElements"].asInt(),
        )
        assertEquals(
            listOf(first.toString()),
            ids(json(create("{\"filter\":{\"applicationId\":\"$first\",\"status\":\"CONFIRMED\"}}"))),
        )
        assertEquals(0, json(create("{\"filter\":{\"applicationId\":\"$foreign\"}}"))["totalElements"].asInt())
        val empty = json(create("{\"filter\":{\"status\":\"CANCELLED\",\"programType\":\"STANDARD\"}}"))
        assertEquals(0, empty["items"].size())
        assertTrue(empty["nextCursor"].isNull)
        jdbc.update("UPDATE tb_program_session_application SET created_at = '2026-10-12T00:00:00Z' WHERE id = ?", first)
        assertEquals(listOf(first, third, second).map(UUID::toString), ids(json(create())))
    }

    @Test
    fun `커서는 생성 필터와 행사에 고정되고 잘못된 위치와 만료를 거절한다`() {
        val page = json(create("{\"limit\":1}"))
        val cursor = page["nextCursor"].asString()
        error(404, next(cursor, otherExpo))
        error(400, next("invalid"))
        error(400, next("x".repeat(129)))
        error(400, next(" "))
        error(400, mvc.perform(get(base()).header("X-Internal-Token", "test-token")).andReturn().response)
        error(
            400,
            next(Base64.getUrlEncoder().withoutPadding().encodeToString("${page["snapshotId"].asString()}:2147483647".toByteArray())),
        )
        error(400, next(Base64.getUrlEncoder().withoutPadding().encodeToString("${page["snapshotId"].asString()}:0".toByteArray())))
        val unaligned = json(create("{\"limit\":2}"))
        error(400, next(Base64.getUrlEncoder().withoutPadding().encodeToString("${unaligned["snapshotId"].asString()}:1".toByteArray())))
        error(404, next(Base64.getUrlEncoder().withoutPadding().encodeToString("${UUID.randomUUID()}:1".toByteArray())))
        error(
            400,
            mvc
                .perform(get(base()).param("cursor", cursor).param("status", "WAITING").header("X-Internal-Token", "test-token"))
                .andReturn()
                .response,
        )
        error(
            400,
            mvc
                .perform(get(base()).param("cursor", cursor, cursor).header("X-Internal-Token", "test-token"))
                .andReturn()
                .response,
        )
        jdbc.update("UPDATE tb_report_application_snapshot SET expires_at = clock_timestamp() - interval '1 second'")
        error(410, next(cursor))
        json(create())
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM tb_report_application_snapshot", Int::class.java))
        error(404, next(cursor))
    }

    @Test
    fun `커서는 반환된 정규 UUID와 위치 인코딩만 허용한다`() {
        val page = json(create("{\"limit\":1}"))
        val id = page["snapshotId"].asString()
        listOf("$id:01", "$id:+1", "$id: 1", "1-1-1-1-1:1").forEach {
            error(400, next(Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray())))
        }
        error(400, next(page["nextCursor"].asString() + "="))
        assertEquals(3, json(next(page["nextCursor"].asString()))["totalElements"].asInt())
    }

    @Test
    fun `저장된 스냅샷을 읽을 수 없으면 원천 장애로 거절한다`() {
        val page = json(create("{\"limit\":1}"))
        jdbc.update("UPDATE tb_report_application_snapshot SET items = jsonb_set(items, '{1}', '{}'::jsonb)")
        val response = next(page["nextCursor"].asString())
        error(503, response)
        assertFalse(response.contentAsString.contains("Jackson"))
        assertFalse(response.contentAsString.contains("participantId"))
        assertEquals(3, json(create())["totalElements"].asInt())
    }

    @Test
    fun `단일 회차 필터는 같은 숫자의 다른 프로그램 유형을 포함하지 않는다`() {
        jdbc.update("UPDATE tb_program_session_application SET program_id = 7 WHERE id = ?", third)
        val standard = json(create("{\"filter\":{\"programType\":\"STANDARD\",\"programId\":7}}"))
        assertEquals(listOf(second, first).map(UUID::toString), ids(standard))
        val training = json(create("{\"filter\":{\"programType\":\"TRAINING\",\"programId\":7,\"status\":\"CANCELLED\"}}"))
        assertEquals(listOf(third.toString()), ids(training))
        assertEquals(1, training["totalElements"].asInt())
    }

    @Test
    fun `미지원 필터 화면 페이징 잘못된 날짜와 상태를 거절한다`() {
        listOf(
            "{\"limit\":0}",
            "{\"limit\":501}",
            "{\"applicationIds\":[\"$first\"]}",
            "{\"filter\":{\"page\":0}}",
            "{\"filter\":{\"size\":20}}",
            "{\"filter\":{\"role\":\"ADMIN\"}}",
            "{\"filter\":{\"occupation\":\"ADMIN\"}}",
            "{\"filter\":{\"q\":\" \"}}",
            "{\"filter\":{\"q\":\"${"x".repeat(51)}\"}}",
            "{\"filter\":{\"query\":{\"page\":0}}}",
            "{\"filter\":{\"searchValid\":true}}",
            "{\"filter\":{\"search\":\" \"}}",
            "{\"filter\":{\"search\":\"${"x".repeat(201)}\"}}",
            "{\"filter\":{\"programId\":7}}",
            "{\"filter\":{\"programType\":\"STANDARD\",\"programId\":0}}",
            "{\"filter\":{\"status\":true}}",
            "{\"filter\":{\"status\":\"OTHER\"}}",
            "{\"filter\":{\"sort\":\"id\"}}",
            "{\"filter\":{\"from\":\"2026-10-12T00:00:00Z\",\"until\":\"2026-10-11T00:00:00Z\"}}",
        ).forEach { error(400, create(it)) }
        error(400, send(base() + "?page=1", "{}"))
    }

    @Test
    fun `모든 경로는 내부 토큰이 필요하며 관리자 헤더는 권한을 대신하지 않는다`() {
        listOf(base(), base() + "/selection").forEach { path ->
            assertEquals(
                401,
                mvc
                    .perform(post(path).header("X-User-Role", "ROLE_ADMIN"))
                    .andReturn()
                    .response.status,
            )
            assertEquals(
                401,
                mvc
                    .perform(post(path).header("X-Internal-Token", "wrong"))
                    .andReturn()
                    .response.status,
            )
        }
        assertEquals(
            401,
            mvc
                .perform(get(base()).param("cursor", "invalid"))
                .andReturn()
                .response.status,
        )
    }

    @Test
    fun `선택 배치 1000 전체 10000은 허용하고 초과는 부분 결과 없이 거절한다`() {
        jdbc.execute("TRUNCATE tb_program_session_application CASCADE")
        jdbc.update(
            "INSERT INTO tb_program_session_application " +
                "(id, expo_id, program_type, program_id, group_id, participant_id, status, definition_snapshot, " +
                "form_id, form_version, questions_snapshot, answers_snapshot, created_at) " +
                "SELECT md5(n::text)::uuid, ?, 'STANDARD', 7, ?, n, 'CONFIRMED', '{\"title\":\"bulk\"}', " +
                "?, 1, '[]', '{}', '2026-10-11T00:00:00Z' FROM generate_series(1, 10000) n",
            expo,
            group,
            UUID.randomUUID(),
        )
        val selectedIds =
            jdbc.query("SELECT id FROM tb_program_session_application ORDER BY id LIMIT 1000") { rs, _ ->
                rs.getObject(1, UUID::class.java)
            }
        assertEquals(1000, json(select(selectedIds))["items"].size())
        val page = json(create("{\"limit\":500}"))
        assertEquals(10000, page["totalElements"].asInt())
        assertEquals(500, page["items"].size())
        val collected = ids(page).toMutableList()
        var current = page
        while (!current["nextCursor"].isNull) {
            current = json(next(current["nextCursor"].asString()))
            collected.addAll(ids(current))
        }
        assertEquals(10000, collected.size)
        assertEquals(10000, collected.distinct().size)
        insert(UUID.randomUUID(), expo, 10001, "CONFIRMED")
        error(422, create())
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM tb_report_application_snapshot", Int::class.java))
        userCandidates(listOf(10001))
        val narrowed = json(create("{\"filter\":{\"q\":\"person\"}}"))
        assertEquals(1, narrowed["totalElements"].asInt())
        assertEquals(10001, narrowed["items"][0]["application"]["participantId"].asInt())
    }

    @Test
    fun `DB 원천 장애는 빈 성공 대신 명시적인 실패이며 복구 뒤 다시 조회할 수 있다`() {
        jdbc.execute("ALTER TABLE tb_program_session_application RENAME TO tb_program_session_application_unavailable")
        try {
            error(503, create())
            error(503, select(listOf(first)))
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM tb_report_application_snapshot", Int::class.java))
        } finally {
            jdbc.execute("ALTER TABLE tb_program_session_application_unavailable RENAME TO tb_program_session_application")
        }
        assertEquals(3, json(create())["totalElements"].asInt())
        val snapshot = json(create("{\"limit\":1}"))
        jdbc.execute("ALTER TABLE tb_report_application_snapshot RENAME TO tb_report_application_snapshot_unavailable")
        try {
            error(503, next(snapshot["nextCursor"].asString()))
            error(503, create())
        } finally {
            jdbc.execute("ALTER TABLE tb_report_application_snapshot_unavailable RENAME TO tb_report_application_snapshot")
        }
        assertEquals(3, json(next(snapshot["nextCursor"].asString()))["totalElements"].asInt())
    }

    private fun userCandidates(
        ids: List<Long>,
        size: Int = 500,
        occupation: String = "GENERAL",
    ) {
        val pages = ids.chunked(size)
        pages.forEachIndexed { index, batch ->
            userPages.addLast(
                mapper.writeValueAsString(
                    mapOf(
                        "items" to
                            batch.map {
                                mapOf(
                                    "participantId" to it,
                                    "name" to "Person $it",
                                    "occupation" to occupation,
                                    "phoneNumber" to null,
                                    "notificationPhoneNumber" to null,
                                    "representativeId" to null,
                                    "linkedTraineeId" to null,
                                    "applicationType" to "PRE",
                                    "information" to emptyMap<String, String>(),
                                )
                            },
                        "nextCursor" to if (index == pages.lastIndex) null else batch.last(),
                    ),
                ),
            )
        }
    }

    @Test
    fun `관리자 대상 선택 전체 커서는 원본 Authorization을 행사 권한 원천에 전달한다`() {
        Mockito
            .doAnswer { Mockito.mock(it.method.returnType) }
            .`when`(authority)
            .requireAccess(expo, "Bearer admin-jwt")
        val adminBase = "/application/admin/expos/$expo/program-session-applications/report-targets"

        fun adminPost(
            path: String,
            body: String,
        ) = mvc
            .perform(
                post(path).header("Authorization", "Bearer admin-jwt").contentType(MediaType.APPLICATION_JSON).content(body),
            ).andReturn()
            .response
        val page = json(adminPost(adminBase, "{\"limit\":1}"))
        assertEquals(3, page["totalElements"].asInt())
        assertEquals(1, json(adminPost(adminBase + "/selection", "{\"applicationIds\":[\"$first\"]}"))["totalElements"].asInt())
        val next =
            json(
                mvc
                    .perform(
                        get(adminBase).param("cursor", page["nextCursor"].asString()).header("Authorization", "Bearer admin-jwt"),
                    ).andReturn()
                    .response,
            )
        assertEquals(page["snapshotId"], next["snapshotId"])
        Mockito.verify(authority, Mockito.times(3)).requireAccess(expo, "Bearer admin-jwt")
        assertTrue(userRequests.isEmpty())
    }

    @Test
    fun `관리자 권한 거절이나 원천 장애는 User 및 스냅샷 생성 전에 차단한다`() {
        val adminBase = "/application/admin/expos/$expo/program-session-applications/report-targets"
        listOf(401, 403, 404, 502, 503).forEach { status ->
            Mockito
                .doThrow(ResponseStatusException(HttpStatus.valueOf(status), "Authority unavailable"))
                .`when`(authority)
                .requireAccess(expo, "Bearer denied")
            error(
                status,
                mvc
                    .perform(
                        post(adminBase)
                            .header("Authorization", "Bearer denied")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"filter\":{\"q\":\"person\"}}"),
                    ).andReturn()
                    .response,
            )
            error(
                status,
                mvc
                    .perform(
                        post(adminBase + "/selection")
                            .header("Authorization", "Bearer denied")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"applicationIds\":[\"$first\"]}"),
                    ).andReturn()
                    .response,
            )
            error(
                status,
                mvc
                    .perform(
                        get(adminBase).param("cursor", "invalid").header("Authorization", "Bearer denied"),
                    ).andReturn()
                    .response,
            )
        }
        assertTrue(userRequests.isEmpty())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM tb_report_application_snapshot", Int::class.java))
    }

    @Test
    fun `관리자 경로에서 내부 토큰이나 관리자 헤더는 원본 JWT를 대신하지 않는다`() {
        Mockito
            .doThrow(ResponseStatusException(HttpStatus.UNAUTHORIZED, "Admin JWT required"))
            .`when`(authority)
            .requireAccess(expo, null)
        error(
            401,
            mvc
                .perform(
                    post("/application/admin/expos/$expo/program-session-applications/report-targets")
                        .header("X-Internal-Token", "test-token")
                        .header("X-User-Role", "ROLE_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"),
                ).andReturn()
                .response,
        )
        Mockito.verify(authority).requireAccess(expo, null)
        assertTrue(userRequests.isEmpty())
    }

    @Test
    fun `관리자 권한은 고정 스냅샷 커서에서도 다시 검증한다`() {
        val page = json(create("{\"limit\":1}"))
        Mockito
            .doThrow(ResponseStatusException(HttpStatus.FORBIDDEN, "Access revoked"))
            .`when`(authority)
            .requireAccess(expo, "Bearer revoked")
        error(
            403,
            mvc
                .perform(
                    get("/application/admin/expos/$expo/program-session-applications/report-targets")
                        .header("Authorization", "Bearer revoked")
                        .param("cursor", page["nextCursor"].asString()),
                ).andReturn()
                .response,
        )
        assertEquals(page["snapshotId"], json(next(page["nextCursor"].asString()))["snapshotId"])
    }

    @Test
    fun `지연된 User 요청은 다른 대상 조회의 응답을 막지 않는다`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        observeUser = {
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(30, TimeUnit.SECONDS))
            }
        }
        HttpClient.newHttpClient().use { client ->
            val blocked =
                client.sendAsync(
                    HttpRequest
                        .newBuilder(URI.create("http://127.0.0.1:${user.address.port}/internal/probe"))
                        .header("X-Internal-Token", "user-token")
                        .build(),
                    HttpResponse.BodyHandlers.discarding(),
                )
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                userCandidates(listOf(1))
                assertEquals(2, json(create("{\"filter\":{\"q\":\"person\"}}"))["totalElements"].asInt())
            } finally {
                release.countDown()
                blocked.get(5, TimeUnit.SECONDS)
                observeUser = null
            }
        }
    }

    @Test
    fun `User 전체 페이지와 배치 조회 중에는 스냅샷 작업이 DB 연결을 점유하지 않는다`() {
        userCandidates((1L..501L).toList())
        val pool = dataSource.unwrap(com.zaxxer.hikari.HikariDataSource::class.java)
        val activeConnections = mutableListOf<Int>()
        observeUser = { activeConnections.add(pool.hikariPoolMXBean.activeConnections) }
        try {
            val result = json(create("{\"filter\":{\"q\":\"person\",\"programType\":\"STANDARD\"}}"))
            assertEquals(2, result["totalElements"].asInt())
            assertEquals(listOf(0, 0, 0, 0), activeConnections)
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM tb_report_application_snapshot", Int::class.java))
        } finally {
            observeUser = null
        }
    }

    @Test
    fun `사람 검색과 직업은 행사 원장 조건에 교차하고 조회 이후 커서는 User를 다시 호출하지 않는다`() {
        userCandidates(listOf(1, 2), occupation = "TEACHER")
        val page =
            json(
                create(
                    "{\"filter\":{\"q\":\" 김 & Teacher \",\"occupation\":\"TEACHER\",\"programType\":\"STANDARD\",\"sort\":\"CREATED_ASC\"},\"limit\":1}",
                ),
            )
        assertEquals(2, page["totalElements"].asInt())
        assertEquals(listOf(first.toString()), ids(page))
        assertEquals(2, userRequests.size)
        assertTrue(userRequests[0].first.startsWith("/internal/expos/$expo/standard-participants/details?"))
        assertTrue(userRequests[0].first.contains("q=%EA%B9%80+%26+Teacher"))
        assertTrue(userRequests[0].first.contains("occupation=TEACHER"))
        assertEquals(expo.toString(), mapper.readTree(userRequests[1].second)["expoId"].asString())
        userStatus = 503
        jdbc.update("UPDATE tb_program_session_application SET status = 'CANCELLED' WHERE id = ?", second)
        val last = json(next(page["nextCursor"].asString()))
        assertEquals("WAITING", last["items"][0]["application"]["status"].asString())
        assertEquals(2, userRequests.size)
        assertEquals(page["snapshotAt"], last["snapshotAt"])
        assertFalse(last.toString().contains("phoneNumber"))
    }

    @Test
    fun `User 후보와 원장 필터의 날짜 묶음 상태 정렬 해석은 기존 관리자 결과와 일치한다`() {
        val filters =
            listOf(
                "{}",
                "{\"status\":\"WAITING\"}",
                "{\"programType\":\"STANDARD\",\"programId\":7,\"groupId\":\"$group\",\"search\":\"KOTLIN\",\"sort\":\"CREATED_ASC\"}",
                "{\"applicationId\":\"$second\",\"from\":\"2026-10-11T00:00:00Z\",\"until\":\"2026-10-12T00:00:00Z\"}",
                "{\"until\":\"2026-10-11T00:00:00.123456Z\"}",
                "{\"search\":\"_\"}",
                "{\"programType\":\"TRAINING\",\"programId\":7}",
            )
        filters.forEach { filter ->
            val original = json(create("{\"filter\":$filter}"))
            userCandidates(listOf(1, 2, 3))
            val withPeople = mapper.readTree(filter) as tools.jackson.databind.node.ObjectNode
            withPeople.put("occupation", "GENERAL")
            val actual = json(create(mapper.writeValueAsString(mapOf("filter" to withPeople))))
            assertEquals(original["items"], actual["items"])
            assertEquals(original["totalElements"], actual["totalElements"])
        }
    }

    @Test
    fun `후보 전체 커서와 배치를 확정하고 참가자 중복을 신청 건수와 혼동하지 않는다`() {
        userCandidates((1L..501L).toList())
        val result = json(create("{\"filter\":{\"q\":\"010-1234\"}}"))
        assertEquals(3, result["totalElements"].asInt())
        assertEquals(2, items(result).map { it["application"]["participantId"].asLong() }.distinct().size)
        assertEquals(4, userRequests.size)
        assertTrue(userRequests[1].first.contains("cursor=500"))
        assertEquals(500, mapper.readTree(userRequests[2].second)["participantIds"].size())
        assertEquals(1, mapper.readTree(userRequests[3].second)["participantIds"].size())
    }

    @Test
    fun `확정 후보가 없거나 타 행사 참가자면 행사 원장의 대상은 빈 스냅샷이다`() {
        assertEquals(0, json(create("{\"filter\":{\"occupation\":\"PARENT\"}}"))["totalElements"].asInt())
        assertEquals(1, userRequests.size)
        userCandidates(listOf(3))
        val result = json(create("{\"filter\":{\"q\":\"other\"}}"))
        assertEquals(0, result["totalElements"].asInt())
        assertTrue(result["nextCursor"].isNull)
    }

    @Test
    fun `User 장애 거절 잘못된 형식과 후보 삭제는 빈 성공이나 부분 스냅샷을 저장하지 않는다`() {
        listOf(400 to 502, 401 to 502, 404 to 502, 500 to 503, 503 to 503).forEach { (upstream, expected) ->
            userStatus = upstream
            error(expected, create("{\"filter\":{\"q\":\"person\"}}"))
        }
        userStatus = 200
        listOf(
            "not-json",
            "{}",
            "{\"items\":[],\"nextCursor\":1}",
            "{\"items\":[{\"participantId\":1},{\"participantId\":1}],\"nextCursor\":null}",
            "{\"items\":[{\"participantId\":2},{\"participantId\":1}],\"nextCursor\":null}",
            "{\"items\":[{\"participantId\":\"1\"}],\"nextCursor\":null}",
            "{\"items\":[{\"participantId\":1}],\"nextCursor\":0}",
        ).forEach { page ->
            userPages.addLast(page)
            error(502, create("{\"filter\":{\"q\":\"person\"}}"))
        }
        listOf("[]", "[{\"participantId\":2}]", "[{\"participantId\":1},{\"participantId\":1}]", "{}").forEach { body ->
            userCandidates(listOf(1))
            batchBody = body
            error(502, create("{\"filter\":{\"q\":\"person\"}}"))
        }
        batchBody = null
        userCandidates(listOf(1))
        userBatchStatus = 404
        error(502, create("{\"filter\":{\"q\":\"person\"}}"))
        userBatchStatus = 200
        listOf(listOf(1L, 1L), listOf(2L, 1L)).forEach { candidates ->
            userCandidates(candidates)
            error(502, create("{\"filter\":{\"q\":\"person\"}}"))
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM tb_report_application_snapshot", Int::class.java))
        batchBody = null
        userCandidates(listOf(1))
        assertEquals(2, json(create("{\"filter\":{\"q\":\"person\"}}"))["totalElements"].asInt())
    }

    @Test
    fun `후속 User 커서가 반복되거나 뒤로 가면 전체 요청을 실패 처리한다`() {
        userCandidates(listOf(1, 1), size = 1)
        error(502, create("{\"filter\":{\"q\":\"person\"}}"))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM tb_report_application_snapshot", Int::class.java))
    }

    companion object {
        private val userPages = ArrayDeque<String>()
        private val userRequests = mutableListOf<Pair<String, String>>()
        private var userStatus = 200
        private var userBatchStatus = 200
        private var batchBody: String? = null

        @Volatile private var observeUser: (() -> Unit)? = null
        private val userExecutor = Executors.newVirtualThreadPerTaskExecutor()
        private val user =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                executor = userExecutor
                createContext("/internal") { exchange ->
                    observeUser?.invoke()
                    val body = exchange.requestBody.readAllBytes().decodeToString()
                    userRequests.add(exchange.requestURI.toString() to body)
                    assertEquals("user-token", exchange.requestHeaders.getFirst("X-Internal-Token"))
                    val response =
                        if (exchange.requestMethod == "GET") {
                            userPages.removeFirstOrNull() ?: "{\"items\":[],\"nextCursor\":null}"
                        } else {
                            batchBody ?: tools.jackson.databind.json.JsonMapper
                                .builder()
                                .build()
                                .readTree(body)["participantIds"]
                                .joinToString(",", "[", "]") { "{\"participantId\":${it.asLong()}}" }
                        }
                    val bytes = response.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    val status = if (exchange.requestMethod == "GET" || userStatus != 200) userStatus else userBatchStatus
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                    exchange.close()
                }
                start()
            }

        @DynamicPropertySource
        @JvmStatic
        fun userProperties(registry: DynamicPropertyRegistry) {
            registry.add("application.registration.user-url") { "http://127.0.0.1:${user.address.port}" }
            registry.add("application.registration.user-internal-token") { "user-token" }
        }

        @AfterAll
        @JvmStatic
        fun stopUser() {
            user.stop(0)
            userExecutor.close()
        }

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
