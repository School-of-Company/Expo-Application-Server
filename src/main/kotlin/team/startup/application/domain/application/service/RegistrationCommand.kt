package team.startup.application.domain.application.service

import com.fasterxml.jackson.annotation.JsonIgnore
import team.startup.application.domain.application.presentation.RegistrationRequest

data class RegistrationCommand(
    val expoId: String,
    val request: RegistrationRequest,
    val idempotencyKey: String?,
    @get:JsonIgnore val participantSession: String? = null,
)
