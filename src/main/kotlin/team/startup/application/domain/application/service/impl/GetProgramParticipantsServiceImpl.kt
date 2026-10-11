package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.ProgramParticipantItem
import team.startup.application.domain.application.presentation.dto.ProgramParticipantsCommand
import team.startup.application.domain.application.presentation.dto.ProgramParticipantsResponse
import team.startup.application.domain.application.repository.ManagedParticipantRepository
import team.startup.application.domain.application.service.GetProgramParticipantsService

@Service
class GetProgramParticipantsServiceImpl(
    private val gateway: RegistrationGateway,
    private val repository: ManagedParticipantRepository,
    transactionManager: PlatformTransactionManager,
) : GetProgramParticipantsService {
    private val reads = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    @Transactional(readOnly = true, propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: ProgramParticipantsCommand): ProgramParticipantsResponse {
        val party = gateway.managedParty(command.owner.expoId, command.owner.sessionId)
        val definition =
            gateway.programDefinition(command.owner.expoId.toString(), command.type, command.programId)
                ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
        val allowed = definition.policy?.allowedOccupations.orEmpty()
        return ProgramParticipantsResponse(
            party.representativeId != null,
            party.participants.map { participant ->
                ProgramParticipantItem(
                    participant,
                    participant.occupation != null && participant.occupation in allowed &&
                        requireNotNull(
                            reads.execute {
                                repository.confirmed(
                                    command.owner.expoId,
                                    participant.participantId,
                                    requireNotNull(party.representativeId),
                                )
                            },
                        ),
                )
            },
        )
    }
}
