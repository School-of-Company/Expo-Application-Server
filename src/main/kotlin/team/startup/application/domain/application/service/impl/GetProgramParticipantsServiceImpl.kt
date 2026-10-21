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
        if (policy != null && !policy.admissionCapacitiesValid()) throw ResponseStatusException(HttpStatus.BAD_GATEWAY)
        val allowed = policy?.allowedOccupations.orEmpty()
        return requireNotNull(
            reads.execute {
                ledger.lockCapacityReads(listOf(definition))
                waitlist.checkDefinition(definition)
                ProgramParticipantsResponse(
                    party.representativeId != null,
                    party.participants.map { participant ->
                        // 신청 경로와 같이 현장 신청은 같은 묶음의 본인 사전 대기를 취소할 행으로 보고 판정에서 뺀다(#261).
                        val replaced =
                            if (command.onsite) {
                                ledger.onsiteReplaceableWaiting(
                                    command.owner.expoId,
                                    command.type,
                                    definition.groupId,
                                    participant.participantId,
                                )
                            } else {
                                emptyList()
                            }
                        val preregister =
                            when {
                                command.onsite && participant.fieldRegistered -> {
                                    null
                                }

                                // 신청 경로와 같은 회차 시간 겹침 기준(#197, 현장은 #261로 확정만). 정책 없는 프로그램은 신청할 수 없어 확정 여부만 본다.
                                policy != null -> {
                                    ledger.preregisterIneligibility(
                                        definition,
                                        participant.participantId,
                                        lock = false,
                                        requireOverlap = !command.onsite,
                                    )
                                }

                                !repository.confirmed(command.owner.expoId, participant.participantId) -> {
                                    ProgramIneligibleReason.PREREGISTRATION_NOT_CONFIRMED
                                }

                                else -> {
                                    null
                                }
                            }
                        val reason =
                            when {
                                preregister != null -> {
                                    preregister
                                }

                                participant.occupation == null || participant.occupation !in allowed -> {
                                    ProgramIneligibleReason.OCCUPATION_NOT_ALLOWED
                                }

                                ledger.activeInGroup(
                                    command.owner.expoId,
                                    command.type,
                                    definition.groupId,
                                    participant.participantId,
                                    replaced,
                                ) -> {
                                    ProgramIneligibleReason.GROUP_ALREADY_APPLIED
                                }

                                policy != null &&
                                    ledger.hasTimeConflict(definition, participant.participantId, excludingApplications = replaced) -> {
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
                    ledger.admissionSeats(definition),
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
