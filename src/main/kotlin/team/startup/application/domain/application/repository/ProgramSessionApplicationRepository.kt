package team.startup.application.domain.application.repository

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.ApplyProgramSessionCommand
import team.startup.application.domain.application.presentation.dto.ProgramAdmissionCapacity
import team.startup.application.domain.application.presentation.dto.ProgramAdmissionSeats
import team.startup.application.domain.application.presentation.dto.ProgramFormSnapshot
import team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition
import team.startup.application.domain.application.presentation.dto.ProgramSessionReceipt
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.service.impl.RegistrationGateway
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.UUID

@Repository
class ProgramSessionApplicationRepository(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val attribution: LegacyTraineeAttributionRepository,
    private val gateway: RegistrationGateway,
) {
    fun lockParticipant(participantId: Long) {
        lock(43, participantId.toString())
    }

    fun tryLockParticipant(participantId: Long): Boolean =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(43, hashtext(?))",
                Boolean::class.java,
                participantId.toString(),
            ),
        )

    fun lockSchedule(
        expoId: String,
        changing: Boolean = false,
    ) {
        val function = if (changing) "pg_advisory_xact_lock" else "pg_advisory_xact_lock_shared"
        jdbc.queryForObject("SELECT CAST($function(46, hashtext(?)) AS text)", String::class.java, UUID.fromString(expoId).toString())
    }

    fun activeParticipants(definition: ProgramSessionDefinition): List<Long> =
        jdbc.query(
            "SELECT DISTINCT participant_id FROM tb_program_session_application " +
                "WHERE expo_id = ? AND program_type = ? AND program_id = ? AND status IN ('CONFIRMED', 'WAITING') " +
                "UNION SELECT l.participant_id FROM tb_legacy_trainee_attribution l " +
                "JOIN tb_training_program_application t ON t.trainee_id = l.trainee_id " +
                "WHERE l.expo_id = ? AND ? = 'TRAINING' AND t.training_program_id = ?",
            { rs, _ -> rs.getLong(1) },
            UUID.fromString(definition.expoId),
            definition.programType.name,
            definition.id,
            UUID.fromString(definition.expoId),
            definition.programType.name,
            definition.id,
        )

    fun hasTimeConflict(
        definition: ProgramSessionDefinition,
        participantId: Long,
        excludingTraineeId: Long? = null,
    ): Boolean {
        if (attribution.pending(UUID.fromString(definition.expoId), participantId)) return true
        val start = sessionTime(definition.startedAt)
        val end = sessionTime(definition.endedAt)
        if (!start.isBefore(end)) throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "프로그램 운영 시간 구간이 올바르지 않습니다.")
        val existing =
            jdbc.query(
                "SELECT COALESCE(s.pending_definition, s.definition, a.definition_snapshot), false " +
                    "FROM tb_program_session_application a LEFT JOIN tb_program_session_state s " +
                    "ON s.expo_id = a.expo_id AND s.program_type = a.program_type AND s.program_id = a.program_id " +
                    "WHERE a.expo_id = ? AND a.participant_id = ? AND a.status IN ('CONFIRMED', 'WAITING') " +
                    "AND NOT (a.program_type = ? AND a.program_id = ?) " +
                    "AND (CAST(? AS bigint) IS NULL OR a.trainee_id IS DISTINCT FROM ?) " +
                    "UNION ALL SELECT COALESCE(s.pending_definition, s.definition, p.definition), s.pending_definition IS NULL " +
                    "FROM tb_legacy_trainee_attribution l " +
                    "JOIN tb_training_program_application t ON t.trainee_id = l.trainee_id " +
                    "JOIN tb_legacy_trainee_program p ON p.trainee_id = t.trainee_id AND p.program_id = t.training_program_id " +
                    "LEFT JOIN tb_program_session_state s ON s.expo_id = l.expo_id " +
                    "AND s.program_type = 'TRAINING' AND s.program_id = p.program_id " +
                    "WHERE l.expo_id = ? AND l.participant_id = ? " +
                    "AND NOT (? = 'TRAINING' AND t.training_program_id = ?) " +
                    "AND (CAST(? AS bigint) IS NULL OR l.trainee_id <> ?)",
                { rs, _ -> mapper.readValue(rs.getString(1), ProgramSessionDefinition::class.java) to rs.getBoolean(2) },
                UUID.fromString(definition.expoId),
                participantId,
                definition.programType.name,
                definition.id,
                excludingTraineeId,
                excludingTraineeId,
                UUID.fromString(definition.expoId),
                participantId,
                definition.programType.name,
                definition.id,
                excludingTraineeId,
                excludingTraineeId,
            )
        val legacyIds =
            existing
                .filter { it.second }
                .map { it.first.id }
                .distinct()
                .sorted()
        val current = gateway.programDefinitions(definition.expoId, ProgramSessionType.TRAINING, legacyIds, existing = true)
        if (current.map { it.id }.sorted() != legacyIds) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "귀속된 연수 신청의 현재 프로그램 정의를 모두 확인할 수 없습니다.")
        }
        val currentById = current.associateBy { it.id }
        return existing.any { (stored, _) ->
            val other = if (stored.programType == ProgramSessionType.TRAINING) currentById[stored.id] ?: stored else stored
            val otherStart = sessionTime(other.startedAt)
            val otherEnd = sessionTime(other.endedAt)
            if (!otherStart.isBefore(otherEnd)) throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "기존 프로그램 운영 시간이 올바르지 않습니다.")
            start.isBefore(otherEnd) && otherStart.isBefore(end)
        }
    }

    fun validateSessionTime(definition: ProgramSessionDefinition) {
        if (!sessionTime(definition.startedAt).isBefore(sessionTime(definition.endedAt))) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "프로그램 운영 시간 구간이 올바르지 않습니다.")
        }
    }

    fun requireAttributionReady(expoId: UUID) {
        attribution.requireReady(expoId)
    }

    fun validateAttributedStandardChange(
        expoId: String,
        participantId: Long,
        programIds: List<Long>,
        definitions: List<ProgramSessionDefinition>,
    ) {
        val expo =
            try {
                UUID.fromString(expoId)
            } catch (_: IllegalArgumentException) {
                return
            }
        attribution.requireReady(expo, participantId)
        if (!attribution.hasParticipant(expo, participantId)) return
        lockSchedule(expo.toString())
        if (definitions.map { it.id }.toSet() != programIds.toSet()) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "귀속된 참가자의 프로그램 정의가 필요합니다.")
        }
        if (definitions.any { hasTimeConflict(it, participantId) }) {
            throw ProgramApplicationConflictException("신청한 프로그램과 운영 시간이 겹칩니다.")
        }
    }

    fun validateAttributedTrainingChange(
        expoId: String,
        traineeId: Long,
        programIds: List<Long>,
        definitions: List<ProgramSessionDefinition>,
        replacing: Boolean = false,
    ) {
        val expo =
            try {
                UUID.fromString(expoId)
            } catch (_: IllegalArgumentException) {
                return
            }
        attribution.requireReady(expo, traineeId = traineeId)
        val participant = attribution.participant(expo, traineeId) ?: return
        if (!tryLockParticipant(participant) || jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock_shared(46, hashtext(?))",
                Boolean::class.java,
                expo.toString(),
            ) != true
        ) {
            throw ProgramApplicationConflictException("참가자 또는 일정 변경 중입니다. 재시도해 주세요.")
        }
        if (definitions.map { it.id }.toSet() != programIds.toSet()) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "귀속된 연수 신청의 프로그램 정의가 필요합니다.")
        }
        val currentIds =
            jdbc
                .query(
                    "SELECT training_program_id FROM tb_training_program_application WHERE trainee_id = ?",
                    { rs, _ -> rs.getLong(1) },
                    traineeId,
                ).toSet()
        val removingOnly = replacing && programIds.all { it in currentIds }
        if (!removingOnly) {
            definitions.forEach { definition ->
                validateSessionTime(definition)
                if (hasTimeConflict(definition, participant, traineeId.takeIf { replacing })) {
                    throw ProgramApplicationConflictException("신청한 프로그램과 운영 시간이 겹칩니다.")
                }
            }
            if (definitions.any { a ->
                    definitions.any { b ->
                        a.id != b.id && sessionTime(a.startedAt) < sessionTime(b.endedAt) &&
                            sessionTime(b.startedAt) < sessionTime(a.endedAt)
                    }
                }
            ) {
                throw ProgramApplicationConflictException("신청한 프로그램과 운영 시간이 겹칩니다.")
            }
        }
        attribution.remember(traineeId, definitions)
    }

    private fun sessionTime(value: String?): Instant {
        if (value.isNullOrBlank()) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "프로그램 운영 시간이 제공되지 않았습니다.")
        return try {
            OffsetDateTime.parse(value).toInstant()
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(value).atZone(ZoneId.of("Asia/Seoul")).toInstant()
            } catch (ex: DateTimeParseException) {
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "프로그램 운영 시간이 올바르지 않습니다.", ex)
            }
        }
    }

    fun lockGroups(definitions: List<ProgramSessionDefinition>) {
        definitions
            .map { "${it.expoId}:${it.programType}:${it.groupId}" }
            .distinct()
            .sorted()
            .forEach { lock(44, it) }
    }

    fun lockCapacityReads(definitions: List<ProgramSessionDefinition>) {
        lockGroups(definitions)
        definitions.map { it.id }.distinct().sorted().forEach {
            jdbc.queryForObject("SELECT CAST(pg_advisory_xact_lock_shared(?) AS text)", String::class.java, it)
        }
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
    ): Boolean = activeInGroup(UUID.fromString(command.expoId), command.programType, groupId, command.participantId)

    fun activeInGroup(
        expoId: UUID,
        type: ProgramSessionType,
        groupId: UUID,
        participantId: Long,
    ): Boolean =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM tb_program_session_application " +
                    "WHERE expo_id = ? AND program_type = ? AND group_id = ? AND participant_id = ? AND status IN ('CONFIRMED', 'WAITING'))",
                Boolean::class.java,
                expoId,
                type.name,
                groupId,
                participantId,
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

    fun admissionConfirmedCount(
        definition: ProgramSessionDefinition,
        route: String,
    ): Long =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM tb_program_session_application " +
                    "WHERE expo_id = ? AND program_type = ? AND program_id = ? AND status = 'CONFIRMED' AND admission_route = ?",
                Long::class.java,
                UUID.fromString(definition.expoId),
                definition.programType.name,
                definition.id,
                route,
            ),
        )

    fun unknownAdmissionCount(definition: ProgramSessionDefinition): Long =
        (
            confirmedCount(definition.programType, definition.id) -
                admissionConfirmedCount(definition, "PRE_REGISTRATION") - admissionConfirmedCount(definition, "ONSITE")
        ).coerceAtLeast(0)

    fun preRegistrationRemaining(definition: ProgramSessionDefinition): Long {
        val capacity = requireNotNull(requireNotNull(definition.policy).preRegistrationCapacity)
        return (capacity - admissionConfirmedCount(definition, "PRE_REGISTRATION") - unknownAdmissionCount(definition)).coerceAtLeast(0)
    }

    fun admissionSeats(definition: ProgramSessionDefinition): ProgramAdmissionSeats? {
        val policy = definition.policy?.takeIf { it.hasAdmissionCapacities() } ?: return null
        if (!policy.admissionCapacitiesValid()) throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "접수 경로별 정원 정의가 올바르지 않습니다.")
        val pre = admissionConfirmedCount(definition, "PRE_REGISTRATION")
        val onsite = admissionConfirmedCount(definition, "ONSITE")
        val unknown = unknownAdmissionCount(definition)
        return ProgramAdmissionSeats(
            ProgramAdmissionCapacity(
                requireNotNull(policy.preRegistrationCapacity),
                pre,
                (policy.preRegistrationCapacity - pre - unknown).coerceAtLeast(0),
            ),
            ProgramAdmissionCapacity(
                requireNotNull(policy.onsiteCapacity),
                onsite,
                (policy.onsiteCapacity - onsite - unknown).coerceAtLeast(0),
            ),
            unknown,
        )
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
                "definition_snapshot, form_id, form_version, questions_snapshot, answers_snapshot, channel, admission_route) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?)",
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
            if (definition.policy?.hasAdmissionCapacities() == true) "PRE_REGISTRATION" else null,
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
                val linked =
                    jdbc.queryForObject(
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
