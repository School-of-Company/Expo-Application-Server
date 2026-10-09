package team.startup.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.entity.StandardProgramApplication
import team.startup.application.domain.application.entity.TrainingProgramApplication
import team.startup.application.domain.application.repository.StandardProgramApplicationRepository
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository

@SpringBootTest(properties = ["eureka.client.enabled=false"])
@Testcontainers
class ApplicationPersistenceTests {
    @Autowired
    private lateinit var trainingApplications: TrainingProgramApplicationRepository

    @Autowired
    private lateinit var standardApplications: StandardProgramApplicationRepository

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun clearTables() {
        jdbc.execute("TRUNCATE TABLE tb_training_program_application, tb_standard_program_application RESTART IDENTITY")
    }

    @Test
    fun `기존 ID를 유지하고 사람과 프로그램을 ID로 연결한다`() {
        jdbc.update(
            "INSERT INTO tb_training_program_application (id, trainee_id, training_program_id) VALUES (?, ?, ?)",
            81L,
            12L,
            34L,
        )

        val oldApplication = trainingApplications.findById(81L).orElseThrow()
        assertEquals(12L, oldApplication.traineeId)
        assertEquals(34L, oldApplication.trainingProgramId)
        assertTrue(trainingApplications.existsByTraineeIdAndTrainingProgramId(12L, 34L))
        assertEquals(1L, trainingApplications.countByTrainingProgramId(34L))
        assertEquals(listOf(81L), trainingApplications.findAllByTrainingProgramIdOrderByIdAsc(34L).map { it.id })
        assertEquals(listOf(81L), trainingApplications.findAllByTraineeIdIn(listOf(12L)).map { it.id })

        val newApplication = standardApplications.saveAndFlush(StandardProgramApplication(participantId = 56L, standardProgramId = 78L))
        assertEquals(1L, newApplication.id)
        assertTrue(standardApplications.existsByParticipantIdAndStandardProgramId(56L, 78L))
        assertEquals(listOf(newApplication.id), standardApplications.findAllByStandardProgramIdOrderByIdAsc(78L).map { it.id })
    }

    @Test
    fun `같은 사람의 같은 프로그램 중복 신청은 DB가 거부한다`() {
        trainingApplications.saveAndFlush(TrainingProgramApplication(traineeId = 12L, trainingProgramId = 34L))
        standardApplications.saveAndFlush(StandardProgramApplication(participantId = 56L, standardProgramId = 78L))

        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update("INSERT INTO tb_training_program_application (trainee_id, training_program_id) VALUES (?, ?)", 12L, 34L)
        }
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update("INSERT INTO tb_standard_program_application (participant_id, standard_program_id) VALUES (?, ?)", 56L, 78L)
        }
        assertFalse(trainingApplications.existsByTraineeIdAndTrainingProgramId(99L, 34L))
    }

    @Test
    fun `회차 원장 마이그레이션은 박람회당 활성 참가자를 유일하게 유지한다`() {
        val expoId = java.util.UUID.randomUUID()
        jdbc.update(
            "INSERT INTO tb_preregister_session_state (expo_id, session_id, definition) VALUES (?, 1, '{}'), (?, 2, '{}')",
            expoId,
            expoId,
        )
        jdbc.update(
            "INSERT INTO tb_preregister_application (expo_id, session_id, representative_id, participant_id, status) " +
                "VALUES (?, 1, 42, 42, 'CONFIRMED')",
            expoId,
        )
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update(
                "INSERT INTO tb_preregister_application (expo_id, session_id, representative_id, participant_id, status) " +
                    "VALUES (?, 2, 42, 42, 'WAITING')",
                expoId,
            )
        }
        jdbc.update("UPDATE tb_preregister_application SET status = 'CANCELLED' WHERE expo_id = ?", expoId)
        jdbc.update(
            "INSERT INTO tb_preregister_application (expo_id, session_id, representative_id, participant_id, status) " +
                "VALUES (?, 2, 42, 42, 'WAITING')",
            expoId,
        )
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM tb_preregister_application WHERE expo_id = ?", Int::class.java, expoId))
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
