package team.startup.application.domain.application.service

interface RegisterStandardPreService {
    fun execute(command: RegistrationCommand)
}
