package team.startup.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.global.security.InternalTokenVerifier
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(properties = ["eureka.client.enabled=false", "application.internal-token=test-token"])
@AutoConfigureMockMvc
@Testcontainers
class StandardProgramApplicationHttpTests {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `내부 토큰 설정이 비어 있으면 기동을 거부한다`() {
        assertThrows(IllegalArgumentException::class.java) { InternalTokenVerifier("") }
    }

    @Test
    fun `내부 API는 유효한 토큰을 요구한다`() {
        mvc
            .perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized)
        mvc
            .perform(get("$PATH/program/7"))
            .andExpect(status().isUnauthorized)
        mvc
            .perform(delete("$PATH/program/7"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `인코딩된 내부 경로로 토큰 검사를 우회할 수 없다`() {
        mvc.perform(apply(16, 906)).andExpect(status().isCreated)

        val response = mvc.perform(delete(URI.create("/%69nternal/standard-program-applications/program/906"))).andReturn().response
        assertEquals(401, response.status)
        assertEquals(1L, count(906))
    }

    @Test
    fun `중복 ID는 한 번만 신청하고 기존 신청과 충돌하면 전체 요청을 롤백한다`() {
        val program = 901L
        val other = 902L
        mvc.perform(apply(11, program, program)).andExpect(status().isCreated)
        assertEquals(1L, count(program))

        mvc.perform(apply(11, other, program)).andExpect(status().isConflict)
        assertEquals(0L, count(other))
        assertEquals(1L, count(program))
    }

    @Test
    fun `빈 목록과 잘못된 참조를 검증한다`() {
        mvc.perform(apply(12)).andExpect(status().isCreated)
        mvc
            .perform(
                post(PATH)
                    .header(TOKEN_HEADER, TOKEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"participant":{"id":12,"expoId":"expo-1"},"programs":[{"id":903,"expoId":"expo-2"}]}"""),
            ).andExpect(status().isBadRequest)
        mvc
            .perform(
                post(PATH)
                    .header(TOKEN_HEADER, TOKEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"participant":{"id":0,"expoId":"expo-1"},"programs":[]}"""),
            ).andExpect(status().isBadRequest)
        mvc
            .perform(
                post(PATH)
                    .header(TOKEN_HEADER, TOKEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"participant":{"id":12,"expoId":"expo-1"},"programs":[{"id":0,"expoId":"expo-1"}]}"""),
            ).andExpect(status().isBadRequest)
        mvc.perform(get("$PATH/program/0").header(TOKEN_HEADER, TOKEN)).andExpect(status().isBadRequest)
        mvc.perform(delete("$PATH/program/0").header(TOKEN_HEADER, TOKEN)).andExpect(status().isBadRequest)
        assertEquals(0L, count(903))
    }

    @Test
    fun `목록은 출입 상태와 HH mm 형식의 시각을 반환하고 삭제는 재시도 가능하다`() {
        val program = 904L
        mvc.perform(apply(13, program)).andExpect(status().isCreated)
        mvc
            .perform(get("$PATH/program/$program").header(TOKEN_HEADER, TOKEN))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].applicationId").isNumber)
            .andExpect(jsonPath("$[0].participantId").value(13))
            .andExpect(jsonPath("$[0].status").value(false))
            .andExpect(jsonPath("$[0].entryTime").value(null))
            .andExpect(jsonPath("$[0].leaveTime").value(null))

        jdbc.update(
            "UPDATE tb_standard_program_application SET status = true, entry_time = '09:03:00', leave_time = '10:04:00' WHERE standard_program_id = ?",
            program,
        )
        mvc
            .perform(get("$PATH/program/$program").header(TOKEN_HEADER, TOKEN))
            .andExpect(jsonPath("$[0].entryTime").value("09:03"))
            .andExpect(jsonPath("$[0].leaveTime").value("10:04"))

        mvc.perform(delete("$PATH/program/$program").header(TOKEN_HEADER, TOKEN)).andExpect(status().isNoContent)
        mvc.perform(delete("$PATH/program/$program").header(TOKEN_HEADER, TOKEN)).andExpect(status().isNoContent)
        mvc
            .perform(get("$PATH/program/$program").header(TOKEN_HEADER, TOKEN))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isEmpty)
        mvc.perform(apply(14, program)).andExpect(status().isConflict)
    }

    @Test
    fun `동시 중복 신청 중 하나만 저장한다`() {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results =
                (1..2).map {
                    executor.submit<Int> {
                        start.await()
                        mvc
                            .perform(apply(15, 905))
                            .andReturn()
                            .response.status
                    }
                }
            start.countDown()
            assertEquals(setOf(201, 409), results.map { it.get(15, TimeUnit.SECONDS) }.toSet())
            assertEquals(1L, count(905))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `동시 삭제와 신청 뒤에는 신청 기록이 남지 않는다`() {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val apply =
                executor.submit<Int> {
                    start.await()
                    mvc
                        .perform(apply(17, 907))
                        .andReturn()
                        .response.status
                }
            val delete =
                executor.submit<Int> {
                    start.await()
                    mvc
                        .perform(delete("$PATH/program/907").header(TOKEN_HEADER, TOKEN))
                        .andReturn()
                        .response.status
                }
            start.countDown()
            assertEquals(204, delete.get(15, TimeUnit.SECONDS))
            assertTrue(apply.get(15, TimeUnit.SECONDS) in setOf(201, 409))
            assertEquals(0L, count(907))
        } finally {
            executor.shutdownNow()
        }
    }

    private fun apply(
        participantId: Long,
        vararg programIds: Long,
    ) = post(PATH)
        .header(TOKEN_HEADER, TOKEN)
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """{"participant":{"id":$participantId,"expoId":"expo-1"},"programs":[${programIds.joinToString {
                """{"id":$it,"expoId":"expo-1"}"""
            }}]}""",
        )

    private fun count(programId: Long) =
        jdbc.queryForObject(
            "SELECT count(*) FROM tb_standard_program_application WHERE standard_program_id = ?",
            Long::class.java,
            programId,
        )

    companion object {
        private const val PATH = "/internal/standard-program-applications"
        private const val TOKEN_HEADER = "X-Internal-Token"
        private const val TOKEN = "test-token"

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
