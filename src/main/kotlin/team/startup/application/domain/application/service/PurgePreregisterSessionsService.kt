package team.startup.application.domain.application.service

import java.util.UUID

interface PurgePreregisterSessionsService {
    fun execute(expoId: UUID)
}
