package team.startup.application

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID

@SpringBootTest(
    properties = ["eureka.client.enabled=false", "application.internal-token=test-token", "management.health.redis.enabled=false"],
)
@AutoConfigureMockMvc
@Testcontainers
class ParticipantNoticeItemsHttpTests {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    private val expo = UUID.randomUUID()
    private val other = UUID.randomUUID()

    @BeforeEach
    fun reset() {
        jdbc.execute(
            "TRUNCATE tb_program_session_application, tb_preregister_application, tb_preregister_reservation, " +
                "tb_preregister_session_state, tb_preregister_deleted_expo CASCADE",
        )
        session(expo, 1, "오전 회차", "2026-10-31T00:30:00Z", "2026-10-31T03:30:00Z", "접수실")
        session(expo, 2, "오후 회차", "2026-10-31T05:00:00Z", "2026-10-31T08:00:00Z", "별관")
        session(other, 1, "다른 박람회 회차", "2026-10-31T00:30:00Z", "2026-10-31T03:30:00Z", "접수실")
    }

    @Test
    fun `requires the internal token`() {
        mvc
            .perform(post(path(expo)).contentType(MediaType.APPLICATION_JSON).content("""{"participantIds":[42]}"""))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `returns confirmed sessions and programs in request order without names or phone numbers`() {
        preregister(expo, 2, 42, "CONFIRMED")
        preregister(expo, 1, 42, "CONFIRMED")
        preregister(expo, 1, 43, "CANCELLED")
        preregister(other, 1, 44, "CONFIRMED")
        program(
            expo,
            42,
            "STANDARD",
            7,
            """{"title":"로봇 체험","startedAt":"2026-10-31 11:00","endedAt":"2026-10-31 12:00","place":"체험존\nA"}""",
            "CONFIRMED",
        )
        program(
            expo,
            42,
            "TRAINING",
            8,
            """{"title":"교원 연수","startedAt":"2026-10-31T09:30:00+09:00","endedAt":"2026-10-31T10:30:00+09:00"}""",
            "CONFIRMED",
        )
        program(expo, 42, "STANDARD", 9, """{"title":"대기 프로그램"}""", "WAITING")
        program(expo, 43, "STANDARD", 10, """{"title":"취소 프로그램"}""", "CANCELLED")

        lookup(expo, "[44, 42, 43]")
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].participantId").value(44))
            .andExpect(jsonPath("$[0].sessions.length()").value(0))
            .andExpect(jsonPath("$[0].programs.length()").value(0))
            .andExpect(jsonPath("$[1].participantId").value(42))
            .andExpect(jsonPath("$[1].sessions[0].title").value("오전 회차"))
            .andExpect(jsonPath("$[1].sessions[0].startedAt").value("2026-10-31T09:30:00+09:00"))
            .andExpect(jsonPath("$[1].sessions[0].endedAt").value("2026-10-31T12:30:00+09:00"))
            .andExpect(jsonPath("$[1].sessions[0].place").value("접수실"))
            .andExpect(jsonPath("$[1].sessions[1].title").value("오후 회차"))
            .andExpect(jsonPath("$[1].programs.length()").value(2))
            .andExpect(jsonPath("$[1].programs[0].programType").value("TRAINING"))
            .andExpect(jsonPath("$[1].programs[0].startedAt").value("2026-10-31T09:30:00+09:00"))
            .andExpect(jsonPath("$[1].programs[1].title").value("로봇 체험"))
            .andExpect(jsonPath("$[1].programs[1].startedAt").value("2026-10-31T11:00:00+09:00"))
            .andExpect(jsonPath("$[1].programs[1].location").value("체험존 A"))
            .andExpect(jsonPath("$[2].participantId").value(43))
            .andExpect(jsonPath("$[2].sessions.length()").value(0))
            .andExpect(jsonPath("$[2].programs.length()").value(0))
            .andExpect(jsonPath("$..name").isEmpty)
            .andExpect(jsonPath("$..phoneNumber").isEmpty)
    }

    @Test
    fun `leaves out a program whose title is unusable and drops unreadable times`() {
        program(expo, 42, "STANDARD", 7, """{"startedAt":"2026-10-31 11:00","endedAt":"2026-10-31 12:00"}""", "CONFIRMED")
        program(expo, 42, "STANDARD", 8, """{"title":"시간 없는 프로그램","startedAt":"곧","endedAt":"2026-10-31 12:00"}""", "CONFIRMED")

        lookup(expo, "[42]")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].programs.length()").value(1))
            .andExpect(jsonPath("$[0].programs[0].title").value("시간 없는 프로그램"))
            .andExpect(jsonPath("$[0].programs[0].startedAt").doesNotExist())
            .andExpect(jsonPath("$[0].programs[0].endedAt").doesNotExist())
    }

    @Test
    fun `rejects empty, too many, duplicate and non positive participant ids`() {
        listOf("[]", "[1,2,3,4,5,6]", "[1,1]", "[0]", "[-3]").forEach {
            lookup(expo, it).andExpect(status().isBadRequest)
        }
    }

    @Test
    fun `answers 404 for an expo that was deleted`() {
        jdbc.update("INSERT INTO tb_preregister_deleted_expo (expo_id) VALUES (?)", expo)

        lookup(expo, "[42]").andExpect(status().isNotFound)
    }

    private fun path(expoId: UUID) = "/internal/expos/$expoId/participants/notice-items"

    private fun lookup(
        expoId: UUID,
        ids: String,
    ): ResultActions =
        mvc.perform(
            post(path(expoId))
                .header("X-Internal-Token", "test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"participantIds":$ids}"""),
        )

    private fun session(
        expoId: UUID,
        id: Long,
        title: String,
        startedAt: String,
        endedAt: String,
        place: String,
    ) {
        jdbc.update(
            "INSERT INTO tb_preregister_session_state (expo_id, session_id, definition) VALUES (?, ?, CAST(? AS jsonb))",
            expoId,
            id,
            """{"id":$id,"expoId":"$expoId","title":"$title","startedAt":"$startedAt","endedAt":"$endedAt","place":"$place","capacity":100,"closed":false,"revision":1}""",
        )
    }

    private fun preregister(
        expoId: UUID,
        sessionId: Long,
        participantId: Long,
        status: String,
    ) {
        jdbc.update(
            "INSERT INTO tb_preregister_application (expo_id, session_id, representative_id, participant_id, status) VALUES (?, ?, ?, ?, ?)",
            expoId,
            sessionId,
            participantId,
            participantId,
            status,
        )
    }

    private fun program(
        expoId: UUID,
        participantId: Long,
        type: String,
        programId: Long,
        snapshot: String,
        status: String,
    ) {
        jdbc.update(
            "INSERT INTO tb_program_session_application " +
                "(id,expo_id,program_type,program_id,group_id,participant_id,trainee_id,status,definition_snapshot," +
                "form_id,form_version,questions_snapshot,answers_snapshot) VALUES (?,?,?,?,?,?,?,?,CAST(? AS jsonb),?,1,'[]','{}')",
            UUID.randomUUID(),
            expoId,
            type,
            programId,
            UUID.randomUUID(),
            participantId,
            if (type == "TRAINING" && status == "CONFIRMED") 50L else null,
            status,
            snapshot,
            UUID.randomUUID(),
        )
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
