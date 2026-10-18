package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AdminOccupation
import team.startup.application.domain.application.presentation.dto.AdminPreregisterOccupationSummary
import team.startup.application.domain.application.presentation.dto.AdminPreregisterSummary
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.service.GetAdminPreregisterSummaryService
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Service
class GetAdminPreregisterSummaryServiceImpl(
    private val repository: PreregisterSessionRepository,
    private val participants: AdminParticipantGateway,
    private val mapper: ObjectMapper,
    transactionManager: PlatformTransactionManager,
) : GetAdminPreregisterSummaryService {
    private val reads = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    // The User occupation lookup runs after the ledger snapshot, outside the transaction and Expo lock.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(expoId: UUID): AdminPreregisterSummary {
        val (summary, confirmedIds, waiting) =
            requireNotNull(
                reads.execute {
                    repository.readLock(expoId)
                    if (repository.expoDeleted(expoId)) throw ResponseStatusException(HttpStatus.CONFLICT, "삭제된 행사입니다.")
                    Triple(
                        repository.adminSummary(expoId),
                        repository.confirmedParticipantIds(expoId),
                        repository.waitingRegistrations(expoId),
                    )
                },
            )
        val confirmed =
            participants
                .details(expoId, confirmedIds)
                .values
                .groupingBy { it.occupation }
                .eachCount()
        val waitingByOccupation = waiting.flatMap(::partyOccupations).groupingBy { it }.eachCount()
        return summary.copy(
            occupations =
                (AdminOccupation.entries + null).map {
                    AdminPreregisterOccupationSummary(it, (confirmed[it] ?: 0).toLong(), (waitingByOccupation[it] ?: 0).toLong())
                },
        )
    }

    private fun partyOccupations(registration: ParticipantRegistration): List<AdminOccupation?> =
        PreregisterSeatCategory.partyOccupations(registration, mapper).map { value -> AdminOccupation.entries.find { it.name == value } }
}
