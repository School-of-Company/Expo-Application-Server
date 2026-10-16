package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.ApplicationActorType
import team.startup.application.domain.application.presentation.dto.CancelOwnerApplicationCommand
import team.startup.application.domain.application.presentation.dto.CancelStudentGuardianApplicationCommand
import team.startup.application.domain.application.presentation.dto.OwnerApplicationType
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.ProgramWaitlistRepository
import team.startup.application.domain.application.repository.StudentGuardianBatchRepository
import team.startup.application.domain.application.repository.TrainingOperationRepository
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import team.startup.application.domain.application.service.CancelStudentGuardianApplicationService
import team.startup.application.domain.application.service.OwnerCancellationGuard
import java.time.Clock

@Service
class CancelStudentGuardianApplicationServiceImpl(
    private val batches: StudentGuardianBatchRepository,
    private val ledger: ProgramSessionApplicationRepository,
    private val applications: ProgramWaitlistRepository,
    private val gateway: RegistrationGateway,
    private val expo: AdminExpoGateway,
    private val guards: ObjectProvider<OwnerCancellationGuard>,
    private val trainings: TrainingProgramApplicationRepository,
    private val operations: TrainingOperationRepository,
    private val waitlist: ProgramWaitlist,
    private val transactions: TransactionTemplate,
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) : CancelStudentGuardianApplicationService {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: CancelStudentGuardianApplicationCommand) {
        if ((command.sessionId == null) == (command.authorization == null)) throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        val party = command.sessionId?.let { gateway.managedParty(command.expoId, it) }
        val adminId = command.authorization?.let { expo.requireCancelAccess(command.expoId, it).adminId }
        val original = applications.entry(command.applicationId)?.takeIf { it.key.expoId == command.expoId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (batches.batchId(original.id) == null) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (party != null && party.participants.none { it.participantId == original.participantId }) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val guard = guards.ifAvailable ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
        if (original.status == "CANCELLED") {
            (listOf(original) + listOfNotNull(batches.guardian(original.id)?.let(applications::entry))).forEach { entry ->
                guard.recover(
                    CancelOwnerApplicationCommand(command.expoId, command.sessionId.orEmpty(), OwnerApplicationType.valueOf(entry.key.programType.name), entry.id.toString()),
                )
            }
            return
        }
        val planned = targets(original)
        val plannedComponent = component(original)
        val permits = mutableListOf<Pair<ProgramWaitlistRepository.Entry, OwnerCancellationGuard.Permit>>()
        var failure: Exception? = null
        try {
            for (entry in planned.sortedBy { it.participantId }) {
                val ownerCommand = CancelOwnerApplicationCommand(command.expoId, command.sessionId.orEmpty(), OwnerApplicationType.valueOf(entry.key.programType.name), entry.id.toString())
                guard.recover(ownerCommand)
                val permit = if (party != null) {
                    guard.acquire(ownerCommand, entry.participantId, party)
                } else {
                    guard.acquireAdminProgram(ownerCommand, entry.participantId, entry.key.programId)
                }
                permits.add(entry to permit)
            }
            transactions.executeWithoutResult {
                if (!plannedComponent.map { it.participantId }.distinct().sorted().all(ledger::tryLockParticipant)) conflict("신청 변경 중입니다. 취소를 재시도해 주세요.")
                val definition = gateway.programDefinition(original.key.expoId.toString(), original.key.programType, original.key.programId)
                    ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
                waitlist.lock(definition)
                applications.observe(definition)
                val current = requireNotNull(applications.entry(original.id))
                if (current.status == "CANCELLED") return@executeWithoutResult
                if (component(current).map { it.id }.toSet() != plannedComponent.map { it.id }.toSet() || targets(current).map { it.id }.toSet() != planned.map { it.id }.toSet()) {
                    conflict("연결 학생이 변경되었습니다. 취소 대상을 다시 확인해 주세요.")
                }
                val currentTargets = planned.map { requireNotNull(applications.entry(it.id)) }
                val traineeIds = currentTargets.filter { it.status == "CONFIRMED" }.mapNotNull { it.traineeId }.distinct().sorted()
                if (!traineeIds.all(trainings::tryLockTrainee)) conflict("연수 신청 변경 중입니다.")
                for ((entry, permit) in permits) {
                    val target = currentTargets.single { it.id == entry.id }
                    if (!clock.instant().isBefore(permit.cancelEndedAt) || !clock.instant().isBefore(requireNotNull(definition.policy).cancelEndedAt)) conflict("신청 취소 기간이 끝났습니다.")
                    if (target.status == "CONFIRMED" && target.key.programType == ProgramSessionType.TRAINING && target.traineeId != permit.linkedTraineeId) conflict("연수자 연결이 변경되었습니다.")
                }
                currentTargets.forEach { entry ->
                    if (entry.status == "CONFIRMED") {
                        if (entry.key.programType == ProgramSessionType.STANDARD) {
                            jdbc.update("DELETE FROM tb_standard_program_application WHERE standard_program_id = ? AND participant_id = ?", entry.key.programId, entry.participantId)
                        } else {
                            val trainee = requireNotNull(entry.traineeId)
                            operations.lockVersion(trainee)
                            jdbc.update("DELETE FROM tb_training_program_application WHERE training_program_id = ? AND trainee_id = ?", entry.key.programId, trainee)
                            operations.incrementVersion(trainee)
                        }
                    }
                    applications.transition(
                        entry.id,
                        "CANCELLED",
                        if (adminId == null) ApplicationActorType.USER else ApplicationActorType.ADMIN,
                        actorId = adminId,
                    )
                }
                permits.forEach { it.second.committed() }
                waitlist.promote(definition)
            }
        } catch (exception: Exception) {
            failure = exception
            throw exception
        } finally {
            var cleanupFailure: Exception? = null
            permits.asReversed().forEach { (_, permit) ->
                try {
                    permit.close()
                } catch (cleanup: Exception) {
                    val previous = cleanupFailure
                    if (previous == null) cleanupFailure = cleanup else previous.addSuppressed(cleanup)
                }
            }
            cleanupFailure?.let { cleanup -> failure?.addSuppressed(cleanup) ?: throw cleanup }
        }
    }

    private fun targets(entry: ProgramWaitlistRepository.Entry): List<ProgramWaitlistRepository.Entry> {
        if (batches.students(entry.id).isNotEmpty()) conflict("연결 학생에게 필요한 보호자를 먼저 취소할 수 없습니다.")
        val guardian = batches.guardian(entry.id)?.let(applications::entry) ?: return listOf(entry)
        return if (batches.students(guardian.id).all { it.id == entry.id }) listOf(entry, guardian) else listOf(entry)
    }

    private fun component(entry: ProgramWaitlistRepository.Entry): List<ProgramWaitlistRepository.Entry> {
        val guardianId = batches.guardian(entry.id) ?: entry.id
        return listOfNotNull(applications.entry(guardianId)) + batches.students(guardianId)
    }

    private fun conflict(reason: String): Nothing = throw ProgramApplicationConflictException(reason)
}
