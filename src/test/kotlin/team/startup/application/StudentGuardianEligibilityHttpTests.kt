package team.startup.application

import com.sun.net.httpserver.HttpServer
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.service.StudentGuardianEligibilityProvider
import team.startup.application.domain.application.service.impl.StudentGuardianEligibilityAdapter
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.util.UUID

@SpringBootTest(properties = ["eureka.client.enabled=false"])
@AutoConfigureMockMvc
@Testcontainers
class StudentGuardianEligibilityHttpTests {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var provider: StudentGuardianEligibilityProvider
    private val mapper = ObjectMapper()

    @BeforeEach
    fun reset() {
        jdbc.execute(
            "TRUNCATE tb_program_guardian_link, tb_program_guardian_member, tb_program_guardian_batch, " +
                "tb_program_session_event, tb_program_session_state, tb_program_change_receipt, " +
                "tb_program_session_history, tb_program_session_receipt, tb_program_session_application, " +
                "tb_standard_program_application, tb_training_program_application, tb_training_application_version, " +
                "tb_training_operation_receipt, tb_deleted_standard_program, tb_deleted_training_program, " +
                "tb_preregister_application, tb_preregister_session_state RESTART IDENTITY",
        )
        jdbc.update("INSERT INTO tb_preregister_session_state (expo_id, session_id, definition) VALUES (?, 1, '{}')", UUID.fromString(EXPO))
        (1L..5L).forEach { id ->
            jdbc.update(
                "INSERT INTO tb_preregister_application (expo_id, session_id, representative_id, participant_id, status) " +
                    "VALUES (?, 1, 1, ?, 'CONFIRMED')",
                UUID.fromString(EXPO),
                id,
            )
        }
        parentRole = "PARENT"
        capacity = 3
        formVersion = 1
        policyEnabled = true
        assertEquals(StudentGuardianEligibilityAdapter::class.java, provider.javaClass)
    }

    @ParameterizedTest
    @EnumSource(ProgramSessionType::class)
    fun `real provider completes three person HTTP batch and idempotent replay for both program types`(type: ProgramSessionType) {
        val receipt = batch(type = type)
        assertTrue(receipt.path("applications").all { it.path("application").path("status").asString() == "CONFIRMED" })
        assertEquals(3, receipt.path("applications").size())
        assertEquals(3L, count("tb_program_session_application"))
        assertEquals(2L, count("tb_program_guardian_link"))
        assertEquals(receipt, batch(type = type))
        assertEquals(1L, count("tb_program_guardian_batch"))
        assertEquals(
            3L,
            count(
                if (type ==
                    ProgramSessionType.STANDARD
                ) {
                    "tb_standard_program_application"
                } else {
                    "tb_training_program_application"
                },
            ),
        )
    }

    @Test
    fun `three person waiting party promotes together only after current guardian role passes again`() {
        jdbc.update("INSERT INTO tb_standard_program_application (participant_id, standard_program_id) VALUES (99, 1)")
        val receipt = batch()
        assertTrue(receipt.path("applications").all { it.path("application").path("status").asString() == "WAITING" })
        jdbc.update("DELETE FROM tb_standard_program_application WHERE participant_id=99")
        parentRole = "TEACHER"
        reconcile()
        assertEquals(3, applicationsWithStatus("WAITING"))
        assertEquals(0L, count("tb_standard_program_application"))
        parentRole = "PARENT"
        reconcile()
        assertEquals(3, applicationsWithStatus("CONFIRMED"))
        assertEquals(3L, count("tb_standard_program_application"))
    }

    @Test
    fun `existing confirmed guardian keeps original answers and only the new student adds a seat`() {
        batch(students = listOf(1), key = "first")
        val original =
            jdbc.queryForObject(
                "SELECT answers_snapshot::text FROM tb_program_session_application WHERE participant_id=3",
                String::class.java,
            )
        val receipt = batch(students = listOf(2), key = "second", reuse = true)
        assertEquals(1, receipt.path("applications").count { it.path("reused").asBoolean() })
        assertEquals(3L, count("tb_program_session_application"))
        assertEquals(3L, count("tb_standard_program_application"))
        assertEquals(
            original,
            jdbc.queryForObject(
                "SELECT answers_snapshot::text FROM tb_program_session_application WHERE participant_id=3",
                String::class.java,
            ),
        )
    }

    @Test
    fun `invalid linked grade or guardian role leaves no partial rows`() {
        batch(grade = "g4", expected = 403)
        assertEquals(0L, count("tb_program_session_application"))
        parentRole = "TEACHER"
        batch(expected = 403)
        assertEquals(0L, count("tb_program_guardian_batch"))
        assertEquals(0L, count("tb_standard_program_application"))
    }

    @Test
    fun `single endpoint rejects lower grades and parents while upper grade still works`() {
        single(1, """{"101":"primary","102":"g1"}""", 409)
        single(3, """{"101":"other"}""", 409)
        assertEquals(0L, count("tb_program_session_application"))
        single(1, """{"101":"primary","102":"g4"}""", 201)
    }

    @Test
    fun `policy OFF preserves the existing single parent application`() {
        policyEnabled = false
        single(3, """{"101":"other"}""", 201)
    }

    @Test
    fun `active batch prevents guardian policy removal through both change routes`() {
        batch()
        val previous = mapper.readTree(definition("STANDARD"))
        val next = previous.deepCopy().asObject()
        next.put("revision", 2)
        next.path("policy").asObject().remove("guardianPolicy")
        listOf("", "/guardian-policy").forEach { suffix ->
            mvc
                .perform(
                    put("/internal/expos/$EXPO/program-changes/${UUID.randomUUID()}$suffix")
                        .header("X-Internal-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"mutations":[{"previous":$previous,"definition":$next}]}"""),
                ).andExpect(status().isConflict)
        }
        assertEquals(3, applicationsWithStatus("CONFIRMED"))
    }

    private fun batch(
        type: ProgramSessionType = ProgramSessionType.STANDARD,
        students: List<Int> = listOf(1, 2),
        key: String = "batch",
        reuse: Boolean = false,
        grade: String = "g1",
        expected: Int = 201,
    ): JsonNode {
        val participants =
            students.map { """{"participantId":$it,"formVersion":1,"answers":{"101":"primary","102":"$grade"}}""" } +
                if (reuse) """{"participantId":3}""" else """{"participantId":3,"formVersion":1,"answers":{"101":"other"}}"""
        val links = students.joinToString { """{"studentId":$it,"guardianId":3}""" }
        val response =
            mvc
                .perform(
                    post("/application/expos/$EXPO/program-sessions/$type/1/application-batches")
                        .with(csrf())
                        .cookie(Cookie("PARTICIPANT_SESSION", "session"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"participants":[${participants.joinToString()}],"links":[$links]}"""),
                ).andExpect(status().`is`(expected))
                .andReturn()
                .response
        return mapper.readTree(response.contentAsString.ifBlank { "{}" })
    }

    private fun single(
        id: Int,
        answers: String,
        expected: Int,
    ) {
        mvc
            .perform(
                post("/application/expos/$EXPO/program-sessions/STANDARD/1/applications")
                    .with(csrf())
                    .cookie(Cookie("PARTICIPANT_SESSION", "session"))
                    .header("Idempotency-Key", "single-$id-$expected")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"participantId":$id,"formVersion":1,"answers":$answers}"""),
            ).andExpect(status().`is`(expected))
    }

    private fun reconcile() {
        mvc
            .perform(
                post("/internal/expos/$EXPO/program-sessions/STANDARD/1/reconciliation")
                    .header("X-Internal-Token", "test-token"),
            ).andExpect(status().isNoContent)
    }

    private fun count(table: String): Long = requireNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java))

    private fun applicationsWithStatus(value: String): Int =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM tb_program_session_application WHERE status=?",
                Int::class.java,
                value,
            ),
        )

    companion object {
        private const val EXPO = "8e986bf3-fb9e-4eef-97c1-bc08b2a18879"
        private const val FORM = "881b7b98-70cc-4129-a387-a3929508fe3a"
        private const val GROUP = "9ff554ff-ecef-46a1-995b-3f22650b2900"

        @Volatile private var parentRole = "PARENT"

        @Volatile private var capacity = 3

        @Volatile private var formVersion = 1

        @Volatile private var policyEnabled = true
        private val json = ObjectMapper()
        private val policy = """{"formVersion":1,"schoolTypeQuestionId":101,"elementarySchoolValue":"primary","gradeQuestionId":102,
            "gradeValues":{${(1..6).joinToString { "\"g$it\":$it" }}}}"""
        private val questions = """[{"id":101,"title":"School","formType":"DROPDOWN","requiredStatus":true,"dynamicFormType":"DEFAULT",
            "jsonData":{"primary":"Elementary","other":"Other"},"otherJson":null},
            {"id":102,"title":"Grade","formType":"DROPDOWN","requiredStatus":true,"dynamicFormType":"DEFAULT",
            "jsonData":{${(1..6).joinToString { "\"g$it\":\"Grade $it\"" }}},
            "otherJson":{"hasEtc":false,"conditional":{"parentIndex":0,"triggerValue":"primary"}}}]"""

        private fun role(id: Long) = if (id == 3L) parentRole else "ELEMENTARY_STUDENT"

        private fun definition(type: String) =
            """{"expoId":"$EXPO","programType":"$type","id":1,"groupId":"$GROUP","revision":1,
            "startedAt":"2099-01-01T10:00:00","endedAt":"2099-01-01T11:00:00",
            "policy":{"formId":"$FORM","allowedOccupations":["ELEMENTARY_STUDENT","PARENT","TEACHER"],"capacity":$capacity,"waitingCapacity":10,
            "applicationStartedAt":"2020-01-01T00:00:00Z","applicationEndedAt":"2100-01-01T00:00:00Z","closed":false,
            "cancelEndedAt":"2100-01-01T00:00:00Z","promotionEndedAt":"2100-01-01T00:00:00Z"${if (policyEnabled) ",\"guardianPolicy\":$policy" else ""}}}"""

        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    val path = exchange.requestURI.path
                    val body = exchange.requestBody.bufferedReader().use { it.readText() }
                    val response =
                        when {
                            path.endsWith("participant-sessions/resolve") -> {
                                """{"expoId":"$EXPO","phoneNumber":"01012345678","expiresAt":"2100-01-01T00:00:00"}"""
                            }

                            path.endsWith("standard-participants/managed") -> {
                                """{"registered":true,"participants":[${(1L..5L).joinToString {
                                    """{"participantId":$it,"name":"Person$it","occupation":"${role(it)}","representative":${it == 1L}}"""
                                }}]}"""
                            }

                            path.endsWith("/managed/select") || path.endsWith("/standard-participants/details") -> {
                                json.readTree(body).path("participantIds").joinToString(prefix = "[", postfix = "]") {
                                    """{"participantId":${it.asLong()},"occupation":"${role(it.asLong())}"}"""
                                }
                            }

                            path.contains("/forms/by-id/") -> {
                                """{"id":"$FORM","expoId":"$EXPO","purpose":"PROGRAM","version":$formVersion,
                        "startDate":"2020-01-01T00:00:00Z","endDate":"2100-01-01T00:00:00Z","dynamicForm":$questions}"""
                            }

                            path.endsWith("resolve-or-create-by-participant") -> {
                                """{"traineeId":${json.readTree(body).path("participantId").asLong() + 100}}"""
                            }

                            path.endsWith("training-programs/batch") -> {
                                "[${definition("TRAINING")}]"
                            }

                            else -> {
                                definition("STANDARD")
                            }
                        }
                    val bytes = response.toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                start()
            }

        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")

        @DynamicPropertySource @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("application.program-sessions.enabled") { true }
            registry.add("application.program-sessions.reconcile-delay") { 3600000 }
            listOf("expo", "form", "user").forEach { target ->
                registry.add("application.registration.$target-internal-token") { "test-token" }
                registry.add("application.registration.$target-url") { "http://127.0.0.1:${server.address.port}" }
            }
        }
    }
}
