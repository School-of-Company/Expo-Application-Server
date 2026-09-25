package team.startup.application.domain.application.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "tb_standard_program_application",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_standard_application_participant_program",
            columnNames = ["participant_id", "standard_program_id"],
        ),
    ],
)
class StandardProgramApplication(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @field:Column(name = "participant_id", nullable = false)
    val participantId: Long,
    @field:Column(name = "standard_program_id", nullable = false)
    val standardProgramId: Long,
)
