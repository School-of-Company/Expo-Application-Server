package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.CancelOwnerApplicationCommand
import team.startup.application.domain.application.presentation.dto.GetOwnerApplicationsCommand
import team.startup.application.domain.application.presentation.dto.GetOwnerCancellationStatusCommand
import team.startup.application.domain.application.presentation.dto.OwnerApplicationItem
import team.startup.application.domain.application.presentation.dto.OwnerApplicationParty
import team.startup.application.domain.application.presentation.dto.OwnerApplicationType
import team.startup.application.domain.application.presentation.dto.OwnerApplicationsResponse
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.repository.OwnerApplicationRepository
import team.startup.application.domain.application.repository.OwnerCancellationDeliveryRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.service.GetOwnerApplicationsService
import java.time.Clock

@Service
class GetOwnerApplicationsServiceImpl(
    private val gateway: RegistrationGateway,
    private val repository: OwnerApplicationRepository,
    private val user: OwnerCancellationGateway,
    private val attendance: AttendanceCancellationStatusGateway,
    private val deadlines: OwnerCancellationDeadlineGateway,
    private val deliveries: OwnerCancellationDeliveryRepository,
    private val preregister: PreregisterSessionRepository,
    private val clock: Clock,
    private val companions: PreregisterCompanionPolicy,
    transactionManager: PlatformTransactionManager,
    @Value("\${application.attendance.enabled:false}") private val attendanceEnabled: Boolean,
) : GetOwnerApplicationsService {
    private val reads = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    @Transactional(readOnly = true, propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: GetOwnerApplicationsCommand): OwnerApplicationsResponse {
        val party = gateway.managedParty(command.expoId, command.sessionId)
        val stored = requireNotNull(reads.execute { repository.applications(command.expoId, party) })
        val definitions =
            stored
                .filter { it.type != OwnerApplicationType.PREREGISTER }
                .groupBy { ProgramSessionType.valueOf(it.type.name) }
                .flatMap { (type, items) ->
                    gateway.programDefinitions(command.expoId.toString(), type, items.map { it.sessionOrProgramId }, existing = true)
                }.associateBy { it.programType.name to it.id }
        val applications = stored.map { it.copy(place = definitions[it.type.name to it.sessionOrProgramId]?.place) }
        if (!attendanceEnabled) return OwnerApplicationsResponse(party.participants, withCompanions(command, party, applications))
        val observations =
            applications.filter { it.applied }.map { it.participantId }.distinct().associateWith { participantId ->
                runCatching {
                    val verified = user.verify(GetOwnerCancellationStatusCommand(command.expoId, command.sessionId, participantId))
                    verified to attendance.find(command.expoId, participantId, verified.phoneNumber)
                }
            }
        val eligible =
            applications.map { application ->
                if (!application.applied) return@map application
                val reason =
                    try {
                        val (verified, observed) = requireNotNull(observations[application.participantId]).getOrThrow()
                        when {
                            party.participants.single { it.participantId == application.participantId }.representative !=
                                verified.status.representative -> {
                                "OWNERSHIP_CHANGED"
                            }

                            verified.status.representative != observed.representative ||
                                verified.status.traineeLinked != observed.traineeLinked ||
                                verified.status.linkedTraineeId != observed.linkedTraineeId -> {
                                "PARTICIPANT_STATE_CHANGED"
                            }

                            verified.status.entered || observed.entered -> {
                                "ENTERED"
                            }

                            observed.programAttended -> {
                                "ATTENDED"
                            }

                            observed.guardState != "NONE" -> {
                                "CANCELLATION_IN_PROGRESS"
                            }

                            else -> {
                                blockedReason(command, party, application, verified.status.linkedTraineeId)
                            }
                        }
                    } catch (exception: ResponseStatusException) {
                        when (exception.statusCode) {
                            HttpStatus.UNAUTHORIZED, HttpStatus.NOT_FOUND -> {
                                throw exception
                            }

                            HttpStatus.CONFLICT -> {
                                if (application.type ==
                                    OwnerApplicationType.PREREGISTER
                                ) {
                                    "DEADLINE_UNSET"
                                } else {
                                    "PROGRAM_CHANGE_PENDING"
                                }
                            }

                            else -> {
                                "EXTERNAL_CONTRACT_REQUIRED"
                            }
                        }
                    }
                application.copy(cancellable = reason == null, cancellationBlockedReason = reason ?: "")
            }
        return OwnerApplicationsResponse(party.participants, withCompanions(command, party, eligible))
    }

    // #169: 대표자 본인의 확정 사전등록에만 동행자 추가 가능 여부를 붙인다. 공급자 조회 실패는 목록을 막지 않는다.
    private fun withCompanions(
        command: GetOwnerApplicationsCommand,
        party: OwnerApplicationParty,
        applications: List<OwnerApplicationItem>,
    ): List<OwnerApplicationItem> =
        applications.map { application ->
            if (application.type != OwnerApplicationType.PREREGISTER || application.status != "CONFIRMED" ||
                application.participantId != party.representativeId
            ) {
                return@map application
            }
            val availability =
                try {
                    val id = application.applicationId.toLong()
                    val observation = companions.observe(command.expoId, application.sessionOrProgramId)
                    requireNotNull(reads.execute { companions.evaluate(command.expoId, party, id, observation, persist = false) })
                } catch (_: ResponseStatusException) {
                    CompanionAvailability(0, "EXTERNAL_CONTRACT_REQUIRED")
                }
            application.copy(
                companionAddable = availability.addable,
                remainingCompanionSlots = availability.remaining,
                companionAddBlockedReason = availability.blockedReason,
            )
        }

    private fun blockedReason(
        command: GetOwnerApplicationsCommand,
        party: OwnerApplicationParty,
        application: OwnerApplicationItem,
        traineeId: Long?,
    ): String? {
        val deadline =
            if (application.type == OwnerApplicationType.PREREGISTER) {
                deadlines.preregister(command.expoId, application.sessionOrProgramId)
            } else {
                deadlines.program(command.expoId, application.type, application.sessionOrProgramId)
            }
        if (!clock.instant().isBefore(deadline)) return "DEADLINE_EXPIRED"
        return reads.execute {
            val cancellation = CancelOwnerApplicationCommand(command.expoId, command.sessionId, application.type, application.applicationId)
            val current = repository.cancellationTarget(cancellation, party) ?: return@execute "APPLICATION_STATE_CHANGED"
            when {
                current.status != application.status -> {
                    "APPLICATION_STATE_CHANGED"
                }

                application.type == OwnerApplicationType.TRAINING && application.status == "CONFIRMED" &&
                    (current.traineeId == null || current.traineeId != traineeId) -> {
                    "PARTICIPANT_STATE_CHANGED"
                }

                deliveries.find(cancellation) != null -> {
                    "CANCELLATION_IN_PROGRESS"
                }

                application.type != OwnerApplicationType.PREREGISTER -> {
                    null
                }

                preregister.deleted(command.expoId, application.sessionOrProgramId) ||
                    preregister.pending(command.expoId, application.sessionOrProgramId) != null -> {
                    "SESSION_CHANGE_PENDING"
                }

                repository.activePrograms(command.expoId, application.participantId, traineeId) -> {
                    "ACTIVE_PROGRAMS"
                }

                application.participantId == party.representativeId &&
                    repository.activeCompanions(command.expoId, application.participantId) -> {
                    "ACTIVE_COMPANIONS"
                }

                else -> {
                    null
                }
            }
        }
    }
}
