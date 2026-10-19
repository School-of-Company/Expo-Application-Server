package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationCancellationResult
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationQuery
import team.startup.application.domain.application.presentation.dto.CancelAdminPreregistrationCommand
import team.startup.application.domain.application.presentation.dto.CancelPreregisterSessionCommand
import team.startup.application.domain.application.presentation.dto.WithdrawPreregisterReservationCommand
import team.startup.application.domain.application.repository.AdminPreregistrationRepository
import team.startup.application.domain.application.service.CancelAdminPreregistrationService
import team.startup.application.domain.application.service.CancelPreregisterSessionService
import team.startup.application.domain.application.service.WithdrawPreregisterReservationService

@Service
class CancelAdminPreregistrationServiceImpl(
    private val expo: AdminExpoGateway,
    private val ledger: AdminPreregistrationRepository,
    private val cancel: CancelPreregisterSessionService,
    private val withdraw: WithdrawPreregisterReservationService,
) : CancelAdminPreregistrationService {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: CancelAdminPreregistrationCommand): AdminPreregistrationCancellationResult {
        val access = expo.requireCancelAccess(command.expoId, command.authorization)
        // Same key resolution as /targets: every key must be in this expo's ledger, never substituted. The existing
        // services recheck ownership and state under the Expo lock.
        val (rows, _) = ledger.query(command.expoId, AdminPreregistrationQuery(), null, command.keys.size, keys = command.keys)
        if (rows.size != command.keys.size) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val reservationId = rows.singleOrNull()?.reservationId
        if (reservationId != null) {
            withdraw.execute(WithdrawPreregisterReservationCommand(command.expoId, reservationId, access.adminId))
        } else {
            val party = rows.first()
            if (rows.any { it.applicationId == null || it.sessionId != party.sessionId || it.representativeId != party.representativeId }) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "한 일행의 확정 신청 또는 대기 일행 하나만 함께 취소할 수 있습니다.")
            }
            cancel.execute(
                CancelPreregisterSessionCommand(
                    command.expoId,
                    party.sessionId,
                    party.representativeId,
                    rows.map { requireNotNull(it.applicationId) },
                    access.adminId,
                ),
            )
        }
        return AdminPreregistrationCancellationResult(command.keys)
    }
}
