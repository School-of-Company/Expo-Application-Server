package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.ApplicationActorType
import team.startup.application.domain.application.presentation.dto.WithdrawPreregisterReservationCommand
import team.startup.application.domain.application.repository.PreregisterReservationRepository
import team.startup.application.domain.application.service.WithdrawPreregisterReservationService

@Service
class WithdrawPreregisterReservationServiceImpl(
    private val reservations: PreregisterReservationRepository,
    private val ledger: PreregisterSessionLedger,
    private val waitlist: PreregisterWaitlist,
) : WithdrawPreregisterReservationService {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: WithdrawPreregisterReservationCommand) {
        val sessionId =
            ledger.transactions.execute {
                ledger.lock(command.expoId)
                val reservation =
                    reservations.find(command.expoId, command.requestId, "") ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
                if (reservation.withdrawn) return@execute null
                // The internal path carries no authenticated human identity; the admin path carries the verified admin.
                val actorType = if (command.adminId == null) ApplicationActorType.UNKNOWN else ApplicationActorType.ADMIN
                if (!reservations.withdraw(reservation, actorType, command.adminId)) {
                    conflict("대기 중인 신청만 취소할 수 있습니다.")
                }
                reservation.sessionId
            } ?: return
        waitlist.promoteQuietly(command.expoId, sessionId)
    }
}
