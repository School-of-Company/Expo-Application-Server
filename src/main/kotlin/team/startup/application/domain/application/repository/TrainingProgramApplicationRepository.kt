package team.startup.application.domain.application.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import team.startup.application.domain.application.entity.TrainingProgramApplication

interface TrainingProgramApplicationRepository : JpaRepository<TrainingProgramApplication, Long> {
    @Query(value = "SELECT CAST(pg_advisory_xact_lock(:programId) AS text)", nativeQuery = true)
    fun lockTrainingProgram(
        @Param("programId") programId: Long,
    ): String?

    // 프로그램 ID는 양수이고 연수자 잠금은 음수 키를 사용한다.
    @Query(value = "SELECT CAST(pg_advisory_xact_lock(:traineeId * -1) AS text)", nativeQuery = true)
    fun lockTrainee(
        @Param("traineeId") traineeId: Long,
    ): String?

    @Query(value = "SELECT EXISTS (SELECT 1 FROM tb_deleted_training_program WHERE program_id = :programId)", nativeQuery = true)
    fun isDeleted(
        @Param("programId") programId: Long,
    ): Boolean

    @Modifying
    @Query(value = "INSERT INTO tb_deleted_training_program (program_id) VALUES (:programId) ON CONFLICT DO NOTHING", nativeQuery = true)
    fun markDeleted(
        @Param("programId") programId: Long,
    ): Int

    fun existsByTraineeIdAndTrainingProgramId(
        traineeId: Long,
        trainingProgramId: Long,
    ): Boolean

    fun countByTrainingProgramId(trainingProgramId: Long): Long

    fun findAllByTrainingProgramIdOrderByIdAsc(trainingProgramId: Long): List<TrainingProgramApplication>

    // ponytail: 필수 프로그램은 신청이 99,999건까지 가능하므로 엔티티 단건 삭제 대신 벌크 삭제
    @Modifying
    @Query("DELETE FROM TrainingProgramApplication a WHERE a.trainingProgramId = :programId")
    fun deleteAllByTrainingProgramId(
        @Param("programId") programId: Long,
    ): Int

    fun findAllByTraineeIdIn(traineeIds: Collection<Long>): List<TrainingProgramApplication>

    fun findAllByTraineeId(traineeId: Long): List<TrainingProgramApplication>
}
