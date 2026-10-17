package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import team.startup.application.domain.application.presentation.dto.ApplyStudentGuardianBatchCommand
import team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition
import team.startup.application.domain.application.presentation.dto.ProgramSessionKey
import team.startup.application.domain.application.presentation.dto.StudentGuardianBatchReceipt
import team.startup.application.domain.application.presentation.dto.StudentGuardianLink
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Repository
class StudentGuardianBatchRepository(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val applications: ProgramWaitlistRepository,
) {
    fun lockRequest(
        expoId: UUID,
        owner: Long,
        key: String,
    ) {
        jdbc.queryForObject(
            "SELECT CAST(pg_advisory_xact_lock(46, hashtext(?)) AS text)",
            String::class.java,
            "$expoId:$owner:$key",
        )
    }

    fun replay(
        command: ApplyStudentGuardianBatchCommand,
        owner: Long,
    ): Pair<Boolean, StudentGuardianBatchReceipt>? =
        jdbc
            .query(
                "SELECT command = CAST(? AS jsonb), receipt FROM tb_program_guardian_batch " +
                    "WHERE expo_id = ? AND representative_id = ? AND idempotency_key = ?",
                { rs, _ -> rs.getBoolean(1) to mapper.readValue(rs.getString(2), StudentGuardianBatchReceipt::class.java) },
                snapshot(command),
                command.key.expoId,
                owner,
                command.idempotencyKey,
            ).singleOrNull()

    private fun snapshot(command: ApplyStudentGuardianBatchCommand): String =
        mapper.writeValueAsString(
            mapOf("key" to command.key, "request" to command.request) +
                if (command.onsite) mapOf("admissionRoute" to "ONSITE") else emptyMap(),
        )

    fun save(
        command: ApplyStudentGuardianBatchCommand,
        owner: Long,
        receipt: StudentGuardianBatchReceipt,
    ) {
        jdbc.update(
            "INSERT INTO tb_program_guardian_batch " +
                "(id, expo_id, program_type, program_id, representative_id, idempotency_key, command, receipt) " +
                "VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))",
            receipt.batchId,
            command.key.expoId,
            command.key.programType.name,
            command.key.programId,
            owner,
            command.idempotencyKey,
            snapshot(command),
            mapper.writeValueAsString(receipt),
        )
    }

    fun addMember(
        batchId: UUID,
        applicationId: UUID,
        guardian: Boolean,
    ) {
        jdbc.update(
            "INSERT INTO tb_program_guardian_member VALUES (?, ?, ?)",
            applicationId,
            batchId,
            if (guardian) "GUARDIAN" else "STUDENT",
        )
        jdbc.update(
            "UPDATE tb_program_session_application SET created_at = (SELECT created_at FROM tb_program_guardian_batch WHERE id = ?) WHERE id = ?",
            batchId,
            applicationId,
        )
    }

    fun link(
        student: UUID,
        guardian: UUID,
    ) {
        jdbc.update("INSERT INTO tb_program_guardian_link VALUES (?, ?)", student, guardian)
    }

    fun batchId(applicationId: UUID): UUID? =
        jdbc
            .query(
                "SELECT batch_id FROM tb_program_guardian_member WHERE application_id = ?",
                { rs, _ -> rs.getObject(1, UUID::class.java) },
                applicationId,
            ).singleOrNull()

    fun members(batchId: UUID): List<ProgramWaitlistRepository.Entry> =
        jdbc
            .query(
                "SELECT application_id FROM tb_program_guardian_member WHERE batch_id = ? ORDER BY application_id",
                { rs, _ -> rs.getObject(1, UUID::class.java) },
                batchId,
            ).mapNotNull(applications::entry)

    fun active(
        key: ProgramSessionKey,
        participantId: Long,
    ): ProgramWaitlistRepository.Entry? =
        jdbc
            .query(
                "SELECT id FROM tb_program_session_application WHERE expo_id = ? AND program_type = ? AND program_id = ? " +
                    "AND participant_id = ? AND status <> 'CANCELLED'",
                { rs, _ -> rs.getObject(1, UUID::class.java) },
                key.expoId,
                key.programType.name,
                key.programId,
                participantId,
            ).singleOrNull()
            ?.let(applications::entry)

    fun confirmedPreregister(
        key: ProgramSessionKey,
        participantId: Long,
    ): Boolean =
        jdbc
            .query(
                "SELECT id FROM tb_preregister_application WHERE expo_id = ? AND participant_id = ? AND status = 'CONFIRMED' FOR SHARE",
                { rs, _ -> rs.getLong(1) },
                key.expoId,
                participantId,
            ).isNotEmpty()

    fun guardian(student: UUID): UUID? =
        jdbc
            .query(
                "SELECT guardian_application_id FROM tb_program_guardian_link WHERE student_application_id = ?",
                { rs, _ -> rs.getObject(1, UUID::class.java) },
                student,
            ).singleOrNull()

    fun students(guardian: UUID): List<ProgramWaitlistRepository.Entry> =
        jdbc
            .query(
                "SELECT student_application_id FROM tb_program_guardian_link WHERE guardian_application_id = ?",
                { rs, _ -> rs.getObject(1, UUID::class.java) },
                guardian,
            ).mapNotNull(applications::entry)
            .filter { it.status != "CANCELLED" }

    fun links(entries: List<ProgramWaitlistRepository.Entry>): List<StudentGuardianLink> =
        entries.mapNotNull { entry ->
            guardian(entry.id)?.let { id ->
                val guardian = requireNotNull(applications.entry(id))
                StudentGuardianLink(entry.participantId, guardian.participantId)
            }
        }

    fun representative(batchId: UUID): Long? =
        jdbc
            .query("SELECT representative_id FROM tb_program_guardian_batch WHERE id = ?", { rs, _ -> rs.getLong(1) }, batchId)
            .singleOrNull()

    fun channel(id: UUID): String? =
        jdbc.queryForObject("SELECT channel FROM tb_program_session_application WHERE id = ?", String::class.java, id)

    fun definition(id: UUID): ProgramSessionDefinition =
        mapper.readValue(
            jdbc.queryForObject(
                "SELECT definition_snapshot::text FROM tb_program_session_application WHERE id = ?",
                String::class.java,
                id,
            ),
            ProgramSessionDefinition::class.java,
        )
}
