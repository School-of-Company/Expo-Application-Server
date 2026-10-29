package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.ProgramSessionKey
import team.startup.application.domain.application.repository.ProgramWaitlistRepository
import team.startup.application.domain.application.service.ReconcileProgramSessionService

@Service
class ReconcileProgramSessionServiceImpl(
    private val waitlist: ProgramWaitlist,
    private val gateway: RegistrationGateway,
    private val repository: ProgramWaitlistRepository,
) : ReconcileProgramSessionService {
    @Transactional
    override fun execute(command: ProgramSessionKey) {
        repository.settledDefinition(command)?.let { current ->
            val policy = current.policy
            if (policy != null &&
                (
                    repository.waitingCount(current) == 0 ||
                        (policy.channels ?: listOf(null)).none { waitlist.free(current, it?.name) > 0 }
                )
            ) {
                return
            }
        }
        val definition =
            gateway.programDefinition(command.expoId.toString(), command.programType, command.programId)
                ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
        waitlist.lock(definition)
        repository.observe(definition)
        waitlist.promote(definition)
    }
}
