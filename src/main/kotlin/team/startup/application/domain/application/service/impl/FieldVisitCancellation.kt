package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.CancelOwnerApplicationCommand
import team.startup.application.domain.application.presentation.dto.FieldVisitRegistration
import team.startup.application.domain.application.presentation.dto.GetOwnerCancellationStatusCommand
import team.startup.application.domain.application.presentation.dto.OwnerApplicationParty
import team.startup.application.domain.application.presentation.dto.OwnerFieldVisitItem
import team.startup.application.domain.application.repository.FieldCancellationDeliveryRepository
import team.startup.application.domain.application.repository.FieldCancellationPolicyRepository
import team.startup.application.domain.application.repository.FieldVisitRegistrationRepository
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import java.util.UUID

@Component
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FieldVisitCancellation(
    private val visits: FieldVisitRegistrationRepository,
    private val deliveries: FieldCancellationDeliveryRepository,
    private val policy: FieldCancellationPolicyRepository,
    private val gateway: FieldCancellationGateway,
    private val user: OwnerCancellationGateway,
    private val ledger: PreregisterSessionLedger,
    private val programs: ProgramSessionApplicationRepository,
    private val trainings: TrainingProgramApplicationRepository,
    @Value("\${application.attendance.enabled:false}") private val attendanceEnabled: Boolean,
    @Value("\${application.field-visits.enabled:false}") private val enabled: Boolean,
) {
    fun execute(
        command: CancelOwnerApplicationCommand,
        party: OwnerApplicationParty,
    ) {
        val id = runCatching { UUID.fromString(command.applicationId) }.getOrElse { reject("FIELD_NOT_FOUND", HttpStatus.NOT_FOUND) }
        val generation = command.generation?.takeIf { it > 0 } ?: reject("FIELD_GENERATION_REQUIRED", HttpStatus.BAD_REQUEST)
        val original = owned(id, command.expoId, party)
        if (original.generation != generation) reject("FIELD_GENERATION_CHANGED")
        if (!attendanceEnabled || !enabled) reject("EXTERNAL_CONTRACT_REQUIRED", HttpStatus.SERVICE_UNAVAILABLE)
        deliveries.lock(command.expoId, id).use {
            deliveries.find(id)?.let(::deliver)
            val current = owned(id, command.expoId, party)
            if (current.generation != generation) reject("FIELD_GENERATION_CHANGED")
            if (current.status == "CANCELLED") return
            val verified = user.verify(GetOwnerCancellationStatusCommand(command.expoId, command.sessionId, current.participantId))
            if (party.participants.single { it.participantId == current.participantId }.representative != verified.status.representative) {
                reject("OWNERSHIP_CHANGED")
            }
            val target = current.target()
            val observation = gateway.status(target, verified.phoneNumber)
            externalReason(observation, verified.status.linkedTraineeId)?.let { reject(it) }
            val delivery =
                FieldCancellationDeliveryRepository.Delivery(
                    target,
                    UUID.randomUUID(),
                    requireNotNull(party.representativeId),
                    "ACQUIRING",
                )
            ledger.transactions.executeWithoutResult {
                lock(current, observation.linkedTraineeId)
                val locked = visits.lock(id) ?: reject("FIELD_NOT_FOUND", HttpStatus.NOT_FOUND)
                if (locked.generation != generation || locked.status != "ACTIVE" || locked.cancellationPending ||
                    locked.deliveryPending
                ) {
                    reject("APPLICATION_STATE_CHANGED")
                }
                policy.blockedReason(locked, party, observation.linkedTraineeId)?.let { reject(it) }
                if (!visits.beginCancellation(id, generation, delivery.operationId)) reject("CANCELLATION_IN_PROGRESS")
                deliveries.insert(delivery)
            }
            try {
                gateway.acquire(target, delivery.operationId, verified.phoneNumber)
                val fenced = user.verify(GetOwnerCancellationStatusCommand(command.expoId, command.sessionId, current.participantId))
                if (fenced.phoneNumber != verified.phoneNumber || fenced.status.representative != verified.status.representative ||
                    fenced.status.linkedTraineeId != verified.status.linkedTraineeId
                ) {
                    reject("OWNERSHIP_CHANGED")
                }
                ledger.transactions.executeWithoutResult {
                    lock(current, observation.linkedTraineeId)
                    val locked = visits.lock(id) ?: reject("FIELD_NOT_FOUND", HttpStatus.NOT_FOUND)
                    policy.blockedReason(locked, party, observation.linkedTraineeId)?.let { reject(it) }
                    if (!visits.cancel(
                            id,
                            generation,
                            delivery.actorParticipantId,
                            delivery.operationId,
                        )
                    ) {
                        reject("APPLICATION_STATE_CHANGED")
                    }
                    deliveries.commit(delivery.operationId)
                }
            } catch (exception: Exception) {
                try {
                    deliveries.find(id)?.let(::deliver)
                } catch (cleanup: Exception) {
                    exception.addSuppressed(cleanup)
                }
                throw exception
            }
            deliveries.find(id)?.let(::deliver)
        }
    }

    fun items(
        expoId: UUID,
        sessionId: String,
        party: OwnerApplicationParty,
        resolvedPhone: String? = null,
    ): List<OwnerFieldVisitItem> =
        visits.findByParticipants(expoId, party.participants.map { it.participantId }).map { visit ->
            val reason =
                when {
                    visit.cancellationPending -> {
                        "CANCELLATION_IN_PROGRESS"
                    }

                    visit.deliveryPending -> {
                        "REGISTRATION_IN_PROGRESS"
                    }

                    visit.status == "CANCELLED" -> {
                        "CANCELLED"
                    }

                    !attendanceEnabled || !enabled -> {
                        "EXTERNAL_CONTRACT_REQUIRED"
                    }

                    else -> {
                        try {
                            val verified =
                                user.verify(
                                    GetOwnerCancellationStatusCommand(expoId, sessionId, visit.participantId),
                                    resolvedPhone,
                                )
                            if (verified.status.representative !=
                                party.participants.single { it.participantId == visit.participantId }.representative
                            ) {
                                "OWNERSHIP_CHANGED"
                            } else {
                                val observed = gateway.status(visit.target(), verified.phoneNumber)
                                externalReason(observed, verified.status.linkedTraineeId) ?: ledger.transactions.execute {
                                    val current = visits.findById(visit.id)
                                    if (current == null || current.generation != visit.generation || current.status != "ACTIVE" ||
                                        current.cancellationPending || current.deliveryPending
                                    ) {
                                        "APPLICATION_STATE_CHANGED"
                                    } else {
                                        policy.blockedReason(current, party, verified.status.linkedTraineeId)
                                    }
                                }
                            }
                        } catch (exception: org.springframework.web.server.ResponseStatusException) {
                            if (exception.statusCode == HttpStatus.UNAUTHORIZED ||
                                exception.statusCode == HttpStatus.NOT_FOUND
                            ) {
                                throw exception
                            }
                            "EXTERNAL_CONTRACT_REQUIRED"
                        }
                    }
                }
            OwnerFieldVisitItem(
                visit.id,
                visit.participantId,
                visit.visitDate,
                visit.generation,
                visit.status,
                visit.registeredAt,
                visit.cancelledAt,
                visit.cancelledBy,
                reason == null,
                reason.orEmpty(),
            )
        }

    fun recoverPending() {
        deliveries.pending().forEach { pending ->
            try {
                deliveries.lock(pending.target.expoId, pending.target.registrationId).use {
                    deliveries.find(pending.target.registrationId)?.let(::deliver)
                }
            } catch (_: Exception) {
                deliveries.defer(pending.operationId)
            }
        }
    }

    private fun deliver(delivery: FieldCancellationDeliveryRepository.Delivery) {
        try {
            deliveries.abort(delivery.operationId)
            val decision = deliveries.find(delivery.target.registrationId) ?: return
            if (decision.action == "COMMIT") {
                gateway.commit(decision.target, decision.operationId)
                gateway.projectCancellation(decision.target, decision.operationId)
            } else {
                gateway.abort(decision.target, decision.operationId)
            }
            ledger.transactions.executeWithoutResult {
                ledger.lock(decision.target.expoId)
                check(visits.clearCancellation(decision.target.registrationId, decision.target.generation, decision.operationId))
                deliveries.delete(decision.operationId)
            }
        } catch (exception: Exception) {
            deliveries.defer(delivery.operationId)
            throw exception
        }
    }

    private fun owned(
        id: UUID,
        expoId: UUID,
        party: OwnerApplicationParty,
    ): FieldVisitRegistration =
        visits.findById(id)?.takeIf {
            it.expoId == expoId && party.representativeId != null &&
                party.participants.any { p -> p.participantId == it.participantId }
        }
            ?: reject("FIELD_NOT_FOUND", HttpStatus.NOT_FOUND)

    private fun lock(
        visit: FieldVisitRegistration,
        traineeId: Long?,
    ) {
        programs.limitLockWait()
        ledger.lock(visit.expoId)
        programs.lockParticipant(visit.participantId)
        traineeId?.let(trainings::lockTrainee)
    }

    private fun externalReason(
        status: FieldCancellationGateway.Status,
        traineeId: Long?,
    ): String? =
        when {
            status.linkedTraineeId != traineeId -> "PARTICIPANT_STATE_CHANGED"
            status.enteredOnVisitDate -> "ENTERED"
            status.programAttended -> "ATTENDED"
            status.guardState != "NONE" -> "CANCELLATION_IN_PROGRESS"
            else -> null
        }

    private fun FieldVisitRegistration.target() = FieldCancellationTarget(id, expoId, participantId, visitDate, generation)

    private fun reject(
        code: String,
        status: HttpStatus = HttpStatus.CONFLICT,
    ): Nothing = throw OwnerApplicationRejectedException(status, code, "현장등록 취소 조건을 확인해 주세요.")
}
