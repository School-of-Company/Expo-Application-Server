package team.startup.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@SpringBootTest(properties = ["eureka.client.enabled=false", "application.internal-token=test-token"])
@AutoConfigureMockMvc
@Testcontainers
class AdminApplicationHttpTests {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    private val expoId = UUID.randomUUID()
    private val groupId = UUID.randomUUID()
    private val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val second = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val base get() = "/internal/expos/$expoId/program-session-applications"

    @BeforeEach
    fun seed() {
        jdbc.execute("TRUNCATE tb_program_session_application CASCADE")
        insert(first, expoId, "CONFIRMED")
        insert(second, expoId, "WAITING")
        insert(UUID.randomUUID(), expoId, "CANCELLED", "TRAINING", 8)
        insert(UUID.randomUUID(), UUID.randomUUID(), "CONFIRMED")
        jdbc.update("INSERT INTO tb_program_session_history (application_id, status) VALUES (?, 'CONFIRMED')", first)
    }

    private fun insert(
        id: UUID,
        expo: UUID,
        status: String,
        type: String = "STANDARD",
        program: Long = 7,
    ) {
        jdbc.update(
            "INSERT INTO tb_program_session_application " +
                "(id, expo_id, program_type, program_id, group_id, participant_id, status, definition_snapshot, " +
                "form_id, form_version, questions_snapshot, answers_snapshot, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, '{\"title\":\"100% Kotlin\"}', ?, 1, '[{\"id\":1}]', '{\"1\":\"saved\"}', '2026-10-11T00:00:00Z')",
            id,
            expo,
            type,
            program,
            groupId,
            Math.floorMod(id.hashCode(), 100000) + 1,
            status,
            UUID.randomUUID(),
        )
    }

    private fun read(query: String = "") = mvc.perform(get("$base$query").header("X-Internal-Token", "test-token")).andReturn().response

    @Test
    fun `필터 건수 상태 집계와 안정된 페이지는 같은 원장을 조회한다`() {
        val filter =
            "?programType=STANDARD&programId=7&groupId=$groupId&search=KOTLIN" +
                "&from=2026-10-11T00:00:00Z&until=2026-10-12T00:00:00Z&sort=CREATED_ASC&size=1"
        val response = read(filter)
        assertEquals(200, response.status)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        val body = mapper.readTree(response.contentAsString)
        assertEquals(2, body["totalElements"].asInt())
        assertEquals(1, body["statusCounts"]["CONFIRMED"].asInt())
        assertEquals(1, body["statusCounts"]["WAITING"].asInt())
        assertEquals(first.toString(), body["items"][0]["applicationId"].asString())
        assertEquals(second.toString(), mapper.readTree(read("$filter&page=1").contentAsString)["items"][0]["applicationId"].asString())
        assertEquals(2, mapper.readTree(read("$filter&page=99").contentAsString)["totalElements"].asInt())
    }

    @Test
    fun `상세는 당시 답변과 이력을 반환하며 타 행사 ID는 노출하지 않는다`() {
        val body = mapper.readTree(read("?applicationId=$first&status=CONFIRMED").contentAsString)
        assertEquals("saved", body["items"][0]["answers"]["1"].asString())
        assertEquals("CONFIRMED", body["items"][0]["history"][0]["status"].asString())
        assertTrue(body["items"][0]["traineeId"].isNull)
        val other =
            mvc
                .perform(
                    get(
                        "/internal/expos/${UUID.randomUUID()}/program-session-applications?applicationId=$first",
                    ).header("X-Internal-Token", "test-token"),
                ).andReturn()
                .response
        assertEquals(0, mapper.readTree(other.contentAsString)["totalElements"].asInt())
        assertEquals(0, mapper.readTree(read("?search=missing").contentAsString)["totalElements"].asInt())
        assertEquals(0, mapper.readTree(read("?until=2026-10-11T00:00:00Z").contentAsString)["totalElements"].asInt())
    }

    @Test
    fun `검색 특수문자는 패턴이 아니며 취소 이력도 기본 집계에 포함된다`() {
        val response = mvc.perform(get(base).param("search", "%").header("X-Internal-Token", "test-token")).andReturn().response
        val body = mapper.readTree(response.contentAsString)
        assertEquals(3, body["totalElements"].asInt())
        assertEquals(1, body["statusCounts"]["CANCELLED"].asInt())
        assertEquals(0, mapper.readTree(read("?search=_").contentAsString)["totalElements"].asInt())
        assertEquals(
            second.toString(),
            mapper.readTree(read("?programType=STANDARD&size=1").contentAsString)["items"][0]["applicationId"].asString(),
        )
        val cancelled = mapper.readTree(read("?programType=TRAINING&programId=8&status=CANCELLED").contentAsString)
        assertEquals(1, cancelled["totalElements"].asInt())
        assertEquals(0, cancelled["statusCounts"]["CONFIRMED"].asInt())
    }

    @Test
    fun `이력 시각은 DB 시간대와 무관하게 접수 시각과 동일한 UTC 형식이다`() {
        jdbc.update("UPDATE tb_program_session_application SET created_at = '2026-10-11T00:00:00.123456Z' WHERE id = ?", first)
        jdbc.update("UPDATE tb_program_session_history SET created_at = '2026-10-11T00:00:00.123456Z' WHERE application_id = ?", first)
        val transaction = TransactionTemplate(transactionManager)
        transaction.isReadOnly = true
        transaction.isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        transaction.executeWithoutResult {
            jdbc.execute("SET LOCAL TIME ZONE 'Asia/Seoul'")
            val body = mapper.readTree(read("?applicationId=$first").contentAsString)
            val item = body["items"][0]
            assertEquals("2026-10-11T00:00:00.123456Z", item["createdAt"].asString())
            assertEquals(item["createdAt"].asString(), item["history"][0]["createdAt"].asString())
        }
    }

    @Test
    fun `관리자 전체 상태 조회와 이력 조회용 인덱스가 마이그레이션된다`() {
        val indexes =
            jdbc.query(
                "SELECT indexdef FROM pg_indexes WHERE indexname IN ('ix_program_session_admin_page', 'ix_program_session_history_application')",
            ) { rs, _ -> requireNotNull(rs.getString("indexdef")) }
        assertEquals(2, indexes.size)
        assertTrue(indexes.any { it.contains("(expo_id, created_at, id)") })
        assertTrue(indexes.any { it.contains("(application_id, created_at, id)") })
        assertTrue(indexes.none { it.contains("WHERE") })
    }

    @Test
    fun `토큰과 유효한 지원 필터가 필요하다`() {
        assertEquals(
            401,
            mvc
                .perform(get(base).header("X-User-Role", "ROLE_ADMIN"))
                .andReturn()
                .response.status,
        )
        assertEquals(
            401,
            mvc
                .perform(get(base).header("X-Internal-Token", "wrong"))
                .andReturn()
                .response.status,
        )
        listOf(
            "page=-1",
            "size=101",
            "status=OTHER",
            "sort=id",
            "programId=7",
            "programType=STANDARD&programId=0",
            "role=ADMIN",
            "status=",
            "size=1&size=2",
            "from=2026-10-12T00:00:00Z&until=2026-10-11T00:00:00Z",
        ).forEach {
            assertEquals(400, read("?$it").status, it)
        }
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
