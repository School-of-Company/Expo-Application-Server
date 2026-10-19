package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.CancelOwnerApplicationCommand
import team.startup.application.domain.application.presentation.dto.GetOwnerPreregisterCancellationPreviewCommand
import team.startup.application.domain.application.presentation.dto.OwnerApplicationType
import team.startup.application.domain.application.presentation.dto.OwnerPreregisterCancellationPreview
import team.startup.application.domain.application.repository.OwnerApplicationRepository
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.service.GetOwnerPreregisterCancellationPreviewService

@Service
class GetOwnerPreregisterCancellationPreviewServiceImpl(
    private val gateway: RegistrationGateway,
    private val owners: OwnerApplicationRepository,
    private val programs: ProgramSessionApplicationRepository,
    transactionManager: PlatformTransactionManager,
) : GetOwnerPreregisterCancellationPreviewService {
    private val reads = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    // The User party lookup is remote, so only the local read runs in a transaction.
    @Transactional(readOnly = true, propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: GetOwnerPreregisterCancellationPreviewCommand): OwnerPreregisterCancellationPreview {
        val party = gateway.managedParty(command.expoId, command.sessionId)
        val target =
            CancelOwnerApplicationCommand(command.expoId, command.sessionId, OwnerApplicationType.PREREGISTER, command.applicationId)
        return requireNotNull(
            reads.execute {
                val application = owners.cancellationTarget(target, party) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
                OwnerPreregisterCancellationPreview(
                    if (application.status != "CONFIRMED") {
                        emptyList()
                    } else {
                        programs.preregisterCascade(command.expoId, application.participantId, application.applicationId.toLong())
                    },
                )
            },
        )
    }
}
