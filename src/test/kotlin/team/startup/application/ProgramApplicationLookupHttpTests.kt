package team.startup.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@SpringBootTest(properties = ["eureka.client.enabled=false", "application.internal-token=test-token"])
@AutoConfigureMockMvc
@Testcontainers
class ProgramApplicationLookupHttpTests {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun clearTables() {
        jdbc.execute("TRUNCATE TABLE tb_standard_program_application, tb_training_program_application RESTART IDENTITY")
    }

    @Test
    fun `신청 테이블에 출석 컬럼이 남지 않는다`() {
        val columns =
            jdbc.queryForList(
                """
                SELECT table_name || '.' || column_name
                FROM information_schema.columns
                WHERE table_name IN ('tb_standard_program_application', 'tb_training_program_application')
                  AND column_name IN ('status', 'entry_time', 'leave_time', 'attendance_date')
                """.trimIndent(),
                String::class.java,
            )
        assertEquals(emptyList<String>(), columns)
    }

    @Test
    fun `일반과 연수 신청 여부는 저장된 신청 행만 기준으로 반환한다`() {
        jdbc.update("INSERT INTO tb_standard_program_application (participant_id, standard_program_id) VALUES (42, 7)")
        jdbc.update("INSERT INTO tb_training_program_application (trainee_id, training_program_id) VALUES (8, 9)")

        listOf(
            "/standard/7/participants/42" to true,
            "/standard/7/participants/43" to false,
            "/standard/8/participants/42" to false,
            "/training/9/trainees/8" to true,
            "/training/9/trainees/42" to false,
            "/training/7/trainees/8" to false,
        ).forEach { (path, applied) ->
            val response = mvc.perform(get("$BASE$path").header("X-Internal-Token", "test-token")).andReturn().response
            assertEquals(200, response.status, path)
            assertEquals("""{"applied":$applied}""", response.contentAsString, path)
        }
    }

    @Test
    fun `applied는 신청 존재 여부이고 삭제 후 false다`() {
        jdbc.update(
            """
            INSERT INTO tb_standard_program_application
            (participant_id, standard_program_id) VALUES (42, 7)
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO tb_training_program_application
            (trainee_id, training_program_id) VALUES (8, 9)
            """.trimIndent(),
        )
        val paths = listOf("/standard/7/participants/42", "/training/9/trainees/8")
        paths.forEach { path ->
            val response = mvc.perform(get("$BASE$path").header("X-Internal-Token", "test-token")).andReturn().response
            assertEquals(200, response.status)
            assertEquals("""{"applied":true}""", response.contentAsString)
        }
        jdbc.update("DELETE FROM tb_standard_program_application WHERE participant_id = 42 AND standard_program_id = 7")
        jdbc.update("DELETE FROM tb_training_program_application WHERE trainee_id = 8 AND training_program_id = 9")
        paths.forEach { path ->
            val response = mvc.perform(get("$BASE$path").header("X-Internal-Token", "test-token")).andReturn().response
            assertEquals(200, response.status)
            assertEquals("""{"applied":false}""", response.contentAsString)
        }
    }

    @Test
    fun `조회에는 내부 토큰과 양수 ID가 필요하다`() {
        assertEquals(
            401,
            mvc
                .perform(get("$BASE/standard/7/participants/42"))
                .andReturn()
                .response.status,
        )
        assertEquals(
            401,
            mvc
                .perform(get("$BASE/training/9/trainees/8"))
                .andReturn()
                .response.status,
        )
        assertEquals(
            400,
            mvc
                .perform(get("$BASE/standard/0/participants/42").header("X-Internal-Token", "test-token"))
                .andReturn()
                .response.status,
        )
        assertEquals(
            400,
            mvc
                .perform(get("$BASE/training/9/trainees/0").header("X-Internal-Token", "test-token"))
                .andReturn()
                .response.status,
        )
    }

    companion object {
        private const val BASE = "/internal/program-applications"

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
