package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Component
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationQuery
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationRow
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationStatus
import team.startup.application.domain.application.presentation.dto.ReportPreregistrationRowType
import team.startup.application.domain.application.presentation.dto.ReportPreregistrationSession
import team.startup.application.domain.application.presentation.dto.ReportPreregistrationTarget
import team.startup.application.domain.application.presentation.dto.ReportSeatCategory
import team.startup.application.domain.application.presentation.dto.ReportSeatCounts
import team.startup.application.domain.application.repository.ReportPreregistrationRepository
import java.util.UUID

/** #284 접수대장 행을 같은 읽기 스냅샷의 원장 부가 정보와 묶어 보고 대상 행으로 만든다. 선택과 필터 전체가 같이 쓴다. */
@Component
class ReportPreregistrationTargets(
    private val reader: AdminPreregistrationReader,
    private val repository: ReportPreregistrationRepository,
) {
    fun read(
        expoId: UUID,
        query: AdminPreregistrationQuery,
        keys: List<String>? = null,
    ): List<ReportPreregistrationTarget> {
        lateinit var sessions: Map<Long, ReportPreregistrationSession>
        lateinit var applications: Map<Long, ReportPreregistrationRepository.ApplicationExtra>
        lateinit var reservations: Map<String, ReportPreregistrationRepository.ReservationExtra>
        val (rows) =
            reader.read(expoId, query, 10001, keys = keys) { rows ->
                sessions = repository.sessions(expoId, rows.map { it.sessionId }.toSet())
                applications = repository.applications(expoId, rows.mapNotNull { it.applicationId })
                reservations = repository.reservations(expoId, rows.mapNotNull { it.reservationId })
            }
        return rows.map { row ->
            val session = sessions.getValue(row.sessionId)
            row.applicationId?.let { target(expoId, row, session, applications.getValue(it)) }
                ?: target(expoId, row, session, reservations.getValue(requireNotNull(row.reservationId)))
        }
    }

    private fun target(
        expoId: UUID,
        row: AdminPreregistrationRow,
        session: ReportPreregistrationSession,
        extra: ReportPreregistrationRepository.ApplicationExtra,
    ) = ReportPreregistrationTarget(
        expoId,
        row.key,
        ReportPreregistrationRowType.APPLICATION,
        row.status,
        row.applicationId,
        null,
        row.participantId,
        row.representativeId,
        row.participantId == row.representativeId,
        null,
        row.applicants,
        extra.seatCategory?.let(ReportSeatCategory::valueOf) ?: ReportSeatCategory.UNRECORDED,
        null,
        session,
        row.createdAt,
        row.cancelledAt,
        row.cancellationActorType,
        row.cancellationActorUser,
        row.cancellationActorId,
        extra.history,
        extra.promotedFromWaiting,
        extra.snapshot,
        row.participant,
    )

    private fun target(
        expoId: UUID,
        row: AdminPreregistrationRow,
        session: ReportPreregistrationSession,
        extra: ReportPreregistrationRepository.ReservationExtra,
    ): ReportPreregistrationTarget {
        val teacher = extra.teacher ?: 0
        val other = extra.other ?: 0
        return ReportPreregistrationTarget(
            expoId,
            row.key,
            ReportPreregistrationRowType.RESERVATION,
            row.status,
            null,
            row.reservationId,
            null,
            extra.companionRepresentativeId,
            null,
            extra.kind,
            row.applicants,
            null,
            ReportSeatCounts(teacher, other, row.applicants - teacher - other),
            session,
            null,
            row.cancelledAt,
            row.cancellationActorType,
            row.cancellationActorUser,
            row.cancellationActorId,
            emptyList(),
            // 대기 중·철회된 일행은 아직 승급되지 않았다. 대기열을 거쳐 복구 중(PENDING)인 일행만 승급이다.
            extra.promotedFromWaiting && row.status == AdminPreregistrationStatus.PENDING,
            row.reservation,
            null,
        )
    }
}
