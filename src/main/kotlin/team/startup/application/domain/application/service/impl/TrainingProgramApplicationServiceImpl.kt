package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.entity.TrainingProgramApplication
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.ApplyTrainingProgramsCommand
import team.startup.application.domain.application.presentation.dto.TrainingApplicationResponse
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import team.startup.application.domain.application.service.TrainingProgramApplicationService
import java.time.format.DateTimeFormatter

@Service
class TrainingProgramApplicationServiceImpl(
    private val applications: TrainingProgramApplicationRepository,
) : TrainingProgramApplicationService {
    @Transactional
    override fun execute(command: ApplyTrainingProgramsCommand) {
        val programIds = command.programs.map { it.id }
        require(command.trainee.id > 0 && command.trainee.expoId.isNotBlank())
        require(programIds.isNotEmpty() && programIds.all { it > 0 } && programIds.distinct().size == programIds.size)
        require(command.programs.all { it.expoId == command.trainee.expoId })

        programIds.sorted().forEach(applications::lockTrainingProgram)
        command.programs.forEach { program ->
            if (applications.isDeleted(program.id)) {
                throw ProgramApplicationConflictException("삭제된 연수 프로그램입니다.")
            }
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

    @Transactional(readOnly = true)
    override fun findAllByProgram(programId: Long): List<TrainingApplicationResponse> =
        applications.findAllByTrainingProgramIdOrderByIdAsc(programId).map { application ->
            TrainingApplicationResponse(
                applicationId = requireNotNull(application.id),
                traineeId = application.traineeId,
                status = application.status,
                entryTime = application.entryTime?.format(TIME_FORMAT),
                leaveTime = application.leaveTime?.format(TIME_FORMAT),
            )
        }

    // 신청과 같은 프로그램 잠금 안에서 삭제 표시를 먼저 남겨, 잠금을 기다리던 신청이 삭제 뒤에 행을 남기지 못하게 한다.
    @Transactional
    override fun deleteAllByProgram(programId: Long) {
        applications.lockTrainingProgram(programId)
        applications.markDeleted(programId)
        applications.deleteAllByTrainingProgramId(programId)
    }

    companion object {
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
    }
}
