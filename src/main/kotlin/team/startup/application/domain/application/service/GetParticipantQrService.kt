package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.ParticipantQrCommand
import team.startup.application.domain.application.presentation.dto.ParticipantQrResponse

interface GetParticipantQrService {
    fun execute(command: ParticipantQrCommand): ParticipantQrResponse
}
