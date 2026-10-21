package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.ParticipantQrResponse
import team.startup.application.domain.application.repository.ManagedParticipantRepository
import team.startup.application.domain.application.service.GetAdminParticipantQrService
import java.util.UUID

@Service
class GetAdminParticipantQrServiceImpl(
    private val gateway: RegistrationGateway,
    private val repository: ManagedParticipantRepository,
    private val participants: AdminParticipantGateway,
    @Value("\${application.attendance.enabled:false}") private val attendanceEnabled: Boolean,
    transactionManager: PlatformTransactionManager,
) : GetAdminParticipantQrService {
    private val reads = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    @Transactional(readOnly = true, propagation = Propagation.NOT_SUPPORTED)
    override fun execute(
        expoId: UUID,
        participantId: Long,
    ): ParticipantQrResponse {
        // Attendance checks the party against this representative; an ambiguous ledger is never guessed.
        val confirmed = requireNotNull(reads.execute { repository.confirmedRepresentatives(expoId, participantId) })
        // #261: 확정 사전등록이 없으면 User가 FIELD로 저장한 참가자만 User 일행의 대표자로 조회한다.
        val fieldRepresentative = if (confirmed.isEmpty()) participants.fieldRepresentative(expoId, participantId) else null
        val field = fieldRepresentative != null
        val representative = confirmed.singleOrNull() ?: fieldRepresentative ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (!attendanceEnabled) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
        val response = gateway.participantQr(expoId, participantId, representative)
        if (!requireNotNull(reads.execute { repository.qrAvailable(expoId, participantId, representative, field) })) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        return response
    }
}
