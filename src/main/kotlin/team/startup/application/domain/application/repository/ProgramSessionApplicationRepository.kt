package team.startup.application.domain.application.repository

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Repository
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.ApplyProgramSessionCommand
import team.startup.application.domain.application.presentation.dto.ProgramAdmissionCapacity
import team.startup.application.domain.application.presentation.dto.ProgramAdmissionSeats
import team.startup.application.domain.application.presentation.dto.ProgramFormSnapshot
import team.startup.application.domain.application.presentation.dto.ProgramIneligibleReason
import team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition
import team.startup.application.domain.application.presentation.dto.ProgramSessionPolicy
import team.startup.application.domain.application.presentation.dto.ProgramSessionReceipt
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.service.impl.RegistrationGateway
import team.startup.application.domain.application.time.ExpoProgramTime
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

@Repository
class ProgramSessionApplicationRepository(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val attribution: LegacyTraineeAttributionRepository,
    private val gateway: RegistrationGateway,
) {
    // ponytail: 회차 신청 트랜잭션 전체에 고정 5초. 락 안의 User 연수자 호출(최대 8초)이 길어지면 대기자는 재시도로 빠진다.
    fun limitLockWait() {
        jdbc.execute("SET LOCAL lock_timeout = '5s'")
    }

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

    fun hasTimeConflict(
        definition: ProgramSessionDefinition,
        participantId: Long,
        excludingTraineeId: Long? = null,
        // #261: 현장 신청이 접수와 함께 취소할 본인 대기 행은 시간 충돌로 보지 않는다.
        excludingApplications: List<UUID> = emptyList(),
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
                    "AND a.id NOT IN (SELECT jsonb_array_elements_text(?::jsonb)::uuid) " +
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
                mapper.writeValueAsString(excludingApplications),
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
            ExpoProgramTime.parse(value).toInstant()
        } catch (ex: DateTimeParseException) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "프로그램 운영 시간이 올바르지 않습니다.", ex)
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
            ) + if (command.onsite) mapOf("admissionRoute" to "ONSITE") else emptyMap(),
        )

    // #197: 참가자의 확정 사전등록 회차 중 [startedAt, endedAt)가 프로그램 시간과 일부라도 겹치는 회차가 있어야 신청할 수 있다.
    // 회차 시간은 저장된 회차 정의에서 읽고 Expo는 부르지 않는다. 입장 허용 시간(leadMinutes)은 판정에 넣지 않는다.
    // 신청 경로는 확정 행을 FOR SHARE로 잡고, 읽기 전용 조회(일행 화면)는 잠그지 않는다. null이면 자격이 있다.
    // #261: 현장 신청은 requireOverlap = false로 확정 사전등록만 보고 회차 시간은 보지 않는다. 날짜는 현장 접수 기간이 막는다.
    fun preregisterIneligibility(
        definition: ProgramSessionDefinition,
        participantId: Long,
        lock: Boolean = true,
        requireOverlap: Boolean = true,
    ): ProgramIneligibleReason? {
        val sessions =
            jdbc.query(
                "SELECT s.definition::jsonb ->> 'startedAt', s.definition::jsonb ->> 'endedAt' FROM tb_preregister_application a " +
                    "JOIN tb_preregister_session_state s ON s.expo_id = a.expo_id AND s.session_id = a.session_id " +
                    "WHERE a.expo_id = ? AND a.participant_id = ? AND a.status = 'CONFIRMED'" + if (lock) " FOR SHARE OF a" else "",
                { rs, _ -> rs.getString(1) to rs.getString(2) },
                UUID.fromString(definition.expoId),
                participantId,
            )
        if (sessions.isEmpty()) return ProgramIneligibleReason.PREREGISTRATION_NOT_CONFIRMED
        if (!requireOverlap) return null
        val start = sessionTime(definition.startedAt)
        val end = sessionTime(definition.endedAt)
        val overlaps =
            sessions.any { (startedAt, endedAt) ->
                start.isBefore(sessionTime(endedAt)) && sessionTime(startedAt).isBefore(end)
            }
        return if (overlaps) null else ProgramIneligibleReason.SESSION_TIME_MISMATCH
    }

    fun activeInGroup(
        command: ApplyProgramSessionCommand,
        groupId: UUID,
        excluding: List<UUID> = emptyList(),
    ): Boolean = activeInGroup(UUID.fromString(command.expoId), command.programType, groupId, command.participantId, excluding)

    fun activeInGroup(
        expoId: UUID,
        type: ProgramSessionType,
        groupId: UUID,
        participantId: Long,
        excluding: List<UUID> = emptyList(),
    ): Boolean =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM tb_program_session_application " +
                    "WHERE expo_id = ? AND program_type = ? AND group_id = ? AND participant_id = ? " +
                    "AND status IN ('CONFIRMED', 'WAITING') " +
                    "AND id NOT IN (SELECT jsonb_array_elements_text(?::jsonb)::uuid))",
                Boolean::class.java,
                expoId,
                type.name,
                groupId,
                participantId,
                mapper.writeValueAsString(excluding),
            ),
        )

    // #261: 현장 신청이 취소하고 진행할 같은 묶음의 본인 대기 행. 학생·보호자 묶음 행은 연계 취소가 필요해 빼지 않는다.
    fun onsiteReplaceableWaiting(
        expoId: UUID,
        type: ProgramSessionType,
        groupId: UUID,
        participantId: Long,
    ): List<UUID> =
        jdbc.query(
            "SELECT a.id FROM tb_program_session_application a WHERE a.expo_id = ? AND a.program_type = ? AND a.group_id = ? " +
                "AND a.participant_id = ? AND a.status = 'WAITING' " +
                "AND NOT EXISTS(SELECT 1 FROM tb_program_guardian_member m WHERE m.application_id = a.id) ORDER BY a.id",
            { rs, _ -> rs.getObject(1, UUID::class.java) },
            expoId,
            type.name,
            groupId,
            participantId,
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

    // Waiting rows are always PRE_REGISTRATION, so seats held by active offers (#144) come out of the pre seats only.
    fun preRegistrationRemaining(
        definition: ProgramSessionDefinition,
        excludingOffer: UUID? = null,
    ): Long {
        val capacity = requireNotNull(requireNotNull(definition.policy).preRegistrationCapacity)
        return (
            capacity - admissionConfirmedCount(definition, "PRE_REGISTRATION") - unknownAdmissionCount(definition) -
                offeredSeats(definition, excludingOffer)
        ).coerceAtLeast(0)
    }

    fun offeredSeats(
        definition: ProgramSessionDefinition,
        excludingOffer: UUID? = null,
    ): Long =
        requireNotNull(
            jdbc.queryForObject(
                PromotionOfferRepository.PROGRAM_HELD,
                Long::class.java,
                UUID.fromString(definition.expoId),
                definition.programType.name,
                definition.id,
                null,
                null,
                excludingOffer,
                excludingOffer,
            ),
        )

    // Physical seats taken by confirmed rows plus offer holds; every capacity check and reconfiguration uses this.
    fun occupiedCount(definition: ProgramSessionDefinition): Long =
        confirmedCount(definition.programType, definition.id) + offeredSeats(definition)

    fun onsiteRemaining(definition: ProgramSessionDefinition): Long {
        val capacity = requireNotNull(requireNotNull(definition.policy).onsiteCapacity)
        return (capacity - admissionConfirmedCount(definition, "ONSITE") - unknownAdmissionCount(definition)).coerceAtLeast(0)
    }

    fun admissionSeats(definition: ProgramSessionDefinition): ProgramAdmissionSeats? {
        val policy = definition.policy?.takeIf { it.hasAdmissionCapacities() } ?: return null
        if (!policy.admissionCapacitiesValid()) throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "접수 경로별 정원 정의가 올바르지 않습니다.")
        return admissionSeats(
            policy,
            admissionConfirmedCount(definition, "PRE_REGISTRATION"),
            admissionConfirmedCount(definition, "ONSITE"),
            unknownAdmissionCount(definition),
            offeredSeats(definition),
        )
    }

    fun admissionSeats(
        policy: ProgramSessionPolicy,
        pre: Long,
        onsite: Long,
        unknown: Long,
        offered: Long,
    ) = ProgramAdmissionSeats(
        ProgramAdmissionCapacity(
            requireNotNull(policy.preRegistrationCapacity),
            pre,
            (policy.preRegistrationCapacity - pre - unknown - offered).coerceAtLeast(0),
        ),
        ProgramAdmissionCapacity(
            requireNotNull(policy.onsiteCapacity),
            onsite,
            (policy.onsiteCapacity - onsite - unknown).coerceAtLeast(0),
        ),
        unknown,
        offeredSeats = offered,
        waitingEnabled = policy.waitingCapacity > 0,
        onsiteAdmissionEnabled = policy.onsiteCapacity > 0 && policy.admissionWindow(onsite = true) != null,
    )

    data class SeatUsage(
        val confirmed: Long = 0,
        val waiting: Int = 0,
        val preRegistration: Long = 0,
        val onsite: Long = 0,
        val offered: Long = 0,
    ) {
        val unknown: Long get() = (confirmed - preRegistration - onsite).coerceAtLeast(0)
    }

    /**
     * 공개 정원 요약(#331)용. confirmedCount·admissionConfirmedCount·offeredSeats와 ProgramWaitlistRepository.waitingCount를
     * 회차마다 부르지 않고 같은 조건으로 한 번에 센다. 직군별(channel) 수는 공개하지 않아 세지 않는다.
     */
    fun seatUsage(
        expoId: UUID,
        type: ProgramSessionType,
        programIds: List<Long>,
    ): Map<Long, SeatUsage> {
        if (programIds.isEmpty()) return emptyMap()
        val ids = programIds.distinct().toTypedArray()
        val table = if (type == ProgramSessionType.STANDARD) "tb_standard_program_application" else "tb_training_program_application"
        val column = if (type == ProgramSessionType.STANDARD) "standard_program_id" else "training_program_id"
        val usage = programIds.associateWith { SeatUsage() }.toMutableMap()
        jdbc.query(
            "SELECT $column, count(*) FROM $table WHERE $column = ANY(?) GROUP BY $column",
            RowCallbackHandler { rs -> usage.computeIfPresent(rs.getLong(1)) { _, it -> it.copy(confirmed = rs.getLong(2)) } },
            ids,
        )
        jdbc.query(
            "SELECT program_id, status, admission_route, count(*) FROM tb_program_session_application " +
                "WHERE expo_id = ? AND program_type = ? AND program_id = ANY(?) AND status IN ('CONFIRMED', 'WAITING') " +
                "AND (status <> 'WAITING' OR EXISTS (" +
                "SELECT 1 FROM tb_preregister_application p WHERE p.expo_id = tb_program_session_application.expo_id " +
                "AND p.participant_id = tb_program_session_application.participant_id AND p.status = 'CONFIRMED')) " +
                "GROUP BY program_id, status, admission_route",
            RowCallbackHandler { rs ->
                val route = rs.getString(3)
                val count = rs.getLong(4)
                usage.computeIfPresent(rs.getLong(1)) { _, it ->
                    when {
                        rs.getString(2) == "WAITING" -> it.copy(waiting = it.waiting + count.toInt())
                        route == "PRE_REGISTRATION" -> it.copy(preRegistration = it.preRegistration + count)
                        route == "ONSITE" -> it.copy(onsite = it.onsite + count)
                        else -> it
                    }
                }
            },
            expoId,
            type.name,
            ids,
        )
        jdbc.query(
            "SELECT o.program_id, coalesce(sum(o.seats), 0) FROM tb_promotion_offer o " +
                "JOIN tb_program_session_application h ON h.id = o.application_id AND h.status = 'WAITING' " +
                "WHERE o.expo_id = ? AND o.program_type = ? AND o.program_id = ANY(?) AND o.state IN ${PromotionOfferRepository.ACTIVE} " +
                "GROUP BY o.program_id",
            RowCallbackHandler { rs -> usage.computeIfPresent(rs.getLong(1)) { _, it -> it.copy(offered = rs.getLong(2)) } },
            expoId,
            type.name,
            ids,
        )
        return usage
    }

    fun knownGroups(
        expoId: String,
        type: ProgramSessionType,
        personId: Long,
    ): Map<Long, UUID> {
        if (type == ProgramSessionType.STANDARD) {
            return jdbc
                .query(
                    "SELECT program_id, group_id FROM tb_program_session_application " +
                        "WHERE expo_id = ? AND program_type = ? AND participant_id = ? AND status IN ('CONFIRMED', 'WAITING')",
                    { rs, _ -> rs.getLong(1) to rs.getObject(2, UUID::class.java) },
                    UUID.fromString(expoId),
                    type.name,
                    personId,
                ).toMap()
        }
        // 연수 대기 행은 trainee_id가 없으므로 로컬에 기록된 연수자-참가자 연결로 대기 행도 찾는다(#146).
        // ponytail: 확정 이력·귀속이 없는 연수자는 로컬에서 참가자를 알 수 없어 대기 행을 보지 못한다.
        val expo = UUID.fromString(expoId)
        return jdbc
            .query(
                "SELECT program_id, group_id FROM tb_program_session_application " +
                    "WHERE expo_id = ? AND program_type = 'TRAINING' AND (" +
                    "(trainee_id = ? AND status IN ('CONFIRMED', 'WAITING')) OR (status = 'WAITING' AND participant_id IN (" +
                    "SELECT participant_id FROM tb_program_session_application " +
                    "WHERE expo_id = ? AND program_type = 'TRAINING' AND trainee_id = ? " +
                    "UNION SELECT participant_id FROM tb_legacy_trainee_attribution WHERE expo_id = ? AND trainee_id = ?)))",
                { rs, _ -> rs.getLong(1) to rs.getObject(2, UUID::class.java) },
                expo,
                personId,
                expo,
                personId,
                expo,
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
            when {
                definition.policy?.hasAdmissionCapacities() != true -> null
                command.onsite -> "ONSITE"
                else -> "PRE_REGISTRATION"
            },
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

    // Expo purge (#211). Receipt sequences stay so receipt numbers are never reused.
    fun purge(expoId: UUID) {
        val applications = "SELECT id FROM tb_program_session_application WHERE expo_id = ?"
        jdbc.update("DELETE FROM tb_promotion_offer WHERE expo_id = ?", expoId)
        jdbc.update("DELETE FROM tb_program_guardian_link WHERE student_application_id IN ($applications)", expoId)
        jdbc.update("DELETE FROM tb_program_guardian_member WHERE application_id IN ($applications)", expoId)
        listOf("tb_program_session_receipt", "tb_program_session_history", "tb_program_session_event").forEach {
            jdbc.update("DELETE FROM $it WHERE application_id IN ($applications)", expoId)
        }
        listOf(
            "tb_program_guardian_batch",
            "tb_program_session_application",
            "tb_program_session_state",
            "tb_program_change_receipt",
            "tb_report_application_snapshot",
        ).forEach {
            jdbc.update("DELETE FROM $it WHERE expo_id = ?", expoId)
        }
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
