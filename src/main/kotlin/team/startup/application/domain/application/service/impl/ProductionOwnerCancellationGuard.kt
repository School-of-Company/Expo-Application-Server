package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AdminCancellationParticipantStatus
import team.startup.application.domain.application.presentation.dto.CancelOwnerApplicationCommand
import team.startup.application.domain.application.presentation.dto.GetOwnerCancellationStatusCommand
import team.startup.application.domain.application.presentation.dto.OwnerApplicationParty
import team.startup.application.domain.application.presentation.dto.OwnerApplicationType
import team.startup.application.domain.application.repository.OwnerApplicationRepository
import team.startup.application.domain.application.repository.OwnerCancellationDeliveryRepository
import team.startup.application.domain.application.service.OwnerCancellationGuard
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Component
@Transactional(propagation = Propagation.NEVER)
class ProductionOwnerCancellationGuard(
    private val user: OwnerCancellationGateway,
    private val deadlines: OwnerCancellationDeadlineGateway,
    private val attendance: AttendanceCancellationAdapter,
    private val owners: OwnerApplicationRepository,
    private val deliveries: OwnerCancellationDeliveryRepository,
    private val clock: Clock,
    private val statuses: AttendanceCancellationStatusGateway,
    private val participants: AdminCancellationParticipantGateway,
    @Value("\${application.attendance.enabled:false}") private val attendanceEnabled: Boolean = false,
) : OwnerCancellationGuard {
    override fun recover(command: CancelOwnerApplicationCommand) {
        deliveries.lock(command).use {
            deliveries.find(command)?.let(::deliver)
        }
    }

    override fun acquire(
        command: CancelOwnerApplicationCommand,
        participantId: Long,
        party: OwnerApplicationParty,
    ): OwnerCancellationGuard.Permit {
        lateinit var verified: OwnerCancellationGateway.VerifiedStatus
        return guarded(
            command,
            participantId,
            prepare = {
                val target = owners.cancellationTarget(command, party) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
                if (target.participantId != participantId) throw ResponseStatusException(HttpStatus.NOT_FOUND)
                verified = user.verify(GetOwnerCancellationStatusCommand(command.expoId, command.sessionId, participantId))
                val participant =
                    party.participants.singleOrNull { it.participantId == participantId }
                        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
                if (participant.representative != verified.status.representative) {
                    throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "참가자 소유권 응답이 변경되었습니다.")
                }
                val status = statuses.find(command.expoId, participantId, verified.phoneNumber)
                if (status.representative != verified.status.representative ||
                    status.entered != verified.status.entered ||
                    status.traineeLinked != verified.status.traineeLinked ||
                    status.linkedTraineeId != verified.status.linkedTraineeId
                ) {
                    throw ResponseStatusException(HttpStatus.CONFLICT, "참가자 상태가 변경되었습니다.")
                }
                if (status.entered || status.programAttended || status.guardState != "NONE") {
                    throw ResponseStatusException(HttpStatus.CONFLICT, "입장·출석했거나 다른 취소 작업이 진행 중입니다.")
                }
                if (command.type == OwnerApplicationType.TRAINING && target.status == "CONFIRMED" &&
                    (target.traineeId == null || target.traineeId != verified.status.linkedTraineeId)
                ) {
                    throw ResponseStatusException(HttpStatus.CONFLICT, "연결된 연수자 정보를 확인할 수 없습니다.")
                }
                val deadline =
                    if (command.type == OwnerApplicationType.PREREGISTER) {
                        deadlines.preregister(command.expoId, target.sessionOrProgramId)
                    } else {
                        deadlines.program(command.expoId, command.type, target.sessionOrProgramId)
                    }
                verified.status.linkedTraineeId to deadline
            },
            acquire = { id ->
                attendance.acquire(command.expoId, participantId, id, verified.phoneNumber)
                val fenced = user.verify(GetOwnerCancellationStatusCommand(command.expoId, command.sessionId, participantId))
                if (fenced.phoneNumber != verified.phoneNumber || fenced.status != verified.status) {
                    throw ResponseStatusException(HttpStatus.CONFLICT, "취소 보호 중 참가자 상태가 변경되었습니다.")
                }
            },
        )
    }

    override fun acquireAdmin(
        command: CancelOwnerApplicationCommand,
        participantId: Long,
        sessionId: Long,
    ): OwnerCancellationGuard.Permit {
        require(command.type == OwnerApplicationType.PREREGISTER)
        lateinit var before: AdminCancellationParticipantStatus
        return guarded(
            command,
            participantId,
            prepare = {
                before = participants.status(command.expoId, participantId)
                if (before.entered) throw ResponseStatusException(HttpStatus.CONFLICT, "이미 입장한 참가자입니다.")
                before.linkedTraineeId to deadlines.preregister(command.expoId, sessionId)
            },
            acquire = { id ->
                attendance.acquireAdmin(command.expoId, participantId, id)
                if (participants.status(command.expoId, participantId) != before) {
                    throw ResponseStatusException(HttpStatus.CONFLICT, "취소 보호 중 참가자 상태가 변경되었습니다.")
                }
            },
        )
    }

    // prepare checks the participant before the remote guard and returns (linkedTraineeId, cancelEndedAt).
    private fun guarded(
        command: CancelOwnerApplicationCommand,
        participantId: Long,
        prepare: () -> Pair<Long?, Instant>,
        acquire: (UUID) -> Unit,
    ): OwnerCancellationGuard.Permit {
        if (!attendanceEnabled) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "출석 취소 전달이 활성화되지 않았습니다.")
        }
        val lock = deliveries.lock(command)
        var operationId: UUID? = null
        try {
            deliveries.find(command)?.let(::deliver)
            val (linkedTrainee, deadline) = prepare()
            if (!clock.instant().isBefore(deadline)) throw ResponseStatusException(HttpStatus.CONFLICT, "신청 취소 기간이 끝났습니다.")
            val id = UUID.randomUUID()
            deliveries.insert(command, participantId, id)
            operationId = id
            acquire(id)
            return object : OwnerCancellationGuard.Permit {
                override val linkedTraineeId = linkedTrainee
                override val cancelEndedAt = deadline

                override fun committed(cancellationRevision: Long?) {
                    check(TransactionSynchronizationManager.isActualTransactionActive())
                    if (command.type == OwnerApplicationType.PREREGISTER) {
                        deliveries.commit(id, requireNotNull(cancellationRevision))
                    } else {
                        deliveries.abort(id)
                    }
                }

                override fun close() {
                    lock.use {
                        deliveries.operation(id)?.let(::deliver)
                    }
                }
            }
        } catch (exception: Exception) {
            try {
                operationId?.let { deliveries.operation(it)?.let(::deliver) }
            } catch (cleanup: Exception) {
                exception.addSuppressed(cleanup)
            } finally {
                lock.close()
            }
            throw exception
        }
    }

    fun recoverPending() {
        deliveries.pending().forEach { delivery ->
            try {
                deliveries.lock(delivery.command).use {
                    deliveries.operation(delivery.operationId)?.let(::deliver)
                }
            } catch (_: ResponseStatusException) {
                deliveries.defer(delivery.operationId)
            }
        }
    }

    private fun deliver(delivery: OwnerCancellationDeliveryRepository.Delivery) {
        try {
            deliveries.abort(delivery.operationId)
            val decision = deliveries.operation(delivery.operationId) ?: return
            if (decision.action == "COMMIT") {
                try {
                    attendance.commit(
                        decision.command.expoId,
                        decision.participantId,
                        decision.operationId,
                        requireNotNull(decision.cancellationRevision),
                    )
                } catch (exception: ResponseStatusException) {
                    if (exception.statusCode != HttpStatus.CONFLICT) throw exception
                    // A superseded operation returns 409; ABORT must acknowledge its tombstone before cleanup.
                    attendance.abort(decision.command.expoId, decision.participantId, decision.operationId)
                }
                deliveries.delete(decision.operationId)
            } else {
                attendance.abort(decision.command.expoId, decision.participantId, decision.operationId)
                deliveries.delete(decision.operationId)
            }
        } catch (exception: Exception) {
            deliveries.defer(delivery.operationId)
            throw exception
        }
    }
}
