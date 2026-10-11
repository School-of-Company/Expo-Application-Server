package team.startup.application.domain.application.service

interface RegisterTraineeFieldService {
    fun execute(command: RegistrationCommand)
}
