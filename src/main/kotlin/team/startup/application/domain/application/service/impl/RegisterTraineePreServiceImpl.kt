package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import team.startup.application.domain.application.service.RegisterTraineePreService
import team.startup.application.domain.application.service.RegistrationCommand

@Service
class RegisterTraineePreServiceImpl(
    private val workflow: RegistrationWorkflow,
) : RegisterTraineePreService {
    override fun execute(command: RegistrationCommand) = workflow.register(command, "TRAINEE", "PRE")
}
