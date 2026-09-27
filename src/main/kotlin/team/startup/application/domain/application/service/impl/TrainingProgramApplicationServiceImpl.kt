package team.startup.application.domain.application.service.impl

import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.entity.TrainingProgramApplication
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.ApplyTrainingProgramsCommand
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import team.startup.application.domain.application.service.TrainingProgramApplicationService

@Service
class TrainingProgramApplicationServiceImpl(
    private val applications: TrainingProgramApplicationRepository,
    private val jdbc: JdbcTemplate,
) : TrainingProgramApplicationService {
    @Transactional
    override fun execute(command: ApplyTrainingProgramsCommand) {
        val programIds = command.programs.map { it.id }
        require(command.trainee.id > 0 && command.trainee.expoId.isNotBlank())
        require(programIds.isNotEmpty() && programIds.all { it > 0 } && programIds.distinct().size == programIds.size)
        require(command.programs.all { it.expoId == command.trainee.expoId })

        programIds.sorted().forEach(::lockTrainingProgram)
        command.programs.forEach { program ->
            if (applications.existsByTraineeIdAndTrainingProgramId(command.trainee.id, program.id)) {
                throw ProgramApplicationConflictException("이미 신청한 연수 프로그램입니다.")
            }
            if (applications.countByTrainingProgramId(program.id) >= program.category.capacity) {
                throw ProgramApplicationConflictException("연수 프로그램 정원이 찼습니다.")
            }
        }
        applications.saveAllAndFlush(
            command.programs.map { program ->
                TrainingProgramApplication(traineeId = command.trainee.id, trainingProgramId = program.id)
            },
        )
    }

    private fun lockTrainingProgram(programId: Long) {
        jdbc.execute(
            ConnectionCallback<Unit> { connection ->
                connection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { statement ->
                    statement.setLong(1, programId)
                    statement.execute()
                }
            },
        )
    }
}
