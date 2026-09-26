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
    name = "tb_training_program_application",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_training_application_trainee_program",
            columnNames = ["trainee_id", "training_program_id"],
        ),
    ],
)
class TrainingProgramApplication(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @field:Column(name = "trainee_id", nullable = false)
    val traineeId: Long,
    @field:Column(name = "training_program_id", nullable = false)
    val trainingProgramId: Long,
)
