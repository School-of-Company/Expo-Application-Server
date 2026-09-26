package team.startup.application.domain.application.repository

import org.springframework.data.jpa.repository.JpaRepository
import team.startup.application.domain.application.entity.StandardProgramApplication

interface StandardProgramApplicationRepository : JpaRepository<StandardProgramApplication, Long> {
    fun existsByParticipantIdAndStandardProgramId(
        participantId: Long,
        standardProgramId: Long,
    ): Boolean

    fun findAllByStandardProgramId(standardProgramId: Long): List<StandardProgramApplication>
}
