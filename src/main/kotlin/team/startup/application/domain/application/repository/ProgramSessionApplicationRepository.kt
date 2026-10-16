package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.ApplyProgramSessionCommand
import team.startup.application.domain.application.presentation.dto.ProgramFormSnapshot
import team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition
import team.startup.application.domain.application.presentation.dto.ProgramSessionReceipt
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Repository
class ProgramSessionApplicationRepository(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) {
    fun lockParticipant(participantId: Long) {
        lock(43, participantId.toString())
    }

    fun tryLockParticipant(participantId: Long): Boolean =
        requireNotNull(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(43, hashtext(?))", Boolean::class.java, participantId.toString()))

    fun lockGroups(definitions: List<ProgramSessionDefinition>) {
        definitions
            .map { "${it.expoId}:${it.programType}:${it.groupId}" }
            .distinct()
            .sorted()
            .forEach { lock(44, it) }
    }

    private fun lock(
        namespace: Int,
        key: String,
    ) {
        jdbc.queryForObject("SELECT CAST(pg_advisory_xact_lock(?, hashtext(?)) AS text)", String::class.java, namespace, key)
    }

    fun replay(command: ApplyProgramSessionCommand): Pair<Boolean, ProgramSessionReceipt>? =
        jdbc
            .query(
                "SELECT command = CAST(? AS jsonb), receipt FROM tb_program_session_receipt " +
                    "WHERE expo_id = ? AND participant_id = ? AND idempotency_key = ?",
                { rs, _ -> rs.getBoolean(1) to mapper.readValue(rs.getString(2), ProgramSessionReceipt::class.java) },
                mapper.writeValueAsString(commandSnapshot(command)),
                UUID.fromString(command.expoId),
                command.participantId,
                command.idempotencyKey,
            ).singleOrNull()

    private fun commandSnapshot(command: ApplyProgramSessionCommand): JsonNode =
        mapper.valueToTree(
            mapOf(
                "expoId" to command.expoId,
                "programType" to command.programType,
                "programId" to command.programId,
                "participantId" to command.participantId,
                "formVersion" to command.formVersion,
                "answers" to command.answers,
            ),
        )

    fun confirmedPreregister(command: ApplyProgramSessionCommand): Boolean =
        jdbc
            .query(
                "SELECT id FROM tb_preregister_application WHERE expo_id = ? AND participant_id = ? AND status = 'CONFIRMED' FOR SHARE",
                { rs, _ -> rs.getLong(1) },
                UUID.fromString(command.expoId),
                command.participantId,
            ).isNotEmpty()

    fun activeInGroup(
        command: ApplyProgramSessionCommand,
        groupId: UUID,
    ): Boolean =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM tb_program_session_application " +
                    "WHERE expo_id = ? AND program_type = ? AND group_id = ? AND participant_id = ? AND status IN ('CONFIRMED', 'WAITING'))",
                Boolean::class.java,
                UUID.fromString(command.expoId),
                command.programType.name,
                groupId,
                command.participantId,
            ),
        )

    fun confirmedCount(
        type: ProgramSessionType,
        programId: Long,
    ): Long {
        val table = if (type == ProgramSessionType.STANDARD) "tb_standard_program_application" else "tb_training_program_application"
        val column = if (type == ProgramSessionType.STANDARD) "standard_program_id" else "training_program_id"
        return requireNotNull(jdbc.queryForObject("SELECT count(*) FROM $table WHERE $column = ?", Long::class.java, programId))
    }

    fun knownGroups(
        expoId: String,
        type: ProgramSessionType,
        personId: Long,
    ): Map<Long, UUID> {
        val column = if (type == ProgramSessionType.STANDARD) "participant_id" else "trainee_id"
        return jdbc
            .query(
                "SELECT program_id, group_id FROM tb_program_session_application " +
                    "WHERE expo_id = ? AND program_type = ? AND $column = ? AND status IN ('CONFIRMED', 'WAITING')",
                { rs, _ -> rs.getLong(1) to rs.getObject(2, UUID::class.java) },
                UUID.fromString(expoId),
                type.name,
                personId,
            ).toMap()
    }

    fun ensureLegacyGroups(
        definitions: List<ProgramSessionDefinition>,
        existingDefinitions: List<ProgramSessionDefinition>,
        personId: Long,
        replacing: Boolean = false,
    ) {
        if (definitions.isEmpty()) return
        val groups = definitions.groupBy { it.groupId }
        if (groups.any { it.value.size > 1 }) throw ProgramApplicationConflictException("같은 프로그램 묶음에 여러 회차를 신청할 수 없습니다.")
        val existing =
            existingDefinitions.associate { it.id to it.groupId } +
                knownGroups(definitions.first().expoId, definitions.first().programType, personId)
        if (definitions.any { definition ->
                existing.any { (id, group) -> group == definition.groupId && (!replacing || id != definition.id) }
            }
        ) {
            throw ProgramApplicationConflictException("같은 프로그램 묶음에 활성 신청이 있습니다.")
        }
    }

    fun save(
        command: ApplyProgramSessionCommand,
        definition: ProgramSessionDefinition,
        form: ProgramFormSnapshot,
        receipt: ProgramSessionReceipt,
        channel: String?,
    ) {
        jdbc.update(
            "INSERT INTO tb_program_session_application " +
                "(id, expo_id, program_type, program_id, group_id, participant_id, trainee_id, status, " +
                "definition_snapshot, form_id, form_version, questions_snapshot, answers_snapshot, channel) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)",
            receipt.applicationId,
            UUID.fromString(command.expoId),
            command.programType.name,
            command.programId,
            definition.groupId,
            command.participantId,
            receipt.traineeId,
            receipt.status,
            mapper.writeValueAsString(definition),
            form.id,
            form.version,
            mapper.writeValueAsString(form.questions),
            mapper.writeValueAsString(command.answers),
            channel,
        )
        jdbc.update(
            "INSERT INTO tb_program_session_history (application_id, status, actor_type, previous_status_recorded) VALUES (?, ?, 'USER', TRUE)",
            receipt.applicationId,
            receipt.status,
        )
        jdbc.update(
            "INSERT INTO tb_program_session_receipt (expo_id, participant_id, idempotency_key, command, receipt, application_id) " +
                "VALUES (?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)",
            UUID.fromString(command.expoId),
            command.participantId,
            command.idempotencyKey,
            mapper.writeValueAsString(commandSnapshot(command)),
            mapper.writeValueAsString(receipt),
            receipt.applicationId,
        )
    }

    fun removeProgram(
        type: ProgramSessionType,
        programId: Long,
    ) {
        jdbc.update(
            "INSERT INTO tb_program_session_event (application_id, status) " +
                "SELECT id, 'CANCELLED' FROM tb_program_session_application WHERE program_type = ? AND program_id = ? AND status <> 'CANCELLED' ON CONFLICT DO NOTHING",
            type.name,
            programId,
        )
        jdbc.update(
            "INSERT INTO tb_program_session_history (application_id, status, actor_type, previous_status, previous_status_recorded) " +
                "SELECT id, 'CANCELLED', 'SYSTEM', status, TRUE FROM tb_program_session_application " +
                "WHERE program_type = ? AND program_id = ? AND status <> 'CANCELLED'",
            type.name,
            programId,
        )
        jdbc.update(
            "UPDATE tb_program_session_application SET status = 'CANCELLED' WHERE program_type = ? AND program_id = ?",
            type.name,
            programId,
        )
    }

    fun removeTrainingPrograms(
        traineeId: Long,
        retainedIds: Set<Long>,
    ) {
        jdbc
            .query(
                "SELECT DISTINCT program_id FROM tb_program_session_application WHERE program_type = 'TRAINING' AND trainee_id = ? AND status = 'CONFIRMED'",
                { rs, _ -> rs.getLong(1) },
                traineeId,
            ).filter { it !in retainedIds }
            .forEach { programId ->
                val linked = jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM tb_program_session_application a " +
                        "JOIN tb_program_guardian_member m ON m.application_id = a.id " +
                        "WHERE a.program_type = 'TRAINING' AND a.trainee_id = ? AND a.program_id = ? AND a.status = 'CONFIRMED')",
                    Boolean::class.java,
                    traineeId,
                    programId,
                )
                if (linked == true) throw ProgramApplicationConflictException("학생·보호자 신청은 연계 취소 후 변경해야 합니다.")
                jdbc.update(
                    "INSERT INTO tb_program_session_event (application_id, status) " +
                        "SELECT id, 'CANCELLED' FROM tb_program_session_application " +
                        "WHERE program_type = 'TRAINING' AND trainee_id = ? AND program_id = ? AND status = 'CONFIRMED' ON CONFLICT DO NOTHING",
                    traineeId,
                    programId,
                )
                jdbc.update(
                    "INSERT INTO tb_program_session_history " +
                        "(application_id, status, actor_type, previous_status, previous_status_recorded) " +
                        "SELECT id, 'CANCELLED', 'SYSTEM', status, TRUE FROM tb_program_session_application " +
                        "WHERE program_type = 'TRAINING' AND trainee_id = ? AND program_id = ? AND status = 'CONFIRMED'",
                    traineeId,
                    programId,
                )
                jdbc.update(
                    "UPDATE tb_program_session_application SET status = 'CANCELLED' " +
                        "WHERE program_type = 'TRAINING' AND trainee_id = ? AND program_id = ? AND status = 'CONFIRMED'",
                    traineeId,
                    programId,
                )
            }
    }
}
