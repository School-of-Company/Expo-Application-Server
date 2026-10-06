package team.startup.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["eureka.client.enabled=false", "application.internal-token=test-token"],
)
@Testcontainers
class TrainingProgramApplicationHttpTests {
    @LocalServerPort
    private var port = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val http = HttpClient.newHttpClient()

    @BeforeEach
    fun clearTables() {
        jdbc.execute("TRUNCATE TABLE tb_training_program_application, tb_deleted_training_program RESTART IDENTITY")
    }

    @Test
    fun `토큰이 없거나 틀리면 본문 검증과 저장 전에 401이다`() {
        listOf(null, "wrong-token", "").forEach { token ->
            assertEquals(401, request("POST", BASE, applyBody(42, 7), token).statusCode())
            assertEquals(401, request("POST", BASE, "{}", token).statusCode())
            assertEquals(401, request("GET", "$BASE/program/7", token = token).statusCode())
            assertEquals(401, request("DELETE", "$BASE/program/7", token = token).statusCode())
        }
        // 원본 URI와 디코딩된 경로가 다른 요청도 인증을 우회하지 못한다.
        listOf(
            "/%69nternal/training-program-applications/program/7",
            "/internal/%74raining-program-applications/program/7",
        ).forEach { path ->
            assertEquals(401, request("GET", path, token = null).statusCode(), path)
            assertEquals(401, request("DELETE", path, token = null).statusCode(), path)
        }
        assertEquals(0L, count(7))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM tb_deleted_training_program", Long::class.java))
    }

    @Test
    fun `내부 보안 설정을 추가해도 헬스 체크는 인증 없이 접근한다`() {
        val health = request("GET", "/actuator/health", token = null)
        assertTrue(health.statusCode() in listOf(200, 503), "status=${health.statusCode()}")
    }

    @Test
    fun `신청은 201 빈 본문이고 목록은 신청 ID와 false null 형식이다`() {
        val created = request("POST", BASE, applyBody(42, 7))
        assertEquals(201, created.statusCode())
        assertEquals("", created.body())
        assertEquals(201, request("POST", BASE, applyBody(43, 7)).statusCode())
        jdbc.update(
            "UPDATE tb_training_program_application SET status = true, entry_time = '09:05:30', leave_time = '10:00' WHERE trainee_id = 43",
        )

        val list = request("GET", "$BASE/program/7")
        assertEquals(200, list.statusCode())
        assertEquals(
            """[{"applicationId":1,"traineeId":42,"status":false,"entryTime":null,"leaveTime":null},""" +
                """{"applicationId":2,"traineeId":43,"status":true,"entryTime":"09:05","leaveTime":"10:00"}]""",
            list.body(),
        )
        assertEquals("[]", request("GET", "$BASE/program/8").body())
    }

    @Test
    fun `중복 신청과 정원 초과는 409이고 여러 프로그램 중 하나라도 실패하면 모두 저장하지 않는다`() {
        assertEquals(201, request("POST", BASE, applyBody(42, 20)).statusCode())
        assertEquals(409, request("POST", BASE, applyBody(42, 20)).statusCode())
        assertEquals(409, request("POST", BASE, applyBody(42, 10, 20)).statusCode())
        assertEquals(0L, count(10))

        jdbc.update(
            "INSERT INTO tb_training_program_application (trainee_id, training_program_id) " +
                "SELECT person_id, 30 FROM generate_series(1000, 1024) AS person_id",
        )
        assertEquals(409, request("POST", BASE, applyBody(42, 30)).statusCode())
        assertEquals(25L, count(30))
    }

    @Test
    fun `행사 ID 불일치 요청 내 중복 빈 목록 잘못된 값은 저장 없이 400이다`() {
        val invalidBodies =
            listOf(
                """{"trainee":{"id":42,"expoId":"expo-1"},"programs":[{"id":7,"expoId":"expo-2","category":"CHOICE"}]}""",
                applyBody(42, 7, 7),
                """{"trainee":{"id":42,"expoId":"expo-1"},"programs":[]}""",
                """{"trainee":{"id":42,"expoId":"expo-1"}}""",
                """{"trainee":{"id":0,"expoId":"expo-1"},"programs":[{"id":7,"expoId":"expo-1","category":"CHOICE"}]}""",
                """{"trainee":{"id":42,"expoId":" "},"programs":[{"id":7,"expoId":" ","category":"CHOICE"}]}""",
                """{"trainee":{"id":42,"expoId":"expo-1"},"programs":[{"id":-1,"expoId":"expo-1","category":"CHOICE"}]}""",
                """{"trainee":{"id":42,"expoId":"expo-1"},"programs":[{"id":7,"expoId":"expo-1","category":"UNKNOWN"}]}""",
                """{"trainee":{"id":42,"expoId":"expo-1"},"programs":[{"id":7,"expoId":"expo-1"}]}""",
                "not-json",
            )
        invalidBodies.forEach { body ->
            assertEquals(400, request("POST", BASE, body).statusCode(), body)
        }
        assertEquals(400, request("GET", "$BASE/program/0").statusCode())
        assertEquals(400, request("DELETE", "$BASE/program/abc").statusCode())
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM tb_training_program_application", Long::class.java))
    }

    @Test
    fun `삭제는 재시도해도 204이고 삭제된 프로그램에는 다시 신청할 수 없다`() {
        assertEquals(201, request("POST", BASE, applyBody(42, 7)).statusCode())
        assertEquals(201, request("POST", BASE, applyBody(42, 8)).statusCode())

        repeat(2) {
            val deleted = request("DELETE", "$BASE/program/7")
            assertEquals(204, deleted.statusCode())
            assertEquals("", deleted.body())
        }
        assertEquals(204, request("DELETE", "$BASE/program/999").statusCode())
        assertEquals("[]", request("GET", "$BASE/program/7").body())
        assertEquals(409, request("POST", BASE, applyBody(43, 7)).statusCode())
        assertEquals(409, request("POST", BASE, applyBody(43, 7, 8)).statusCode())
        assertEquals(0L, count(7))
        assertEquals(1L, count(8))
    }

    @Test
    fun `삭제와 동시에 들어온 신청은 삭제된 프로그램에 행을 남기지 않는다`() {
        val executor = Executors.newFixedThreadPool(9)
        try {
            repeat(5) { round ->
                val programId = 100L + round
                val start = CountDownLatch(1)
                val applies =
                    (1L..8L).map { traineeId ->
                        executor.submit<Int> {
                            start.await()
                            request("POST", BASE, applyBody(traineeId, programId)).statusCode()
                        }
                    }
                val delete =
                    executor.submit<Int> {
                        start.await()
                        request("DELETE", "$BASE/program/$programId").statusCode()
                    }
                start.countDown()

                assertEquals(204, delete.get(15, TimeUnit.SECONDS))
                assertTrue(applies.map { it.get(15, TimeUnit.SECONDS) }.all { it == 201 || it == 409 })
                assertEquals(0L, count(programId))
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun applyBody(
        traineeId: Long,
        vararg programIds: Long,
    ) = """{"trainee":{"id":$traineeId,"expoId":"expo-1"},"programs":[""" +
        programIds.joinToString(",") { """{"id":$it,"expoId":"expo-1","category":"CHOICE"}""" } + "]}"

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        token: String? = "test-token",
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Content-Type", "application/json")
        token?.let { builder.header("X-Internal-Token", it) }
        val payload = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        return http.send(builder.method(method, payload).build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun count(programId: Long) =
        jdbc.queryForObject(
            "SELECT count(*) FROM tb_training_program_application WHERE training_program_id = ?",
            Long::class.java,
            programId,
        )

    companion object {
        private const val BASE = "/internal/training-program-applications"

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
