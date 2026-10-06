package team.startup.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@SpringBootTest(properties = ["eureka.client.enabled=false", "application.internal-token=test-token"])
@AutoConfigureMockMvc
@Testcontainers
class ReportTrainingApplicationHttpTests {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun clearTables() {
        jdbc.execute("TRUNCATE TABLE tb_training_program_application RESTART IDENTITY")
    }

    @Test
    fun `연수자별 신청은 신청 ID 순서로 필요한 필드만 반환한다`() {
        jdbc.update("INSERT INTO tb_training_program_application (id, trainee_id, training_program_id) VALUES (200, 43, 8)")
        jdbc.update("INSERT INTO tb_training_program_application (id, trainee_id, training_program_id) VALUES (100, 42, 7)")

        val response = request("""{"traineeIds":[43,42,999]}""")
        assertEquals(200, response.status)
        assertEquals(
            """[{"applicationId":100,"traineeId":42,"trainingProgramId":7},""" +
                """{"applicationId":200,"traineeId":43,"trainingProgramId":8}]""",
            response.contentAsString,
        )
        assertEquals("[]", request("""{"traineeIds":[]}""").contentAsString)
        assertEquals("[]", request("""{"traineeIds":[999]}""").contentAsString)
        assertEquals(200, request("""{"traineeIds":[${(1..500).joinToString()}]}""").status)
    }

    @Test
    fun `연수자별 조회는 토큰과 ID 및 요청 크기를 검증한다`() {
        assertEquals(401, request("""{"traineeIds":[1]}""", false).status)
        listOf(
            """{"traineeIds":[0]}""",
            """{"traineeIds":[-1]}""",
            """{"traineeIds":null}""",
            """{}""",
            """{"traineeIds":[${(1..501).joinToString()}]}""",
        ).forEach { body ->
            assertEquals(400, request(body).status, body)
        }
    }

    private fun request(
        body: String,
        authenticated: Boolean = true,
    ) = mvc
        .perform(
            post("/internal/training-program-applications/trainees")
                .apply { if (authenticated) header("X-Internal-Token", "test-token") }
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andReturn()
        .response

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
