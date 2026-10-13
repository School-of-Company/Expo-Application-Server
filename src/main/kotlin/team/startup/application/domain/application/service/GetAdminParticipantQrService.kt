package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.ParticipantQrResponse
import java.util.UUID

interface GetAdminParticipantQrService {
    fun execute(
        expoId: UUID,
        participantId: Long,
    ): ParticipantQrResponse
}
