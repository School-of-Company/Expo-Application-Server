package team.startup.application.domain.application.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import team.startup.application.domain.application.entity.TrainingProgramApplication

interface TrainingProgramApplicationRepository : JpaRepository<TrainingProgramApplication, Long> {
    @Query(value = "SELECT CAST(pg_advisory_xact_lock(:programId) AS text)", nativeQuery = true)
    fun lockTrainingProgram(
        @Param("programId") programId: Long,
    ): String?

    fun existsByTraineeIdAndTrainingProgramId(
        traineeId: Long,
        trainingProgramId: Long,
    ): Boolean

    fun countByTrainingProgramId(trainingProgramId: Long): Long

    fun findAllByTrainingProgramId(trainingProgramId: Long): List<TrainingProgramApplication>

    fun findAllByTraineeIdIn(traineeIds: Collection<Long>): List<TrainingProgramApplication>
}
