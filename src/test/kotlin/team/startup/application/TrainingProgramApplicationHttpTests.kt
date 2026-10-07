package team.startup.application

import com.fasterxml.jackson.annotation.JsonInclude
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.entity.TrainingProgramCategory
import team.startup.application.domain.application.presentation.dto.ApplyTrainingProgramsCommand
import team.startup.application.domain.application.presentation.dto.TraineeReference
import team.startup.application.domain.application.presentation.dto.TrainingOperationType
import team.startup.application.domain.application.presentation.dto.TrainingProgramReference
import team.startup.application.domain.application.repository.TrainingOperationRepository
import team.startup.application.domain.application.service.impl.TrainingOperationJournal
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var operations: TrainingOperationRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private val http = HttpClient.newHttpClient()

    @BeforeEach
    fun clearTables() {
        jdbc.execute(
            "TRUNCATE TABLE tb_training_program_application, tb_deleted_training_program, " +
                "tb_training_operation_receipt, tb_training_application_version RESTART IDENTITY",
        )
    }

    @Test
    fun `토큰이 없거나 틀리면 본문 검증과 저장 전에 401이다`() {
        listOf(null, "wrong-token", "").forEach { token ->
            assertEquals(401, request("POST", BASE, applyBody(42, 7), token).statusCode())
            assertEquals(401, request("POST", BASE, "{}", token).statusCode())
            assertEquals(401, request("PUT", "$BASE/trainee/42", applyBody(42, 7), token).statusCode())
            assertEquals(401, request("GET", "$BASE/program/7", token = token).statusCode())
            assertEquals(401, request("DELETE", "$BASE/program/7", token = token).statusCode())
            assertEquals(401, request("GET", "$BASE/operations/${UUID.randomUUID()}", token = token).statusCode())
            assertEquals(401, request("GET", "$BASE/trainee/42/version", token = token).statusCode())
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

    @Test
    fun `교체는 선택 항목을 유지하고 빠진 항목을 지우며 재시도해도 출석과 신청 ID를 보존한다`() {
        assertEquals(201, request("POST", BASE, applyBody(42, 7, 8)).statusCode())
        jdbc.update("UPDATE tb_training_program_application SET status = true WHERE trainee_id = 42 AND training_program_id = 8")

        repeat(2) {
            val result = request("PUT", "$BASE/trainee/42", applyBody(42, 8, 9))
            assertEquals(204, result.statusCode())
            assertEquals("", result.body())
        }
        assertEquals(0L, count(7))
        assertEquals(1L, count(8))
        assertEquals(1L, count(9))
        assertEquals(
            1L,
            jdbc.queryForObject(
                "SELECT count(*) FROM tb_training_program_application WHERE trainee_id = 42 AND training_program_id = 8 AND id = 2 AND status = true",
                Long::class.java,
            ),
        )
        assertEquals(201, request("POST", BASE, applyBody(43, 7)).statusCode())
    }

    @Test
    fun `빈 목록은 해당 연수자의 모든 신청을 취소하고 재시도도 성공한다`() {
        assertEquals(201, request("POST", BASE, applyBody(42, 7, 8)).statusCode())
        assertEquals(201, request("POST", BASE, applyBody(43, 7)).statusCode())
        repeat(2) { assertEquals(204, request("PUT", "$BASE/trainee/42", applyBody(42)).statusCode()) }
        assertEquals(1L, count(7))
        assertEquals(0L, count(8))
    }

    @Test
    fun `정원이 찬 프로그램에 대한 본인 신청은 교체 때 유지할 수 있다`() {
        jdbc.update(
            "INSERT INTO tb_training_program_application (trainee_id, training_program_id) " +
                "SELECT person_id, 30 FROM generate_series(1000, 1023) AS person_id",
        )
        assertEquals(201, request("POST", BASE, applyBody(42, 30)).statusCode())
        assertEquals(204, request("PUT", "$BASE/trainee/42", applyBody(42, 30, 31)).statusCode())
        assertEquals(25L, count(30))
        assertEquals(1L, count(31))
    }

    @Test
    fun `교체 요청의 불일치 중복 삭제된 프로그램 정원 초과는 기존 신청을 보존한다`() {
        assertEquals(201, request("POST", BASE, applyBody(42, 7)).statusCode())
        jdbc.update(
            "INSERT INTO tb_training_program_application (trainee_id, training_program_id) " +
                "SELECT person_id, 30 FROM generate_series(1000, 1024) AS person_id",
        )
        assertEquals(204, request("DELETE", "$BASE/program/40").statusCode())
        val invalid =
            listOf(
                400 to applyBody(43, 8),
                400 to applyBody(42, 8, 8),
                400 to """{"trainee":{"id":42,"expoId":"expo-1"},"programs":[{"id":8,"expoId":"expo-2","category":"CHOICE"}]}""",
                409 to applyBody(42, 30),
                409 to applyBody(42, 40),
            )
        invalid.forEach { (status, body) ->
            assertEquals(status, request("PUT", "$BASE/trainee/42", body).statusCode(), body)
        }
        assertEquals(1L, count(7))
        assertEquals(0L, count(8))
    }

    @Test
    fun `교체의 저장 실패는 삭제까지 롤백한다`() {
        assertEquals(201, request("POST", BASE, applyBody(42, 7)).statusCode())
        jdbc.execute(
            "CREATE FUNCTION fail_training_insert() RETURNS trigger LANGUAGE plpgsql AS '\n" +
                "BEGIN IF NEW.training_program_id = 8 THEN RAISE EXCEPTION ''forced insert failure''; END IF; RETURN NEW; END'",
        )
        jdbc.execute(
            "CREATE TRIGGER fail_training_insert BEFORE INSERT ON tb_training_program_application " +
                "FOR EACH ROW EXECUTE FUNCTION fail_training_insert()",
        )
        try {
            assertEquals(500, request("PUT", "$BASE/trainee/42", applyBody(42, 8)).statusCode())
            assertEquals(1L, count(7))
            assertEquals(0L, count(8))
        } finally {
            jdbc.execute("DROP TRIGGER fail_training_insert ON tb_training_program_application")
            jdbc.execute("DROP FUNCTION fail_training_insert()")
        }
    }

    @Test
    fun `동시 교체와 추가 신청은 한 연수자에 부분 목록을 남기지 않는다`() {
        val executor = Executors.newFixedThreadPool(3)
        try {
            repeat(5) { round ->
                val traineeId = 200L + round
                val start = CountDownLatch(1)
                val tasks =
                    listOf(
                        executor.submit<Int> {
                            start.await()
                            request("PUT", "$BASE/trainee/$traineeId", applyBody(traineeId, 50, 51)).statusCode()
                        },
                        executor.submit<Int> {
                            start.await()
                            request("PUT", "$BASE/trainee/$traineeId", applyBody(traineeId, 60, 61)).statusCode()
                        },
                        executor.submit<Int> {
                            start.await()
                            request("POST", BASE, applyBody(traineeId, 70)).statusCode()
                        },
                    )
                start.countDown()
                val statuses = tasks.map { it.get(15, TimeUnit.SECONDS) }
                assertEquals(listOf(204, 204), statuses.take(2))
                assertTrue(statuses[2] == 201 || statuses[2] == 409)
                val ids =
                    jdbc.queryForList(
                        "SELECT training_program_id FROM tb_training_program_application WHERE trainee_id = ? ORDER BY training_program_id",
                        Long::class.java,
                        traineeId,
                    )
                val validLists =
                    listOf(
                        listOf(50L, 51L),
                        listOf(60L, 61L),
                        listOf(50L, 51L, 70L),
                        listOf(60L, 61L, 70L),
                    )
                assertTrue(ids in validLists, "$ids")
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `교체와 프로그램 삭제가 경합해도 삭제된 프로그램의 신청은 남지 않는다`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(5) { round ->
                val traineeId = 300L + round
                val programId = 300L + round
                val start = CountDownLatch(1)
                val replace =
                    executor.submit<Int> {
                        start.await()
                        request("PUT", "$BASE/trainee/$traineeId", applyBody(traineeId, programId)).statusCode()
                    }
                val delete =
                    executor.submit<Int> {
                        start.await()
                        request("DELETE", "$BASE/program/$programId").statusCode()
                    }
                start.countDown()
                assertTrue(replace.get(15, TimeUnit.SECONDS) in listOf(204, 409))
                assertEquals(204, delete.get(15, TimeUnit.SECONDS))
                assertEquals(0L, count(programId))
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `ADD 성공 응답을 유실해도 영수증으로 확인하고 순서가 다른 동일 명령을 재전달한다`() {
        val operationId = UUID.randomUUID()
        assertEquals(201, request("POST", BASE, operationBody(operationId, 42, 8, 7, expectedVersion = 0)).statusCode())
        val receipt = request("GET", "$BASE/operations/$operationId")
        assertEquals(200, receipt.statusCode())
        val result = mapper.readTree(receipt.body())
        assertEquals("SUCCEEDED", result.path("status").asString())
        assertEquals("ADD", result.path("operationType").asString())
        assertEquals(operationId.toString(), result.path("operationId").asString())
        assertEquals("expo-1", result.path("expoId").asString())
        assertEquals(42L, result.path("traineeId").asLong())
        assertEquals(1L, result.path("version").asLong())
        assertTrue(result.path("changed").asBoolean())
        assertEquals("[7,8]", result.path("programIds").toString())
        assertTrue(result.path("completedAt").asString().isNotBlank())
        val replay = request("POST", BASE, operationBody(operationId, 42, 7, 8, expectedVersion = 0))
        assertEquals(201, replay.statusCode())
        assertEquals("", replay.body())
        assertEquals(receipt.body(), request("GET", "$BASE/operations/$operationId").body())
        assertEquals(1L, version(42))
        assertEquals(1L, count(7))
        assertEquals(1L, count(8))
    }

    @Test
    fun `Application commit 뒤 프록시가 HTTP 응답을 끊어도 영수증 조회와 재시도는 성공한다`() {
        val operationId = UUID.randomUUID()
        val body = operationBody(operationId, 42, 7, expectedVersion = 0)
        val upstreamStatus = AtomicInteger()
        val committed = CountDownLatch(1)
        val proxy = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        proxy.createContext(BASE) { exchange ->
            upstreamStatus.set(request("POST", BASE, exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)).statusCode())
            committed.countDown()
            exchange.close()
        }
        proxy.start()
        try {
            val lost =
                HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:${proxy.address.port}$BASE"))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
            assertThrows(IOException::class.java) { http.send(lost, HttpResponse.BodyHandlers.ofString()) }
            assertTrue(committed.await(15, TimeUnit.SECONDS))
            assertEquals(201, upstreamStatus.get())
            assertEquals(200, request("GET", "$BASE/operations/$operationId").statusCode())
            assertEquals(201, request("POST", BASE, body).statusCode())
            assertEquals(1L, count(7))
            assertEquals(1L, version(42))
        } finally {
            proxy.stop(0)
        }
    }

    @Test
    fun `operationId는 연수자 행사 종류 프로그램 분류 기대 버전이 다른 명령에 재사용할 수 없다`() {
        val operationId = UUID.randomUUID()
        val original = operationBody(operationId, 42, 7)
        assertEquals(201, request("POST", BASE, original).statusCode())
        val conflicts =
            listOf(
                operationBody(operationId, 43, 7),
                operationBody(operationId, 42, 8),
                original.replace("expo-1", "expo-2"),
                original.replace("CHOICE", "ESSENTIAL"),
                operationBody(operationId, 42, 7, expectedVersion = 1),
            )
        conflicts.forEach { assertEquals(409, request("POST", BASE, it).statusCode()) }
        assertEquals(409, request("PUT", "$BASE/trainee/42", original).statusCode())
        assertEquals(1L, version(42))
        assertEquals(0L, version(43))
        assertEquals(0L, count(8))
    }

    @Test
    fun `같은 키의 동시 ADD는 모두 201이고 신청 버전 영수증은 한 번만 저장한다`() {
        val operationId = UUID.randomUUID()
        val body = operationBody(operationId, 42, 7, expectedVersion = 0)
        assertEquals(
            listOf(201, 201),
            concurrently({ request("POST", BASE, body).statusCode() }, { request("POST", BASE, body).statusCode() }),
        )
        assertEquals(1L, count(7))
        assertEquals(1L, version(42))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM tb_training_operation_receipt", Long::class.java))
    }

    @Test
    fun `HTTP 직렬화 설정이 바뀌어도 저장된 성공 명령을 재사용한다`() {
        val operationId = UUID.randomUUID()
        assertEquals(201, request("POST", BASE, operationBody(operationId, 42, 7)).statusCode())
        assertEquals(
            """[1,"ADD",42,"expo-1",null,[[7,"expo-1","CHOICE"]]]""",
            jdbc.queryForObject(
                "SELECT command FROM tb_training_operation_receipt WHERE operation_id = ?",
                String::class.java,
                operationId,
            ),
        )
        val command =
            ApplyTrainingProgramsCommand(
                TraineeReference(42, "expo-1"),
                listOf(TrainingProgramReference(7, "expo-1", TrainingProgramCategory.CHOICE)),
                operationId,
            )
        val changedMapper =
            JsonMapper
                .builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .changeDefaultPropertyInclusion { it.withValueInclusion(JsonInclude.Include.NON_NULL) }
                .build()
        val restartedJournal = TrainingOperationJournal(operations, changedMapper)
        assertEquals(
            true,
            TransactionTemplate(transactionManager).execute { restartedJournal.replay(command, TrainingOperationType.ADD) },
        )
        assertEquals(1L, version(42))
        assertEquals(1L, count(7))
    }

    @Test
    fun `같은 키의 동시 다른 연수자 명령은 한 건만 성공한다`() {
        val operationId = UUID.randomUUID()
        val statuses =
            concurrently(
                { request("POST", BASE, operationBody(operationId, 42, 7)).statusCode() },
                { request("POST", BASE, operationBody(operationId, 43, 8)).statusCode() },
            )
        assertEquals(listOf(201, 409), statuses.sorted())
        assertEquals(1L, count(7)!! + count(8)!!)
    }

    @Test
    fun `동시 다른 키 교체는 기대 버전으로 한 건만 성공하고 늦은 호출은 거부한다`() {
        assertEquals(201, request("POST", BASE, applyBody(42, 7)).statusCode())
        val keys = listOf(UUID.randomUUID(), UUID.randomUUID())
        val bodies = listOf(operationBody(keys[0], 42, 8, expectedVersion = 1), operationBody(keys[1], 42, 9, expectedVersion = 1))
        val statuses =
            concurrently(
                { request("PUT", "$BASE/trainee/42", bodies[0]).statusCode() },
                { request("PUT", "$BASE/trainee/42", bodies[1]).statusCode() },
            )
        assertEquals(listOf(204, 409), statuses.sorted())
        assertEquals(2L, version(42))
        val succeeded = statuses.indexOf(204)
        assertEquals(204, request("PUT", "$BASE/trainee/42", bodies[succeeded]).statusCode())
        assertEquals(200, request("GET", "$BASE/operations/${keys[succeeded]}").statusCode())
        assertEquals(404, request("GET", "$BASE/operations/${keys[1 - succeeded]}").statusCode())
        assertEquals(409, request("PUT", "$BASE/trainee/42", bodies[1 - succeeded]).statusCode())
        assertEquals(0L, count(7))
        assertEquals(1L, count(8)!! + count(9)!!)
    }

    @Test
    fun `취소 뒤 새 ADD는 새 버전이고 이전 성공 재전달은 취소를 되돌리지 않는다`() {
        val addId = UUID.randomUUID()
        val add = operationBody(addId, 42, 7)
        assertEquals(201, request("POST", BASE, add).statusCode())
        val original = request("GET", "$BASE/operations/$addId").body()
        val cancelId = UUID.randomUUID()
        val cancel = operationBody(cancelId, 42, expectedVersion = 1)
        assertEquals(204, request("PUT", "$BASE/trainee/42", cancel).statusCode())
        assertEquals(2L, version(42))
        assertEquals(201, request("POST", BASE, add).statusCode())
        assertEquals(0L, count(7))
        assertEquals(original, request("GET", "$BASE/operations/$addId").body())
        assertEquals(201, request("POST", BASE, operationBody(UUID.randomUUID(), 42, 7, expectedVersion = 2)).statusCode())
        assertEquals(3L, version(42))
        assertEquals(204, request("PUT", "$BASE/trainee/42", cancel).statusCode())
        assertEquals(1L, count(7))
        assertEquals(3L, version(42))
    }

    @Test
    fun `같은 목록 교체와 빈 목록 재취소는 성공 영수증을 남기지만 버전을 올리지 않는다`() {
        assertEquals(201, request("POST", BASE, applyBody(42, 7)).statusCode())
        val noChange = UUID.randomUUID()
        assertEquals(204, request("PUT", "$BASE/trainee/42", operationBody(noChange, 42, 7)).statusCode())
        assertEquals(1L, version(42))
        assertEquals(false, mapper.readTree(request("GET", "$BASE/operations/$noChange").body()).path("changed").asBoolean())
        assertEquals(204, request("PUT", "$BASE/trainee/42", applyBody(42)).statusCode())
        val empty = UUID.randomUUID()
        assertEquals(204, request("PUT", "$BASE/trainee/42", operationBody(empty, 42)).statusCode())
        assertEquals(2L, version(42))
        assertEquals(false, mapper.readTree(request("GET", "$BASE/operations/$empty").body()).path("changed").asBoolean())
        assertEquals("[]", mapper.readTree(request("GET", "$BASE/operations/$empty").body()).path("programIds").toString())
    }

    @Test
    fun `삭제는 영향 받은 연수자 버전만 갱신하고 재삭제와 이전 성공 재전달은 상태를 바꾸지 않는다`() {
        val operationId = UUID.randomUUID()
        val body = operationBody(operationId, 42, 7, 8)
        assertEquals(201, request("POST", BASE, body).statusCode())
        assertEquals(201, request("POST", BASE, applyBody(43, 7)).statusCode())
        assertEquals(201, request("POST", BASE, applyBody(44, 8)).statusCode())
        val original = request("GET", "$BASE/operations/$operationId").body()
        repeat(2) { assertEquals(204, request("DELETE", "$BASE/program/7").statusCode()) }
        assertEquals(2L, version(42))
        assertEquals(2L, version(43))
        assertEquals(1L, version(44))
        assertEquals(201, request("POST", BASE, body).statusCode())
        assertEquals(0L, count(7))
        assertEquals(original, request("GET", "$BASE/operations/$operationId").body())
        val newId = UUID.randomUUID()
        assertEquals(409, request("POST", BASE, operationBody(newId, 42, 7)).statusCode())
        assertEquals(404, request("GET", "$BASE/operations/$newId").statusCode())
        assertEquals(2L, version(42))
    }

    @Test
    fun `실패한 명령은 성공 영수증을 남기지 않고 같은 키로 재시도할 수 있다`() {
        val operationId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO tb_training_program_application (trainee_id, training_program_id) " +
                "SELECT person_id, 7 FROM generate_series(1000, 1024) AS person_id",
        )
        val body = operationBody(operationId, 42, 7, 8, expectedVersion = 0)
        assertEquals(409, request("POST", BASE, body).statusCode())
        assertEquals(404, request("GET", "$BASE/operations/$operationId").statusCode())
        assertEquals(0L, version(42))
        assertEquals(0L, count(8))
        jdbc.update("DELETE FROM tb_training_program_application WHERE trainee_id = 1000")
        assertEquals(201, request("POST", BASE, body).statusCode())
        assertEquals(1L, version(42))
        assertEquals(200, request("GET", "$BASE/operations/$operationId").statusCode())
    }

    @Test
    fun `영수증 저장 실패는 신청 교체와 버전까지 롤백한다`() {
        assertEquals(201, request("POST", BASE, applyBody(42, 7)).statusCode())
        jdbc.execute(
            "CREATE FUNCTION fail_receipt_insert() RETURNS trigger LANGUAGE plpgsql AS " +
                "'BEGIN RAISE EXCEPTION ''forced receipt failure''; END'",
        )
        jdbc.execute(
            "CREATE TRIGGER fail_receipt_insert BEFORE INSERT ON tb_training_operation_receipt " +
                "FOR EACH ROW EXECUTE FUNCTION fail_receipt_insert()",
        )
        val operationId = UUID.randomUUID()
        val body = operationBody(operationId, 42, 8, expectedVersion = 1)
        try {
            assertEquals(500, request("PUT", "$BASE/trainee/42", body).statusCode())
            assertEquals(1L, count(7))
            assertEquals(0L, count(8))
            assertEquals(1L, version(42))
            assertEquals(404, request("GET", "$BASE/operations/$operationId").statusCode())
        } finally {
            jdbc.execute("DROP TRIGGER fail_receipt_insert ON tb_training_operation_receipt")
            jdbc.execute("DROP FUNCTION fail_receipt_insert()")
        }
        assertEquals(204, request("PUT", "$BASE/trainee/42", body).statusCode())
        assertEquals(2L, version(42))
    }

    @Test
    fun `서로 다른 프로그램 동시 삭제는 같은 연수자 버전을 두 번 갱신한다`() {
        assertEquals(201, request("POST", BASE, applyBody(42, 7, 8)).statusCode())
        assertEquals(201, request("POST", BASE, applyBody(43, 7, 8)).statusCode())
        assertEquals(
            listOf(204, 204),
            concurrently({ request("DELETE", "$BASE/program/7").statusCode() }, { request("DELETE", "$BASE/program/8").statusCode() }),
        )
        assertEquals(3L, version(42))
        assertEquals(3L, version(43))
        assertEquals(0L, count(7))
        assertEquals(0L, count(8))
    }

    @Test
    fun `필수 프로그램 최대 규모 삭제도 모든 영향 연수자의 버전을 기록한다`() {
        jdbc.update(
            "INSERT INTO tb_training_program_application (trainee_id, training_program_id) " +
                "SELECT person_id, 7 FROM generate_series(1, 99999) AS person_id",
        )
        assertEquals(204, request("DELETE", "$BASE/program/7").statusCode())
        assertEquals(0L, count(7))
        assertEquals(
            99_999L,
            jdbc.queryForObject("SELECT count(*) FROM tb_training_application_version WHERE version = 1", Long::class.java),
        )
        assertEquals(204, request("DELETE", "$BASE/program/7").statusCode())
        assertEquals(1L, version(42))
    }

    @Test
    fun `잘못된 작업 ID와 기대 버전은 400이고 없는 영수증은 404다`() {
        assertEquals(404, request("GET", "$BASE/operations/${UUID.randomUUID()}").statusCode())
        assertEquals(400, request("GET", "$BASE/operations/not-a-uuid").statusCode())
        assertEquals(400, request("GET", "$BASE/trainee/0/version").statusCode())
        assertEquals(0L, version(42))
        assertEquals(400, request("POST", BASE, applyBody(42, 7).dropLast(1) + ",\"expectedVersion\":0}").statusCode())
        assertEquals(400, request("POST", BASE, operationBody(UUID.randomUUID(), 42, 7, expectedVersion = -1)).statusCode())
        assertEquals(
            400,
            request(
                "POST",
                BASE,
                operationBody(UUID.randomUUID(), 42, 7).replace(Regex("\"operationId\":\"[^\"]+\""), "\"operationId\":\"bad\""),
            ).statusCode(),
        )
        assertEquals(0L, count(7))
    }

    private fun operationBody(
        operationId: UUID,
        traineeId: Long,
        vararg programIds: Long,
        expectedVersion: Long? = null,
    ) = applyBody(traineeId, *programIds).dropLast(1) + ",\"operationId\":\"$operationId\"" +
        (expectedVersion?.let { ",\"expectedVersion\":$it" } ?: "") + "}"

    private fun version(traineeId: Long): Long =
        mapper.readTree(request("GET", "$BASE/trainee/$traineeId/version").body()).path("version").asLong()

    private fun concurrently(vararg actions: () -> Int): List<Int> {
        val executor = Executors.newFixedThreadPool(actions.size)
        val start = CountDownLatch(1)
        try {
            val tasks =
                actions.map { action ->
                    executor.submit<Int> {
                        start.await()
                        action()
                    }
                }
            start.countDown()
            return tasks.map { it.get(15, TimeUnit.SECONDS) }
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
