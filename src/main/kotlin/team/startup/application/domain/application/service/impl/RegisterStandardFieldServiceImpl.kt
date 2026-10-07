package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import team.startup.application.domain.application.service.RegisterStandardFieldService
import team.startup.application.domain.application.service.RegistrationCommand

@Service
class RegisterStandardFieldServiceImpl(
    private val workflow: RegistrationWorkflow,
) : RegisterStandardFieldService {
    override fun execute(command: RegistrationCommand) = workflow.register(command, "STANDARD", "FIELD")
}
