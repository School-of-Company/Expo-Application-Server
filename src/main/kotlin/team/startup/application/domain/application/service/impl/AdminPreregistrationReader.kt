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
        // #284: 같은 읽기 스냅샷에서 보고용 원장 부가 정보를 함께 읽는다.
        within: (List<AdminPreregistrationRow>) -> Unit = {},
    ): Pair<List<AdminPreregistrationRow>, Long> {
        val reservationOnly =
            query.status?.none { it == AdminPreregistrationStatus.CONFIRMED || it == AdminPreregistrationStatus.CANCELLED } == true
        val candidates = if (reservationOnly) emptyList() else participants.resolve(expoId, query.q, null)
        val (rows, total) =
            requireNotNull(
                reads.execute {
                    sessions.readLock(expoId)
                    if (sessions.expoDeleted(expoId)) throw ResponseStatusException(HttpStatus.CONFLICT, "삭제된 행사입니다.")
                    repository.query(expoId, query, candidates, limit, offset, keys).also { within(it.first) }
                },
            )
        if (keys != null && total != keys.size.toLong()) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "현재 행사의 사전등록 대상을 찾을 수 없습니다.")
        }
        // 전체 대상 호출은 상한+1을 limit으로 넘긴다: 관리자 targets 5001(5000행), 보고 원천 10001(10000행, #284).
        if (limit > 5000 && total >= limit) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "대상은 최대 ${limit - 1}행입니다. 검색 범위를 좁혀 주세요.")
        }
        val details = participants.details(expoId, rows.mapNotNull { it.participantId }.distinct())
        val names = participants.names(expoId, rows.mapNotNull { it.cancellationActorUser?.participantId })
        return rows.map { row ->
            row.copy(
                participant = details[row.participantId],
                cancellationActorUser =
                    row.cancellationActorUser?.let { actor ->
                        actor.participantId?.let { actor.copy(name = names?.get(it)) } ?: actor
                    },
            )
        } to total
    }
}
