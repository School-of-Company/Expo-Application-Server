package team.startup.application.domain.application.repository

import org.springframework.data.jpa.repository.JpaRepository
import team.startup.application.domain.application.entity.TrainingProgramApplication

interface TrainingProgramApplicationRepository : JpaRepository<TrainingProgramApplication, Long> {
    fun existsByTraineeIdAndTrainingProgramId(
        traineeId: Long,
        trainingProgramId: Long,
    ): Boolean

    fun countByTrainingProgramId(trainingProgramId: Long): Long

    fun findAllByTrainingProgramId(trainingProgramId: Long): List<TrainingProgramApplication>

    fun findAllByTraineeIdIn(traineeIds: Collection<Long>): List<TrainingProgramApplication>
}
