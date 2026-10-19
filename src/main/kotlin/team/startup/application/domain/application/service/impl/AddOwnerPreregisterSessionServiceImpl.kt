package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AddOwnerPreregisterSessionCommand
import team.startup.application.domain.application.presentation.dto.OwnerPreregisterSessionResult
import team.startup.application.domain.application.service.AddOwnerPreregisterSessionService

@Service
class AddOwnerPreregisterSessionServiceImpl(
    private val gateway: RegistrationGateway,
    private val workflow: PreregisterRegistrationWorkflow,
) : AddOwnerPreregisterSessionService {
    // 예약과 확정은 예약 복구 경로가 각자 트랜잭션으로 나눠 처리한다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: AddOwnerPreregisterSessionCommand): OwnerPreregisterSessionResult {
        if (command.idempotencyKey != null && (command.idempotencyKey.isBlank() || command.idempotencyKey.length > 100)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key는 1~100자여야 합니다.")
        }
        if (command.participantIds.distinct().size != command.participantIds.size) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "같은 참석자를 두 번 골랐습니다.")
        }
        val party = gateway.managedParty(command.expoId, command.sessionId)
        val receipt =
            workflow.addSession(command.expoId, party, command.preregisterSessionId, command.participantIds, command.idempotencyKey)
        return OwnerPreregisterSessionResult(
            command.preregisterSessionId,
            if (receipt.status == "CONFIRMED") command.participantIds else emptyList(),
            receipt.status,
            receipt.position,
        )
    }
}
