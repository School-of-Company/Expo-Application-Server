package team.startup.application.domain.application.service

interface CheckTrainingProgramApplicationService {
    fun execute(
        programId: Long,
        traineeId: Long,
    ): Boolean
}
