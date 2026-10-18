package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AddOwnerPreregisterCompanionsCommand
import team.startup.application.domain.application.presentation.dto.OwnerPreregisterCompanionsResult
import team.startup.application.domain.application.service.AddOwnerPreregisterCompanionsService

@Service
class AddOwnerPreregisterCompanionsServiceImpl(
    private val gateway: RegistrationGateway,
    private val workflow: PreregisterRegistrationWorkflow,
) : AddOwnerPreregisterCompanionsService {
    // User 저장과 확정은 예약 복구 경로가 각자 트랜잭션으로 나눠 처리한다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: AddOwnerPreregisterCompanionsCommand): OwnerPreregisterCompanionsResult {
        val applicationId = command.applicationId.toLongOrNull()?.takeIf { it > 0 } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (command.idempotencyKey != null && (command.idempotencyKey.isBlank() || command.idempotencyKey.length > 100)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key는 1~100자여야 합니다.")
        }
        command.companions.forEach(CompanionAnswers::validate)
        if (command.companions
                .map(CompanionAnswers::key)
                .distinct()
                .size != command.companions.size
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "같은 동행자를 두 번 입력했습니다.")
        }
        val party = gateway.managedParty(command.expoId, command.sessionId)
        val added = workflow.addCompanions(command.expoId, applicationId, party, command.companions, command.idempotencyKey)
        return OwnerPreregisterCompanionsResult(applicationId.toString(), added)
    }
}
