package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.entity.TrainingProgramApplication
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.ApplyTrainingProgramsCommand
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.presentation.dto.TrainingOperationType
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import team.startup.application.domain.application.service.ReplaceTrainingProgramsService

@Service
class ReplaceTrainingProgramsServiceImpl(
    private val applications: TrainingProgramApplicationRepository,
    private val journal: TrainingOperationJournal,
    private val gateway: RegistrationGateway,
    private val ledger: ProgramSessionApplicationRepository,
) : ReplaceTrainingProgramsService {
    @Transactional
    override fun execute(command: ApplyTrainingProgramsCommand) {
        val programIds = command.programs.map { it.id }
        require(command.trainee.id > 0 && command.trainee.expoId.isNotBlank())
        require(programIds.all { it > 0 } && programIds.distinct().size == programIds.size)
        require(command.programs.all { it.expoId == command.trainee.expoId })
        if (journal.replay(command, TrainingOperationType.REPLACE)) return

        val previousIds = applications.findAllByTraineeId(command.trainee.id).map { it.trainingProgramId }.toSet()
        val definitions = gateway.programDefinitions(command.trainee.expoId, ProgramSessionType.TRAINING, programIds)
        if (definitions.any { it.policy != null && it.id !in previousIds }) {
            throw ProgramApplicationConflictException("정책이 설정된 프로그램은 참가자와 폼 답변을 포함하는 신청이 필요합니다.")
        }
        applications.lockTrainee(command.trainee.id)
        val oldIds = applications.findAllByTraineeId(command.trainee.id).map { it.trainingProgramId }
        if (definitions.isNotEmpty() && oldIds.toSet() != previousIds) {
            throw ProgramApplicationConflictException("연수 신청이 변경되었습니다. 재시도해 주세요.")
        }
        ledger.lockGroups(definitions)
        (oldIds + programIds).distinct().sorted().forEach(applications::lockTrainingProgram)
        ledger.ensureLegacyGroups(definitions, emptyList(), command.trainee.id, replacing = true)
        val version = journal.lockVersion(command)
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
        ledger.removeTrainingPrograms(command.trainee.id, programIds.toSet())
        applications.saveAllAndFlush(
            command.programs.filter { it.id !in retainedIds }.map { program ->
                TrainingProgramApplication(traineeId = command.trainee.id, trainingProgramId = program.id)
            },
        )
        journal.complete(command, TrainingOperationType.REPLACE, version, retainedIds != programIds.toSet(), programIds)
    }
}
