package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.ApplicationActorType
import team.startup.application.domain.application.presentation.dto.OwnerPreregisterWaitlistCommand
import team.startup.application.domain.application.repository.PreregisterReservationRepository
import team.startup.application.domain.application.service.WithdrawOwnerPreregisterWaitlistService

@Service
class WithdrawOwnerPreregisterWaitlistServiceImpl(
    private val gateway: RegistrationGateway,
    private val reservations: PreregisterReservationRepository,
    private val ledger: PreregisterSessionLedger,
    private val waitlist: PreregisterWaitlist,
) : WithdrawOwnerPreregisterWaitlistService {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: OwnerPreregisterWaitlistCommand) {
        val phone = gateway.participantSessionPhone(command.expoId.toString(), command.participantSession)
        val sessionId =
            requireNotNull(
                ledger.transactions.execute {
                    ledger.lock(command.expoId)
                    val reservation =
                        reservations.waitlistedByPhone(command.expoId, phone) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
                    if (!reservations.withdraw(reservation, ApplicationActorType.USER)) {
                        conflict("승급 처리 중입니다. 확정 뒤 신청 취소를 이용해 주세요.")
                    }
                    reservation.sessionId
                },
            )
        // The withdrawn party may have been the head blocking smaller parties behind it.
        waitlist.promoteQuietly(command.expoId, sessionId)
    }
}
