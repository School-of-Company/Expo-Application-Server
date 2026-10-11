package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.PreparePreregisterSessionChangeCommand

interface PreparePreregisterSessionChangeService {
    fun execute(command: PreparePreregisterSessionChangeCommand): Unit
}
