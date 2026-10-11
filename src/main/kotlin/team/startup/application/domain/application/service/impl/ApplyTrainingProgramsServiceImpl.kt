package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.entity.TrainingProgramApplication
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.ApplyTrainingProgramsCommand
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.presentation.dto.TrainingOperationType
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.ProgramWaitlistRepository
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import team.startup.application.domain.application.service.ApplyTrainingProgramsService

@Service
class ApplyTrainingProgramsServiceImpl(
    private val applications: TrainingProgramApplicationRepository,
    private val journal: TrainingOperationJournal,
    private val gateway: RegistrationGateway,
    private val ledger: ProgramSessionApplicationRepository,
    private val repository: ProgramWaitlistRepository,
) : ApplyTrainingProgramsService {
    @Transactional
    override fun execute(command: ApplyTrainingProgramsCommand) {
        val programIds = command.programs.map { it.id }
        require(command.trainee.id > 0 && command.trainee.expoId.isNotBlank())
        require(programIds.isNotEmpty() && programIds.all { it > 0 } && programIds.distinct().size == programIds.size)
        require(command.programs.all { it.expoId == command.trainee.expoId })
        if (journal.replay(command, TrainingOperationType.ADD)) return

        val oldIds = applications.findAllByTraineeId(command.trainee.id).map { it.trainingProgramId }.toSet()
        val definitions = gateway.programDefinitions(command.trainee.expoId, ProgramSessionType.TRAINING, programIds)
        val known =
            if (definitions.isEmpty()) {
                emptyMap()
            } else {
                ledger.knownGroups(
                    command.trainee.expoId,
                    ProgramSessionType.TRAINING,
                    command.trainee.id,
                )
            }
        val existingDefinitions =
            gateway.programDefinitions(
                command.trainee.expoId,
                ProgramSessionType.TRAINING,
                oldIds.filter { it !in known },
                existing = true,
            )
        if (definitions.any { it.policy != null }) {
            throw ProgramApplicationConflictException("정책이 설정된 프로그램은 참가자와 폼 답변을 포함하는 신청이 필요합니다.")
        }
        applications.lockTrainee(command.trainee.id)
        if (definitions.isNotEmpty() &&
            applications.findAllByTraineeId(command.trainee.id).map { it.trainingProgramId }.toSet() != oldIds
        ) {
            throw ProgramApplicationConflictException("연수 신청이 변경되었습니다. 재시도해 주세요.")
        }
        ledger.lockGroups(definitions + existingDefinitions)
        programIds.sorted().forEach(applications::lockTrainingProgram)
        definitions.forEach(repository::observe)
        ledger.ensureLegacyGroups(definitions, existingDefinitions, command.trainee.id)
        val version = journal.lockVersion(command)
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
        journal.complete(
            command,
            TrainingOperationType.ADD,
            version,
            true,
            applications.findAllByTraineeId(command.trainee.id).map { it.trainingProgramId },
        )
    }
}
