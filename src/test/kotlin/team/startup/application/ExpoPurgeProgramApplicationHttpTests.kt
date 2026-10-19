package team.startup.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID

@SpringBootTest(properties = ["eureka.client.enabled=false", "application.internal-token=test-token"])
@AutoConfigureMockMvc
@Testcontainers
class ExpoPurgeProgramApplicationHttpTests {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `박람회 purge는 body의 프로그램 신청과 박람회의 회차 신청·대기·제안·보호자 배치·리포트 스냅샷을 정리한다`() {
        val expo = UUID.randomUUID()
        val other = UUID.randomUUID()
        jdbc.update("INSERT INTO tb_standard_program_application (participant_id, standard_program_id) VALUES (1, ?)", STANDARD)
        jdbc.update("INSERT INTO tb_training_program_application (trainee_id, training_program_id) VALUES (2, ?)", TRAINING)
        val confirmed = session(expo, "STANDARD", STANDARD, 1, "CONFIRMED")
        val student = session(expo, "TRAINING", TRAINING, 3, "WAITING")
        val guardian = session(expo, "TRAINING", TRAINING, 4, "WAITING")
        val untouched = session(other, "STANDARD", OTHER_PROGRAM, 1, "CONFIRMED")
        jdbc.update(
            "INSERT INTO tb_program_session_receipt (expo_id, participant_id, idempotency_key, command, receipt, application_id) " +
                "VALUES (?, 1, 'key', '{}', '{}', ?)",
            expo,
            confirmed,
        )
        jdbc.update("INSERT INTO tb_program_session_history (application_id, status) VALUES (?, 'CONFIRMED')", confirmed)
        jdbc.update("INSERT INTO tb_program_session_event (application_id, status) VALUES (?, 'CONFIRMED')", confirmed)
        val batch = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO tb_program_guardian_batch " +
                "(id, expo_id, program_type, program_id, representative_id, idempotency_key, command, receipt) " +
                "VALUES (?, ?, 'TRAINING', ?, 4, 'batch', '{}', '{}')",
            batch,
            expo,
            TRAINING,
        )
        jdbc.update(
            "INSERT INTO tb_program_guardian_link (student_application_id, guardian_application_id) VALUES (?, ?)",
            student,
            guardian,
        )
        jdbc.update("INSERT INTO tb_program_guardian_member (application_id, batch_id, role) VALUES (?, ?, 'STUDENT')", student, batch)
        jdbc.update("INSERT INTO tb_program_guardian_member (application_id, batch_id, role) VALUES (?, ?, 'GUARDIAN')", guardian, batch)
        jdbc.update(
            "INSERT INTO tb_promotion_offer " +
                "(id, target, expo_id, program_type, program_id, application_id, seats, hard_deadline, send_after, event_id) " +
                "VALUES (?, 'PROGRAM', ?, 'TRAINING', ?, ?, 1, now() + interval '1 day', now(), ?)",
            UUID.randomUUID(),
            expo,
            TRAINING,
            student,
            UUID.randomUUID(),
        )
        listOf(expo, other).forEach {
            jdbc.update(
                "INSERT INTO tb_program_session_state (expo_id, program_type, program_id, definition) VALUES (?, 'STANDARD', ?, '{}')",
                it,
                if (it == expo) STANDARD else OTHER_PROGRAM,
            )
            jdbc.update(
                "INSERT INTO tb_report_application_snapshot (id, expo_id, snapshot_at, expires_at, page_size, items) " +
                    "VALUES (?, ?, now(), now() + interval '1 hour', 10, '[]')",
                UUID.randomUUID(),
                it,
            )
        }

        // Expo ExpoDeletionClient body; a retry must succeed with nothing left to delete.
        repeat(2) {
            mvc
                .perform(
                    purge(expo, """{"standardProgramIds":[$STANDARD],"trainingProgramIds":[$TRAINING]}"""),
                ).andExpect(status().isNoContent)
        }

        assertEquals(0, count("SELECT count(*) FROM tb_standard_program_application WHERE standard_program_id = ?", STANDARD))
        assertEquals(0, count("SELECT count(*) FROM tb_training_program_application WHERE training_program_id = ?", TRAINING))
        assertEquals(1, count("SELECT count(*) FROM tb_deleted_standard_program WHERE program_id = ?", STANDARD))
        assertEquals(1, count("SELECT count(*) FROM tb_deleted_training_program WHERE program_id = ?", TRAINING))
        assertEquals(0, count("SELECT count(*) FROM tb_program_session_application WHERE expo_id = ?", expo))
        listOf(
            "tb_program_session_receipt",
            "tb_program_session_history",
            "tb_program_session_event",
            "tb_program_guardian_member",
        ).forEach {
            assertEquals(0, count("SELECT count(*) FROM $it WHERE application_id IN (?, ?, ?)", confirmed, student, guardian), it)
        }
        assertEquals(0, count("SELECT count(*) FROM tb_program_guardian_link WHERE student_application_id = ?", student))
        listOf("tb_program_guardian_batch", "tb_promotion_offer", "tb_program_session_state", "tb_report_application_snapshot").forEach {
            assertEquals(0, count("SELECT count(*) FROM $it WHERE expo_id = ?", expo), it)
        }
        assertEquals(1, count("SELECT count(*) FROM tb_program_session_application WHERE id = ?", untouched))
        assertEquals(1, count("SELECT count(*) FROM tb_program_session_state WHERE expo_id = ?", other))
        assertEquals(1, count("SELECT count(*) FROM tb_report_application_snapshot WHERE expo_id = ?", other))
    }

    @Test
    fun `body 없는 기존 호출은 받고 양수가 아닌 프로그램 ID는 거절한다`() {
        val expo = UUID.randomUUID()
        mvc.perform(post("/internal/expos/$expo/purge").header("X-Internal-Token", "test-token")).andExpect(status().isNoContent)
        mvc.perform(purge(expo, """{"standardProgramIds":[0],"trainingProgramIds":[]}""")).andExpect(status().isBadRequest)
    }

    private fun purge(
        expo: UUID,
        body: String,
    ) = post("/internal/expos/$expo/purge").header("X-Internal-Token", "test-token").contentType(MediaType.APPLICATION_JSON).content(body)

    private fun session(
        expo: UUID,
        type: String,
        program: Long,
        participant: Long,
        status: String,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO tb_program_session_application " +
                "(id, expo_id, program_type, program_id, group_id, participant_id, trainee_id, status, " +
                "definition_snapshot, form_id, form_version, questions_snapshot, answers_snapshot) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, '{}', ?, 1, '[]', '{}')",
            id,
            expo,
            type,
            program,
            UUID.randomUUID(),
            participant,
            participant,
            status,
            UUID.randomUUID(),
        )
        return id
    }

    private fun count(
        sql: String,
        vararg args: Any,
    ): Int = jdbc.queryForObject(sql, Int::class.java, *args)!!

    companion object {
        private const val STANDARD = 7101L
        private const val TRAINING = 7102L
        private const val OTHER_PROGRAM = 7103L

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
