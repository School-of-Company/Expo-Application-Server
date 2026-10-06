package team.startup.application.domain.application.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import team.startup.application.domain.application.entity.StandardProgramApplication

interface StandardProgramApplicationRepository : JpaRepository<StandardProgramApplication, Long> {
    @Query(value = "SELECT CAST(pg_advisory_xact_lock(:programId) AS text)", nativeQuery = true)
    fun lockStandardProgram(
        @Param("programId") programId: Long,
    ): String?

    fun existsByParticipantIdAndStandardProgramId(
        participantId: Long,
        standardProgramId: Long,
    ): Boolean

    fun findAllByStandardProgramId(standardProgramId: Long): List<StandardProgramApplication>
}
