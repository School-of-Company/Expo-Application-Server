package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import team.startup.application.domain.application.service.RegisterStandardPreService
import team.startup.application.domain.application.service.RegistrationCommand

@Service
class RegisterStandardPreServiceImpl(
    private val workflow: RegistrationWorkflow,
) : RegisterStandardPreService {
    override fun execute(command: RegistrationCommand) = workflow.register(command, "STANDARD", "PRE")
}
