package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import team.startup.application.domain.application.service.RegisterTraineeFieldService
import team.startup.application.domain.application.service.RegistrationCommand

@Service
class RegisterTraineeFieldServiceImpl(
    private val workflow: RegistrationWorkflow,
) : RegisterTraineeFieldService {
    override fun execute(command: RegistrationCommand) = workflow.register(command, "TRAINEE", "FIELD")
}
