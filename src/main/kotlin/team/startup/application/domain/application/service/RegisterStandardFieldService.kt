package team.startup.application.domain.application.service

interface RegisterStandardFieldService {
    fun execute(command: RegistrationCommand)
}
