package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.entity.TrainingProgramApplication
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.ApplyTrainingProgramsCommand
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import team.startup.application.domain.application.service.ReplaceTrainingProgramsService

@Service
class ReplaceTrainingProgramsServiceImpl(
    private val applications: TrainingProgramApplicationRepository,
) : ReplaceTrainingProgramsService {
    @Transactional
    override fun execute(command: ApplyTrainingProgramsCommand) {
        val programIds = command.programs.map { it.id }
        require(command.trainee.id > 0 && command.trainee.expoId.isNotBlank())
        require(programIds.all { it > 0 } && programIds.distinct().size == programIds.size)
        require(command.programs.all { it.expoId == command.trainee.expoId })

        applications.lockTrainee(command.trainee.id)
        val oldIds = applications.findAllByTraineeId(command.trainee.id).map { it.trainingProgramId }
        (oldIds + programIds).distinct().sorted().forEach(applications::lockTrainingProgram)
        val existing = applications.findAllByTraineeId(command.trainee.id)
        val retainedIds = existing.map { it.trainingProgramId }.toSet()
        command.programs.forEach { program ->
            if (applications.isDeleted(program.id)) {
                throw ProgramApplicationConflictException("삭제된 연수 프로그램입니다.")
            }
            if (program.id !in retainedIds && applications.countByTrainingProgramId(program.id) >= program.category.capacity) {
                throw ProgramApplicationConflictException("연수 프로그램 정원이 찼습니다.")
            }
        }
        applications.deleteAll(existing.filter { it.trainingProgramId !in programIds })
        applications.saveAllAndFlush(
            command.programs.filter { it.id !in retainedIds }.map { program ->
                TrainingProgramApplication(traineeId = command.trainee.id, trainingProgramId = program.id)
            },
        )
    }
}
