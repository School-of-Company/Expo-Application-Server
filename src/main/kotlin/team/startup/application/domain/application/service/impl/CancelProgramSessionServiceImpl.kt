package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.ApplicationActorType
import team.startup.application.domain.application.presentation.dto.CancelProgramSessionCommand
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.ProgramWaitlistRepository
import team.startup.application.domain.application.repository.StudentGuardianBatchRepository
import team.startup.application.domain.application.repository.TrainingOperationRepository
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import team.startup.application.domain.application.service.CancelProgramSessionService

@Service
class CancelProgramSessionServiceImpl(
    private val waitlist: ProgramWaitlist,
    private val repository: ProgramWaitlistRepository,
    private val ledger: ProgramSessionApplicationRepository,
    private val gateway: RegistrationGateway,
    private val trainings: TrainingProgramApplicationRepository,
    private val operations: TrainingOperationRepository,
    private val jdbc: JdbcTemplate,
    private val guardianBatches: StudentGuardianBatchRepository,
) : CancelProgramSessionService {
    @Transactional
    override fun execute(command: CancelProgramSessionCommand) {
        val original = repository.entry(command.applicationId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        ledger.lockParticipant(original.participantId)
        if (repository.entry(original.id)?.status == "CANCELLED") return
        if (guardianBatches.batchId(original.id) != null) throw ProgramApplicationConflictException("학생·보호자 신청은 소유자 또는 관리자 연계 취소가 필요합니다.")
        val definition =
            gateway.programDefinition(original.key.expoId.toString(), original.key.programType, original.key.programId)
                ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "프로그램 정의 공급자가 준비되지 않았습니다.")
        original.traineeId?.let(trainings::lockTrainee)
        waitlist.lock(definition)
        repository.observe(definition)
        val entry = requireNotNull(repository.entry(original.id))
        if (entry.status == "CANCELLED") return
        val now = waitlist.now()
        if (!now.isBefore(requireNotNull(definition.policy).cancelEndedAt)) throw ProgramApplicationConflictException("프로그램 취소 기간이 끝났습니다.")
        if (entry.status == "CONFIRMED") {
            if (definition.programType == ProgramSessionType.STANDARD) {
                jdbc.update(
                    "DELETE FROM tb_standard_program_application WHERE standard_program_id = ? AND participant_id = ?",
                    definition.id,
                    entry.participantId,
                )
            } else {
                val traineeId = requireNotNull(entry.traineeId)
                if (traineeId != original.traineeId && !trainings.tryLockTrainee(traineeId)) {
                    throw ProgramApplicationConflictException("연수 신청 변경 중입니다. 취소를 재시도해 주세요.")
                }
                operations.lockVersion(traineeId)
                jdbc.update(
                    "DELETE FROM tb_training_program_application WHERE training_program_id = ? AND trainee_id = ?",
                    definition.id,
                    traineeId,
                )
                operations.incrementVersion(traineeId)
            }
        }
        repository.transition(
            entry.id,
            "CANCELLED",
            if (command.actorId == null) ApplicationActorType.USER else ApplicationActorType.ADMIN,
            actorId = command.actorId,
        )
        waitlist.promote(definition, now)
    }
}
