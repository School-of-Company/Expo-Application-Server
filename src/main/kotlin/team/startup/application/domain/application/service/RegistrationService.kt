package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.RegistrationRequest

data class RegistrationCommand(
    val expoId: String,
    val participantType: String,
    val applicationType: String,
    val request: RegistrationRequest,
    val idempotencyKey: String?,
)

interface RegistrationService {
    fun register(command: RegistrationCommand)
}
