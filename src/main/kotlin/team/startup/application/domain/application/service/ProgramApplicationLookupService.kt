package team.startup.application.domain.application.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.repository.StandardProgramApplicationRepository
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository

@Service
class ProgramApplicationLookupService(
    private val standardApplications: StandardProgramApplicationRepository,
    private val trainingApplications: TrainingProgramApplicationRepository,
) {
    @Transactional(readOnly = true)
    fun isStandardApplied(
        programId: Long,
        participantId: Long,
    ): Boolean = standardApplications.existsByParticipantIdAndStandardProgramId(participantId, programId)

    @Transactional(readOnly = true)
    fun isTrainingApplied(
        programId: Long,
        traineeId: Long,
    ): Boolean = trainingApplications.existsByTraineeIdAndTrainingProgramId(traineeId, programId)
}
