package team.startup.application.domain.application.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import team.startup.application.domain.application.entity.StandardProgramApplication

interface StandardProgramApplicationRepository : JpaRepository<StandardProgramApplication, Long> {
    @Query(value = "SELECT CAST(pg_advisory_xact_lock(:programId) AS text)", nativeQuery = true)
    fun lockProgram(
        @Param("programId") programId: Long,
    ): String?

    @Query(value = "SELECT EXISTS (SELECT 1 FROM tb_deleted_standard_program WHERE program_id = :programId)", nativeQuery = true)
    fun isDeleted(
        @Param("programId") programId: Long,
    ): Boolean

    fun existsByParticipantIdAndStandardProgramId(
        participantId: Long,
        standardProgramId: Long,
    ): Boolean

    fun findAllByStandardProgramIdOrderByIdAsc(standardProgramId: Long): List<StandardProgramApplication>

    @Modifying
    @Query(value = "INSERT INTO tb_deleted_standard_program (program_id) VALUES (:programId) ON CONFLICT DO NOTHING", nativeQuery = true)
    fun markDeleted(
        @Param("programId") programId: Long,
    ): Int

    @Modifying
    @Query("DELETE FROM StandardProgramApplication a WHERE a.standardProgramId = :programId")
    fun deleteAllByStandardProgramId(
        @Param("programId") programId: Long,
    ): Int
}
