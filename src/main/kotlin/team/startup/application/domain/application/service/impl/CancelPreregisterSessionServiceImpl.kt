package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.CancelOwnerApplicationCommand
import team.startup.application.domain.application.presentation.dto.CancelPreregisterSessionCommand
import team.startup.application.domain.application.presentation.dto.OwnerApplicationType
import team.startup.application.domain.application.presentation.dto.PreregisterApplicationReceipt
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.service.CancelPreregisterSessionService
import team.startup.application.domain.application.service.OwnerCancellationGuard

@Service
class CancelPreregisterSessionServiceImpl(
    private val repository: PreregisterSessionRepository,
    private val ledger: PreregisterSessionLedger,
    private val cancellation: PreregisterCancellation,
    private val guards: ObjectProvider<OwnerCancellationGuard>,
    private val waitlist: PreregisterWaitlist,
) : CancelPreregisterSessionService {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: CancelPreregisterSessionCommand): List<PreregisterApplicationReceipt> {
        // ponytail: each guard holds a pooled connection until commit, so one request is capped at a whole party.
        if (command.sessionId <= 0 || command.applicationIds.isEmpty() || command.applicationIds.size > MAX_PARTY ||
            command.applicationIds.any { it <= 0 } ||
            command.applicationIds.distinct().size != command.applicationIds.size ||
            (command.representativeId != null && command.representativeId <= 0)
        ) {
            badSessionRequest("취소할 신청 목록이 올바르지 않습니다.")
        }
        ledger.transactions.executeWithoutResult {
            ledger.lock(command.expoId)
            ledger.ensureAvailable(command.expoId, command.sessionId)
        }
        val observation = ledger.observe(command.expoId, command.sessionId)
        val targets = owned(command).filter { it.first.status != "CANCELLED" }
        if (targets.isEmpty()) return emptyList()
        val guard =
            guards.ifAvailable ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "입장·출석·취소 기한 보호 계약이 준비되지 않았습니다.")
        val permits = mutableListOf<OwnerCancellationGuard.Permit>()
        var failure: Throwable? = null
        val cancelled =
            try {
                // Representatives go last so the companion rule sees companions cancelled earlier in the same transaction.
                val ordered = targets.sortedWith(compareBy({ it.first.participantId == it.second }, { it.first.participantId }))
                ordered.forEach { (receipt, _) ->
                    val delivery =
                        CancelOwnerApplicationCommand(command.expoId, "", OwnerApplicationType.PREREGISTER, receipt.id.toString())
                    guard.recover(delivery)
                    permits += guard.acquireAdmin(delivery, receipt.participantId, command.sessionId)
                }
                requireNotNull(
                    ledger.transactions.execute {
                        ledger.lock(command.expoId)
                        ledger.current(command.expoId, command.sessionId, observation)
                        ordered.zip(permits).sortedBy { it.first.first.participantId }.forEach { (target, permit) ->
                            cancellation.lockParticipant(target.first.participantId, permit)
                        }
                        if (owned(command).filter { it.first.status != "CANCELLED" } != targets) {
                            conflict("취소 대상 신청이 변경되었습니다.")
                        }
                        ordered.zip(permits).map { (target, permit) ->
                            val (receipt, representativeId) = target
                            cancellation.cancel(
                                command.expoId,
                                command.sessionId,
                                receipt.id,
                                receipt.participantId,
                                representativeId,
                                permit,
                                actorParticipantId = null,
                                adminId = command.adminId,
                            )
                            receipt.copy(status = "CANCELLED")
                        }
                    },
                )
            } catch (exception: Throwable) {
                failure = exception
                throw exception
            } finally {
                var closeFailure: Exception? = null
                permits.asReversed().forEach { permit ->
                    try {
                        permit.close()
                    } catch (cleanup: Exception) {
                        (failure ?: closeFailure)?.addSuppressed(cleanup) ?: run { closeFailure = cleanup }
                    }
                }
                closeFailure?.let { throw it }
            }
        waitlist.promoteQuietly(command.expoId, command.sessionId)
        return cancelled.sortedBy { it.id }
    }

    private fun owned(command: CancelPreregisterSessionCommand): List<Pair<PreregisterApplicationReceipt, Long>> {
        val owned = repository.selectedOwned(command.expoId, command.sessionId, command.applicationIds)
        if (owned.size != command.applicationIds.size ||
            owned.any { command.representativeId != null && it.second != command.representativeId }
        ) {
            conflict("취소할 신청의 소속이 일치하지 않습니다.")
        }
        return owned
    }

    private companion object {
        const val MAX_PARTY = 5
    }
}
