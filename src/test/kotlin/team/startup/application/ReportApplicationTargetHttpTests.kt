package team.startup.application

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
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.Base64
import java.util.UUID

@SpringBootTest(properties = ["eureka.client.enabled=false", "application.internal-token=test-token"])
@AutoConfigureMockMvc
@Testcontainers
class ReportApplicationTargetHttpTests {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

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

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
