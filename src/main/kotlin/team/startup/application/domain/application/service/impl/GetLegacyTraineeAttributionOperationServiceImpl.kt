package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.GetLegacyTraineeAttributionOperationCommand
import team.startup.application.domain.application.presentation.dto.LegacyTraineeAttributionOperationStatus
import team.startup.application.domain.application.repository.LegacyTraineeAttributionRepository
import team.startup.application.domain.application.service.GetLegacyTraineeAttributionOperationService

@Service
class GetLegacyTraineeAttributionOperationServiceImpl(
    private val expo: AdminExpoGateway,
    private val repository: LegacyTraineeAttributionRepository,
) : GetLegacyTraineeAttributionOperationService {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: GetLegacyTraineeAttributionOperationCommand): LegacyTraineeAttributionOperationStatus {
        expo.requireAccess(command.expoId, command.authorization)
        val operation =
            repository.operation(command.operationId)?.takeIf { it.expoId == command.expoId }
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return LegacyTraineeAttributionOperationStatus(operation.id, operation.state, operation.errorStatus, operation.result)
    }
}
