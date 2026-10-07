package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Component
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.ApplyTrainingProgramsCommand
import team.startup.application.domain.application.presentation.dto.TrainingOperationReceipt
import team.startup.application.domain.application.presentation.dto.TrainingOperationType
import team.startup.application.domain.application.repository.TrainingOperationRepository
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

@Component
class TrainingOperationJournal(
    private val operations: TrainingOperationRepository,
    private val mapper: ObjectMapper,
) {
    private val commandMapper = JsonMapper.builder().build()

    fun replay(
        command: ApplyTrainingProgramsCommand,
        type: TrainingOperationType,
    ): Boolean {
        require(command.expectedVersion == null || (command.operationId != null && command.expectedVersion >= 0))
        val operationId = command.operationId ?: return false
        operations.lockOperation(operationId)
        val stored = operations.find(operationId) ?: return false
        if (stored.first != canonical(command, type)) {
            throw ProgramApplicationConflictException("다른 명령에 사용된 operationId입니다.")
        }
        return true
    }

    fun lockVersion(command: ApplyTrainingProgramsCommand): Long {
        val version = operations.lockVersion(command.trainee.id)
        if (command.expectedVersion != null && command.expectedVersion != version) {
            throw ProgramApplicationConflictException("연수 신청 변경 버전이 일치하지 않습니다.")
        }
        return version
    }

    fun complete(
        command: ApplyTrainingProgramsCommand,
        type: TrainingOperationType,
        previousVersion: Long,
        changed: Boolean,
        programIds: List<Long>,
    ) {
        val version = if (changed) operations.incrementVersion(command.trainee.id) else previousVersion
        val operationId = command.operationId ?: return
        val receipt =
            TrainingOperationReceipt(
                operationId,
                type,
                command.trainee.expoId,
                command.trainee.id,
                version,
                changed,
                programIds.sorted(),
                Instant.now(),
            )
        operations.save(operationId, canonical(command, type), mapper.writeValueAsString(receipt))
    }

    private fun canonical(
        command: ApplyTrainingProgramsCommand,
        type: TrainingOperationType,
    ): String =
        commandMapper.writeValueAsString(
            listOf(
                1,
                type.name,
                command.trainee.id,
                command.trainee.expoId,
                command.expectedVersion,
                command.programs.sortedBy { it.id }.map { listOf(it.id, it.expoId, it.category.name) },
            ),
        )
}
