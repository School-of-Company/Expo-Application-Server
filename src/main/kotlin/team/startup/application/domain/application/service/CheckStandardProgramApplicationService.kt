package team.startup.application.domain.application.service

interface CheckStandardProgramApplicationService {
    fun execute(
        programId: Long,
        participantId: Long,
    ): Boolean
}
