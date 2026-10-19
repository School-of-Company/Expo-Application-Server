package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.ApplyPreregisterSessionCommand
import team.startup.application.domain.application.presentation.dto.PreregisterApplicationReceipt
import team.startup.application.domain.application.presentation.dto.targeted
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.service.ApplyPreregisterSessionService
import tools.jackson.databind.ObjectMapper

@Service
class ApplyPreregisterSessionServiceImpl(
    private val repository: PreregisterSessionRepository,
    private val ledger: PreregisterSessionLedger,
    private val mapper: ObjectMapper,
    private val attendance: SessionAttendanceOutbox,
) : ApplyPreregisterSessionService {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: ApplyPreregisterSessionCommand): List<PreregisterApplicationReceipt> {
        if (command.sessionId <= 0 || command.representativeId <= 0 || command.requestId.isBlank() || command.requestId.length > 100 ||
            command.participantIds.size !in 1..5 || command.participantIds.any { it <= 0 } ||
            command.participantIds.distinct().size != command.participantIds.size
        ) {
            badSessionRequest("신청 식별자와 참가자 목록이 올바르지 않습니다.")
        }
        val serialized = mapper.writeValueAsString(command)
        ledger.transactions
            .execute {
                ledger.lock(command.expoId)
                replay(command, serialized)?.let { return@execute it }
                ledger.ensureAvailable(command.expoId, command.sessionId)
                null
            }?.let { return it }
        val observation = ledger.observe(command.expoId, command.sessionId)
        return requireNotNull(
            ledger.transactions.execute {
                ledger.lock(command.expoId)
                replay(command, serialized)?.let { return@execute it }
                val definition = ledger.current(command.expoId, command.sessionId, observation)
                if (definition.closed) conflict("마감된 회차입니다.")
                val existing = command.participantIds.associateWith { repository.activeOwned(command.expoId, command.sessionId, it) }
                if (existing.values.filterNotNull().any { it.second != command.representativeId }) {
                    conflict("다른 대표자의 활성 신청이 있습니다.")
                }
                val added = existing.filterValues { it == null }.keys
                // #198: other sessions are allowed unless their times overlap this one.
                if (repository.overlapping(command.expoId, command.sessionId, added, definition)) {
                    conflict("시간이 겹치는 회차에 활성 신청이 있습니다.")
                }
                val newCount = added.size
                // This trusted-ID path carries no occupations, so it cannot take seats from a target session (#173).
                if (newCount > 0 && definition.targeted) conflict("대상별 정원 회차는 사전등록 신청으로만 좌석을 확보합니다.")
                if (newCount.toLong() > definition.capacity.toLong() - repository.occupied(command.expoId, command.sessionId)) {
                    conflict("일행 전원을 수용할 수 없습니다.")
                }
                val receipt =
                    command.participantIds.map { participantId ->
                        existing[participantId]?.first ?: repository.insert(
                            command.expoId,
                            command.sessionId,
                            command.representativeId,
                            participantId,
                            "CONFIRMED",
                        )
                    }
                receipt.filter { existing[it.participantId] == null }.forEach {
                    attendance.confirmed(command.expoId, it.participantId, command.sessionId)
                }
                repository.saveRequest(command.expoId, command.requestId, serialized, receipt)
                receipt
            },
        )
    }

    private fun replay(
        command: ApplyPreregisterSessionCommand,
        serialized: String,
    ): List<PreregisterApplicationReceipt>? =
        repository.request(command.expoId, command.requestId)?.let {
            if (it.first != serialized) conflict("같은 신청 키의 내용이 다릅니다.")
            it.second
        }
}
