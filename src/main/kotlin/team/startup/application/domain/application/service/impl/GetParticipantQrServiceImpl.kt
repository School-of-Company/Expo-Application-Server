package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.ParticipantQrCommand
import team.startup.application.domain.application.presentation.dto.ParticipantQrResponse
import team.startup.application.domain.application.repository.ManagedParticipantRepository
import team.startup.application.domain.application.service.GetParticipantQrService

@Service
class GetParticipantQrServiceImpl(
    private val gateway: RegistrationGateway,
    private val repository: ManagedParticipantRepository,
    @Value("\${application.attendance.enabled:false}") private val attendanceEnabled: Boolean,
    transactionManager: PlatformTransactionManager,
) : GetParticipantQrService {
    private val reads = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    @Transactional(readOnly = true, propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: ParticipantQrCommand): ParticipantQrResponse {
        val party = gateway.managedParty(command.owner.expoId, command.owner.sessionId)
        val representative = party.representativeId
        if (representative == null || party.participants.none { it.participantId == command.participantId } ||
            !requireNotNull(reads.execute { repository.confirmed(command.owner.expoId, command.participantId, representative) })
        ) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        if (!attendanceEnabled) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
        val response = gateway.participantQr(command.owner.expoId, command.participantId, representative)
        if (!requireNotNull(reads.execute { repository.confirmed(command.owner.expoId, command.participantId, representative) })) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        return response
    }
}
