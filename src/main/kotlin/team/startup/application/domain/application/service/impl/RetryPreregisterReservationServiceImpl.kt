package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.PreregisterReservationKey
import team.startup.application.domain.application.repository.PreregisterReservationRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.service.RetryPreregisterReservationService

@Service
class RetryPreregisterReservationServiceImpl(
    private val reservations: PreregisterReservationRepository,
    private val repository: PreregisterSessionRepository,
    private val ledger: PreregisterSessionLedger,
    private val workflow: PreregisterRegistrationWorkflow,
) : RetryPreregisterReservationService {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: PreregisterReservationKey) {
        ledger.transactions.execute {
            repository.lock(command.expoId)
            val saved = reservations.find(command.expoId, command.requestId, "") ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
            if (saved.state != "RESERVED" || saved.errorStatus == null) conflict("운영자 확인 대기 중인 신청만 재시도할 수 있습니다.")
            if (repository.expoDeleted(command.expoId)) conflict("삭제된 박람회입니다.")
            reservations.retryReviewed(saved)
        }
        workflow.recover(command.expoId, command.requestId)
    }
}
