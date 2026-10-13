package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.PreregisterRegistrationReceipt

interface RegisterStandardPreService {
    // Null means confirmed; a receipt means WAITING or PROCESSING.
    fun execute(command: RegistrationCommand): PreregisterRegistrationReceipt?
}
