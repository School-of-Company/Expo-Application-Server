package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.ProgramCapacityInfo
import team.startup.application.domain.application.presentation.dto.ProgramChannelCapacityInfo
import team.startup.application.domain.application.presentation.dto.ProgramIneligibleReason
import team.startup.application.domain.application.presentation.dto.ProgramParticipantItem
import team.startup.application.domain.application.presentation.dto.ProgramParticipantsCommand
import team.startup.application.domain.application.presentation.dto.ProgramParticipantsResponse
import team.startup.application.domain.application.repository.ManagedParticipantRepository
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.ProgramWaitlistRepository
import team.startup.application.domain.application.service.GetProgramParticipantsService

@Service
class GetProgramParticipantsServiceImpl(
    private val gateway: RegistrationGateway,
    private val repository: ManagedParticipantRepository,
    private val ledger: ProgramSessionApplicationRepository,
    private val waitlist: ProgramWaitlistRepository,
    transactionManager: PlatformTransactionManager,
) : GetProgramParticipantsService {
    private val reads = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    @Transactional(readOnly = true, propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: ProgramParticipantsCommand): ProgramParticipantsResponse {
        val party = gateway.managedParty(command.owner.expoId, command.owner.sessionId)
        val definition =
            gateway
                .programDefinitions(command.owner.expoId.toString(), command.type, listOf(command.programId), existing = true)
                .singleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val policy = definition.policy
        val allowed = policy?.allowedOccupations.orEmpty()
        return requireNotNull(
            reads.execute {
                ProgramParticipantsResponse(
                    party.representativeId != null,
                    party.participants.map { participant ->
                        val reason =
                            when {
                                !repository.confirmed(command.owner.expoId, participant.participantId) -> {
                                    ProgramIneligibleReason.PREREGISTRATION_NOT_CONFIRMED
                                }

                                participant.occupation == null || participant.occupation !in allowed -> {
                                    ProgramIneligibleReason.OCCUPATION_NOT_ALLOWED
                                }

                                ledger.activeInGroup(command.owner.expoId, command.type, definition.groupId, participant.participantId) -> {
                                    ProgramIneligibleReason.GROUP_ALREADY_APPLIED
                                }

                                policy != null && ledger.hasTimeConflict(definition, participant.participantId) -> {
                                    ProgramIneligibleReason.TIME_CONFLICT
                                }

                                else -> {
                                    null
                                }
                            }
                        ProgramParticipantItem(participant, reason == null, reason)
                    },
                    policy?.let {
                        capacity(
                            it.capacity,
                            ledger.confirmedCount(command.type, command.programId),
                            it.waitingCapacity,
                            waitlist.waitingCount(definition),
                        )
                    },
                    policy?.channels.orEmpty().map { channel ->
                        ProgramChannelCapacityInfo(
                            channel.name,
                            channel.allowedOccupations,
                            capacity(
                                channel.capacity,
                                waitlist.confirmedCount(definition, channel.name).toLong(),
                                channel.waitingCapacity,
                                waitlist.waitingCount(definition, channel.name),
                            ),
                        )
                    },
                )
            },
        )
    }

    private fun capacity(
        capacity: Int,
        confirmed: Long,
        waitingCapacity: Int,
        waiting: Int,
    ) = ProgramCapacityInfo(
        capacity,
        confirmed,
        (capacity - confirmed).coerceAtLeast(0),
        waitingCapacity,
        waiting,
        (waitingCapacity - waiting).coerceAtLeast(0),
    )
}
