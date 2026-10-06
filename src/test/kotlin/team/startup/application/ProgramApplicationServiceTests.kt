package team.startup.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.entity.TrainingProgramCategory
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.ApplyStandardProgramsCommand
import team.startup.application.domain.application.presentation.dto.ApplyTrainingProgramsCommand
import team.startup.application.domain.application.presentation.dto.ParticipantReference
import team.startup.application.domain.application.presentation.dto.StandardProgramReference
import team.startup.application.domain.application.presentation.dto.TraineeReference
import team.startup.application.domain.application.presentation.dto.TrainingProgramReference
import team.startup.application.domain.application.service.StandardProgramApplicationService
import team.startup.application.domain.application.service.TrainingProgramApplicationService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(properties = ["eureka.client.enabled=false"])
@Testcontainers
class ProgramApplicationServiceTests {
    @Autowired
    private lateinit var trainingApplications: TrainingProgramApplicationService

    @Autowired
    private lateinit var standardApplications: StandardProgramApplicationService

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun clearTables() {
        jdbc.execute("TRUNCATE TABLE tb_training_program_application, tb_standard_program_application RESTART IDENTITY")
    }

    @Test
    fun `연수 프로그램의 모든 신청 방법에서 정원을 지킨다`() {
        jdbc.update(
            "INSERT INTO tb_training_program_application (trainee_id, training_program_id) " +
                "SELECT person_id, 10 FROM generate_series(1, 24) AS person_id",
        )

        trainingApplications.execute(trainingCommand(25, 10))
        assertEquals(25L, trainingCount(10))
        assertThrows(ProgramApplicationConflictException::class.java) {
            trainingApplications.execute(trainingCommand(26, 10))
        }
        assertEquals(25L, trainingCount(10))
    }

    @Test
    fun `필수 연수 프로그램은 99999번째 신청을 허용하고 다음 신청을 거부한다`() {
        jdbc.update(
            "INSERT INTO tb_training_program_application (trainee_id, training_program_id) " +
                "SELECT person_id, 10 FROM generate_series(1, 99998) AS person_id",
        )

        trainingApplications.execute(trainingCommand(99_999, 10, category = TrainingProgramCategory.ESSENTIAL))
        assertEquals(99_999L, trainingCount(10))
        assertThrows(ProgramApplicationConflictException::class.java) {
            trainingApplications.execute(trainingCommand(100_000, 10, category = TrainingProgramCategory.ESSENTIAL))
        }
        assertEquals(99_999L, trainingCount(10))
    }

    @Test
    fun `여러 프로그램 중 하나가 중복이면 모두 저장하지 않는다`() {
        trainingApplications.execute(trainingCommand(1, 20))

        assertThrows(ProgramApplicationConflictException::class.java) {
            trainingApplications.execute(trainingCommand(1, 10, 20))
        }
        assertEquals(0L, trainingCount(10))
        assertEquals(1L, trainingCount(20))
    }

    @Test
    fun `정원 마지막 자리를 동시에 신청해도 한 명만 저장한다`() {
        jdbc.update(
            "INSERT INTO tb_training_program_application (trainee_id, training_program_id) " +
                "SELECT person_id, 10 FROM generate_series(1, 24) AS person_id",
        )
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results =
                listOf(25L, 26L).map { traineeId ->
                    executor.submit<Throwable?> {
                        start.await()
                        runCatching { trainingApplications.execute(trainingCommand(traineeId, 10)) }.exceptionOrNull()
                    }
                }
            start.countDown()
            val outcomes = results.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, outcomes.count { it == null })
            assertTrue(outcomes.filterNotNull().single() is ProgramApplicationConflictException)
            assertEquals(25L, trainingCount(10))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `일반 프로그램은 기존 등록자의 목록 신청을 저장하고 중복을 거부한다`() {
        val command =
            ApplyStandardProgramsCommand(
                ParticipantReference(1, "expo-1"),
                listOf(StandardProgramReference(30, "expo-1"), StandardProgramReference(40, "expo-1")),
            )
        standardApplications.execute(command)
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM tb_standard_program_application", Long::class.java))
        assertThrows(ProgramApplicationConflictException::class.java) { standardApplications.execute(command) }
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM tb_standard_program_application", Long::class.java))
    }

    @Test
    fun `일반 프로그램 동시 중복 신청은 한 건만 저장하고 충돌을 반환한다`() {
        val command =
            ApplyStandardProgramsCommand(
                ParticipantReference(1, "expo-1"),
                listOf(StandardProgramReference(30, "expo-1")),
            )
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results =
                (1..2).map {
                    executor.submit<Throwable?> {
                        start.await()
                        runCatching { standardApplications.execute(command) }.exceptionOrNull()
                    }
                }
            start.countDown()
            val outcomes = results.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, outcomes.count { it == null })
            assertTrue(outcomes.filterNotNull().single() is ProgramApplicationConflictException)
            assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM tb_standard_program_application", Long::class.java))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `일반 프로그램은 25명을 초과한 서로 다른 참가자의 신청을 저장한다`() {
        (1L..26L).forEach { participantId ->
            standardApplications.execute(
                ApplyStandardProgramsCommand(
                    ParticipantReference(participantId, "expo-1"),
                    listOf(StandardProgramReference(30, "expo-1")),
                ),
            )
        }

        assertEquals(26L, jdbc.queryForObject("SELECT count(*) FROM tb_standard_program_application", Long::class.java))
    }

    @Test
    fun `다른 박람회 프로그램과 요청 내 중복 ID는 거부한다`() {
        assertThrows(IllegalArgumentException::class.java) {
            trainingApplications.execute(
                ApplyTrainingProgramsCommand(
                    TraineeReference(1, "expo-1"),
                    listOf(TrainingProgramReference(10, "expo-2", TrainingProgramCategory.CHOICE)),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            trainingApplications.execute(trainingCommand(1, 10, 10))
        }
        assertEquals(0L, trainingCount(10))
    }

    private fun trainingCommand(
        traineeId: Long,
        vararg programIds: Long,
        category: TrainingProgramCategory = TrainingProgramCategory.CHOICE,
    ) = ApplyTrainingProgramsCommand(
        TraineeReference(traineeId, "expo-1"),
        programIds.map { TrainingProgramReference(it, "expo-1", category) },
    )

    private fun trainingCount(programId: Long) =
        jdbc.queryForObject(
            "SELECT count(*) FROM tb_training_program_application WHERE training_program_id = ?",
            Long::class.java,
            programId,
        )

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
