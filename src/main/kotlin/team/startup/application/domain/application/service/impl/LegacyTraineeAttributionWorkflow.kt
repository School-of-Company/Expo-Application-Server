package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.ChangeLegacyTraineeAttributionCommand
import team.startup.application.domain.application.presentation.dto.LegacyTraineeAttributionResult
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.repository.LegacyTraineeAttributionRepository
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository

@Component
class LegacyTraineeAttributionWorkflow(
    private val expo: AdminExpoGateway,
    private val user: LegacyTraineeAttributionGateway,
    private val gateway: RegistrationGateway,
    private val repository: LegacyTraineeAttributionRepository,
    private val ledger: ProgramSessionApplicationRepository,
    private val trainings: TrainingProgramApplicationRepository,
    private val transactions: TransactionTemplate,
    @Value("\${application.legacy-trainee-attribution.enabled:false}") private val enabled: Boolean,
    @Value("\${application.program-sessions.enabled:false}") private val programSessionsEnabled: Boolean,
) {
    fun change(
        command: ChangeLegacyTraineeAttributionCommand,
        revoke: Boolean,
    ): LegacyTraineeAttributionResult {
        val request = command.request
        if (request.traineeId <= 0 || request.participantId <= 0 || request.reason.isBlank() || request.reason.length > 500) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "연수자·참가자 ID와 확인 근거(1~500자)가 필요합니다.")
        }
        val access = expo.requireCancelAccess(command.expoId, command.authorization)
        if (!enabled || !programSessionsEnabled) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "레거시 귀속 변경 연동이 활성화되지 않았습니다.")
        }
        repository.lock(request.traineeId).use {
            val operation =
                try {
                    requireNotNull(
                        transactions.execute {
                            lock(command)
                            repository.prepare(command, access.adminId, revoke, access.policyVersion)
                        },
                    )
                } catch (_: DataIntegrityViolationException) {
                    throw ProgramApplicationConflictException("다른 귀속 변경 작업과 충돌합니다.")
                }
            operation.result?.let { return it }
            if (operation.state == "REJECTED") throw ResponseStatusException(HttpStatus.valueOf(requireNotNull(operation.errorStatus)))
            val ids = trainings.findAllByTraineeId(request.traineeId).map { it.trainingProgramId }.sorted()
            val definitions =
                if (revoke) {
                    emptyList()
                } else {
                    gateway.programDefinitions(
                        command.expoId.toString(),
                        ProgramSessionType.TRAINING,
                        ids,
                        existing = true,
                    )
                }
            if (!revoke && definitions.map { it.id }.sorted() != ids) {
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "레거시 신청의 프로그램 정의를 모두 확인할 수 없습니다.")
            }
            definitions.forEach(ledger::validateSessionTime)
            transactions.executeWithoutResult { repository.attempt(operation.id) }
            val result =
                try {
                    user.changeVerified(command.expoId, request, operation.actorId, revoke)
                } catch (exception: ResponseStatusException) {
                    transactions.executeWithoutResult { repository.fail(operation, exception.statusCode.value()) }
                    throw exception
                }
            transactions.executeWithoutResult {
                lock(command)
                val active = trainings.findAllByTraineeId(request.traineeId).map { it.trainingProgramId }.toSet()
                if (!revoke && !ids.containsAll(active)) throw ProgramApplicationConflictException("연수 신청이 변경되었습니다. 같은 작업 ID로 재시도해 주세요.")
                repository.complete(operation, result, definitions.filter { it.id in active })
            }
            return result
        }
    }

    private fun lock(command: ChangeLegacyTraineeAttributionCommand) {
        ledger.lockParticipant(command.request.participantId)
        if (!trainings.tryLockTrainee(command.request.traineeId)) throw ProgramApplicationConflictException("연수 신청 변경 중입니다. 재시도해 주세요.")
        ledger.lockSchedule(command.expoId.toString())
    }
}
