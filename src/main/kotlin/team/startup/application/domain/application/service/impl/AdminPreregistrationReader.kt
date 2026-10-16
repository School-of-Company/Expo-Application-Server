package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationQuery
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationRow
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationStatus
import team.startup.application.domain.application.repository.AdminPreregistrationRepository
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import java.util.UUID

@Component
class AdminPreregistrationReader(
    private val repository: AdminPreregistrationRepository,
    private val sessions: PreregisterSessionRepository,
    private val participants: AdminParticipantGateway,
    transactionManager: PlatformTransactionManager,
) {
    private val reads =
        TransactionTemplate(transactionManager).apply {
            isReadOnly = true
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        }

    fun read(
        expoId: UUID,
        query: AdminPreregistrationQuery,
        limit: Int,
        offset: Long = 0,
        keys: List<String>? = null,
    ): Pair<List<AdminPreregistrationRow>, Long> {
        val reservationOnly =
            query.status?.none { it == AdminPreregistrationStatus.CONFIRMED || it == AdminPreregistrationStatus.CANCELLED } == true
        val candidates = if (reservationOnly) emptyList() else participants.resolve(expoId, query.q, null)
        val (rows, total) =
            requireNotNull(
                reads.execute {
                    sessions.readLock(expoId)
                    if (sessions.expoDeleted(expoId)) throw ResponseStatusException(HttpStatus.CONFLICT, "삭제된 행사입니다.")
                    repository.query(expoId, query, candidates, limit, offset, keys)
                },
            )
        if (keys != null && total != keys.size.toLong()) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "현재 행사의 사전등록 대상을 찾을 수 없습니다.")
        }
        if (total > 5000 && limit == 5001) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "대상은 최대 5000행입니다. 검색 범위를 좁혀 주세요.")
        }
        val details = participants.details(expoId, rows.mapNotNull { it.participantId }.distinct())
        return rows.map { it.copy(participant = details[it.participantId]) } to total
    }
}
