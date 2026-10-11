package team.startup.application.domain.application.service

interface RegisterTraineePreService {
    fun execute(command: RegistrationCommand)
}
