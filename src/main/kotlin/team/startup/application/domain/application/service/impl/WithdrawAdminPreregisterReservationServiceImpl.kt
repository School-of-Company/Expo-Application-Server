package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.PreregisterReservationKey
import team.startup.application.domain.application.presentation.dto.WithdrawAdminPreregisterReservationCommand
import team.startup.application.domain.application.service.WithdrawAdminPreregisterReservationService
import team.startup.application.domain.application.service.WithdrawPreregisterReservationService

// A waiting party has no User participant or Attendance record, so only the Expo cancel authority guards it.
@Service
class WithdrawAdminPreregisterReservationServiceImpl(
    private val expo: AdminExpoGateway,
    private val withdraw: WithdrawPreregisterReservationService,
) : WithdrawAdminPreregisterReservationService {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: WithdrawAdminPreregisterReservationCommand) {
        expo.requireCancelAccess(command.expoId, command.authorization)
        withdraw.execute(PreregisterReservationKey(command.expoId, command.requestId))
    }
}
