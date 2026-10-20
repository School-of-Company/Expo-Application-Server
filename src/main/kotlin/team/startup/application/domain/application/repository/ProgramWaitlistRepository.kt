package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.exception.ProgramDefinitionMismatchException
import team.startup.application.domain.application.presentation.dto.ApplicationActorType
import team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition
import team.startup.application.domain.application.presentation.dto.ProgramSessionKey
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.time.ExpoProgramTime
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class ProgramWaitlistRepository(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) {
    data class Entry(
        val id: UUID,
        val participantId: Long,
        val traineeId: Long?,
        val status: String,
        val key: ProgramSessionKey,
        val questions: JsonNode,
        val answers: JsonNode,
    )

    private val entryMapper =
        RowMapper { rs, _ ->
            Entry(
                rs.getObject("id", UUID::class.java),
                rs.getLong("participant_id"),
                rs.getLong("trainee_id").let { if (rs.wasNull()) null else it },
                rs.getString("status"),
                ProgramSessionKey(
                    rs.getObject("expo_id", UUID::class.java),
                    ProgramSessionType.valueOf(rs.getString("program_type")),
                    rs.getLong("program_id"),
                ),
                mapper.readTree(rs.getString("questions_snapshot")),
                mapper.readTree(rs.getString("answers_snapshot")),
            )
        }

    fun entry(id: UUID): Entry? =
        jdbc
            .query(
                "SELECT id, participant_id, trainee_id, status, expo_id, program_type, program_id, questions_snapshot, answers_snapshot " +
                    "FROM tb_program_session_application WHERE id = ?",
                entryMapper,
                id,
            ).singleOrNull()

    // A null channel means the whole session; channel sessions never hold NULL-channel active rows (see PrepareProgramChange).
    // Promotion skips participants without a confirmed preregistration, so stale WAITING rows neither promote nor block the queue.
    fun waiting(
        definition: ProgramSessionDefinition,
        limit: Int,
        channel: String? = null,
    ): List<Entry> =
        jdbc.query(
            "SELECT id, participant_id, trainee_id, status, expo_id, program_type, program_id, questions_snapshot, answers_snapshot " +
                "FROM tb_program_session_application " +
                "WHERE expo_id = ? AND program_type = ? AND program_id = ? AND status = 'WAITING' " +
                "AND (CAST(? AS text) IS NULL OR channel = ?) " +
                "AND EXISTS (SELECT 1 FROM tb_preregister_application p WHERE p.expo_id = tb_program_session_application.expo_id " +
                "AND p.participant_id = tb_program_session_application.participant_id AND p.status = 'CONFIRMED') " +
                // A row (or guardian batch) already holding seats for an offer is no longer waiting for one (#144).
                "AND NOT EXISTS (SELECT 1 FROM tb_promotion_offer o WHERE o.state IN ${PromotionOfferRepository.ACTIVE} " +
                "AND (o.application_id = tb_program_session_application.id OR o.batch_id = (SELECT m.batch_id " +
                "FROM tb_program_guardian_member m WHERE m.application_id = tb_program_session_application.id))) " +
                "ORDER BY created_at, id LIMIT ?",
            entryMapper,
            UUID.fromString(definition.expoId),
            definition.programType.name,
            definition.id,
            channel,
            channel,
            limit,
        )

    fun confirmedPreregister(entry: Entry): Boolean =
        jdbc
            .queryForList(
                "SELECT 1 FROM tb_preregister_application WHERE expo_id = ? AND participant_id = ? AND status = 'CONFIRMED' FOR SHARE",
                entry.key.expoId,
                entry.participantId,
            ).isNotEmpty()

    fun channel(id: UUID): String? =
        jdbc.query("SELECT channel FROM tb_program_session_application WHERE id = ?", { rs, _ -> rs.getString(1) }, id).singleOrNull()

    fun waitingCount(
        definition: ProgramSessionDefinition,
        channel: String? = null,
    ): Int = count(definition, "WAITING", channel)

    fun confirmedCount(
        definition: ProgramSessionDefinition,
        channel: String,
    ): Int = count(definition, "CONFIRMED", channel)

    private fun count(
        definition: ProgramSessionDefinition,
        status: String,
        channel: String?,
    ): Int =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM tb_program_session_application " +
                    "WHERE expo_id = ? AND program_type = ? AND program_id = ? AND status = ? " +
                    "AND (CAST(? AS text) IS NULL OR channel = ?) " +
                    "AND (status <> 'WAITING' OR EXISTS (" +
                    "SELECT 1 FROM tb_preregister_application p WHERE p.expo_id = tb_program_session_application.expo_id " +
                    "AND p.participant_id = tb_program_session_application.participant_id AND p.status = 'CONFIRMED'))",
                Int::class.java,
                UUID.fromString(definition.expoId),
                definition.programType.name,
                definition.id,
                status,
                channel,
                channel,
            ),
        )

    fun checkDefinition(definition: ProgramSessionDefinition) {
        jdbc
            .query(
                "SELECT definition, pending_definition, deleted FROM tb_program_session_state " +
                    "WHERE expo_id = ? AND program_type = ? AND program_id = ?",
                { rs, _ ->
                    Triple(
                        mapper.readValue(rs.getString(1), ProgramSessionDefinition::class.java),
                        rs.getString(2)?.let { mapper.readValue(it, ProgramSessionDefinition::class.java) },
                        rs.getBoolean(3),
                    )
                },
                UUID.fromString(definition.expoId),
                definition.programType.name,
                definition.id,
            ).singleOrNull()
            ?.let { (current, pending, deleted) ->
                val remote = definition.copy(pendingRevision = null)
                // 신청(lockDefinition)과 같이 정책 없는 회차는 Expo가 승인 요청 없이 바꾼 제목·시간·장소를 받아들인다.
                val policyless = pending == null && current.policy == null && remote.policy == null && remote.revision >= current.revision
                if (deleted || (!policyless && listOfNotNull(current, pending).none { remote.matchesApproved(it) })) {
                    throw ProgramDefinitionMismatchException(definition.programType, definition.id)
                }
            }
    }

    /**
     * Expo V37은 기존 정책을 승인 요청 없이 "전체 정원 = 사전 정원, 현장 0"으로 바꿨다.
     * 정원 분리·직업 채널이 없는 예전 승인 정의는 그 변환 결과와 같은 정의로 본다. 직업 채널이 있던 정의는 자동 전환하지 않는다.
     */
    private fun ProgramSessionDefinition.matchesApproved(approved: ProgramSessionDefinition): Boolean {
        val remote = withComparableTimes()
        val stored = approved.withComparableTimes()
        if (remote == stored) return true
        val policy = stored.policy ?: return false
        if (policy.channels != null || policy.preRegistrationCapacity != null || policy.onsiteCapacity != null) return false
        return remote == stored.copy(policy = policy.copy(preRegistrationCapacity = policy.capacity, onsiteCapacity = 0))
    }

    // #251: Expo는 같은 회차 시간을 변경 승인 요청·현재 회차 페이지에는 저장값 "yyyy-MM-ddTHH:mm"으로,
    // 연수 일괄 조회에는 "yyyy-MM-dd HH:mm"으로 보낸다. 표기만 다른 같은 시각은 같은 정의로 보고, 읽을 수 없는 값은 그대로 비교한다.
    private fun ProgramSessionDefinition.withComparableTimes() =
        copy(startedAt = startedAt?.let(::comparableTime), endedAt = endedAt?.let(::comparableTime))

    private fun comparableTime(value: String): String =
        runCatching { ExpoProgramTime.parse(value).toInstant().toString() }.getOrDefault(value)

    fun observe(definition: ProgramSessionDefinition) {
        jdbc.update(
            "INSERT INTO tb_program_session_state (expo_id, program_type, program_id, definition) VALUES (?, ?, ?, CAST(? AS jsonb)) ON CONFLICT DO NOTHING",
            UUID.fromString(definition.expoId),
            definition.programType.name,
            definition.id,
            mapper.writeValueAsString(definition),
        )
        val state =
            jdbc
                .query(
                    "SELECT definition, pending_definition, deleted FROM tb_program_session_state WHERE expo_id = ? AND program_type = ? AND program_id = ?",
                    {
                        rs,
                        _,
                        ->
                        Triple(
                            mapper.readValue(rs.getString(1), ProgramSessionDefinition::class.java),
                            rs.getString(2)?.let {
                                mapper.readValue(it, ProgramSessionDefinition::class.java)
                            },
                            rs.getBoolean(3),
                        )
                    },
                    UUID.fromString(definition.expoId),
                    definition.programType.name,
                    definition.id,
                ).single()
        if (state.third) conflict("삭제된 프로그램입니다.")
        if (definition.pendingRevision != null) conflict("프로그램 정책 변경 중입니다.")
        if (state.second == null && state.first.policy == null && definition.policy == null) {
            if (definition.revision < state.first.revision) conflict("프로그램 정의가 변경되었습니다.")
            if (definition != state.first) {
                jdbc.update(
                    "UPDATE tb_program_session_state SET definition = CAST(? AS jsonb) WHERE expo_id = ? AND program_type = ? AND program_id = ?",
                    mapper.writeValueAsString(definition),
                    UUID.fromString(definition.expoId),
                    definition.programType.name,
                    definition.id,
                )
            }
            return
        }
        val expected = state.second ?: state.first
        if (!definition.matchesApproved(expected)) conflict("프로그램 정책 변경 중이거나 승인되지 않은 정의입니다.")
        if (state.second !=
            null
        ) {
            jdbc.update(
                "UPDATE tb_program_session_state SET definition = pending_definition, pending_definition = NULL, pending_change_id = NULL WHERE expo_id = ? AND program_type = ? AND program_id = ?",
                UUID.fromString(definition.expoId),
                definition.programType.name,
                definition.id,
            )
        }
    }

    fun stage(
        previous: ProgramSessionDefinition,
        desired: ProgramSessionDefinition?,
        changeId: UUID,
    ) {
        observe(previous)
        jdbc.update(
            "UPDATE tb_program_session_state SET pending_definition = CAST(? AS jsonb), pending_change_id = ?, deleted = ? WHERE expo_id = ? AND program_type = ? AND program_id = ?",
            desired?.let(mapper::writeValueAsString),
            changeId,
            desired == null,
            UUID.fromString(previous.expoId),
            previous.programType.name,
            previous.id,
        )
    }

    fun changeReplay(
        expoId: UUID,
        changeId: UUID,
        payload: String,
    ): Boolean {
        jdbc
            .query(
                "SELECT payload = CAST(? AS jsonb) FROM tb_program_change_receipt WHERE expo_id = ? AND change_id = ?",
                { rs, _ -> rs.getBoolean(1) },
                payload,
                expoId,
                changeId,
            ).singleOrNull()
            ?.let {
                if (!it) conflict("같은 변경 ID에 다른 정의가 있습니다.")
                return true
            }
        return false
    }

    fun lockChange(expoId: UUID) {
        jdbc.queryForObject("SELECT CAST(pg_advisory_xact_lock(45, hashtext(?)) AS text)", String::class.java, expoId.toString())
    }

    fun saveChange(
        expoId: UUID,
        changeId: UUID,
        payload: String,
    ) {
        jdbc.update("INSERT INTO tb_program_change_receipt VALUES (?, ?, CAST(? AS jsonb))", expoId, changeId, payload)
    }

    fun transition(
        id: UUID,
        status: String,
        actorType: ApplicationActorType,
        traineeId: Long? = null,
        actorId: Long? = null,
        actorParticipantId: Long? = null,
    ) {
        jdbc.update(
            "INSERT INTO tb_program_session_history " +
                "(application_id, status, actor_type, actor_id, actor_participant_id, previous_status, previous_status_recorded) " +
                "SELECT id, ?, ?, ?, ?, status, TRUE FROM tb_program_session_application WHERE id = ?",
            status,
            actorType.name,
            actorId,
            actorParticipantId,
            id,
        )
        jdbc.update(
            "UPDATE tb_program_session_application SET status = ?, trainee_id = COALESCE(?, trainee_id) WHERE id = ?",
            status,
            traineeId,
            id,
        )
        event(id, status)
    }

    fun event(
        id: UUID,
        status: String,
    ) {
        jdbc.update("INSERT INTO tb_program_session_event (application_id, status) VALUES (?, ?) ON CONFLICT DO NOTHING", id, status)
    }

    fun activeCount(definition: ProgramSessionDefinition): Int =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM tb_program_session_application WHERE expo_id = ? AND program_type = ? AND program_id = ? AND status <> 'CANCELLED'",
                Int::class.java,
                UUID.fromString(definition.expoId),
                definition.programType.name,
                definition.id,
            ),
        )

    fun keys(now: Instant): List<ProgramSessionKey> =
        jdbc.query(
            "SELECT s.expo_id, s.program_type, s.program_id FROM tb_program_session_state s WHERE NOT s.deleted " +
                "AND (s.pending_change_id IS NOT NULL OR ((s.definition #>> '{policy,applicationEndedAt}')::timestamptz > ? " +
                "AND (s.definition #>> '{policy,promotionEndedAt}')::timestamptz > ? " +
                "AND EXISTS (SELECT 1 FROM tb_program_session_application a " +
                "WHERE a.expo_id = s.expo_id AND a.program_type = s.program_type AND a.program_id = s.program_id AND a.status = 'WAITING')))",
            { rs, _ -> ProgramSessionKey(rs.getObject(1, UUID::class.java), ProgramSessionType.valueOf(rs.getString(2)), rs.getLong(3)) },
            Timestamp.from(now),
            Timestamp.from(now),
        )

    private fun conflict(message: String): Nothing = throw ProgramApplicationConflictException(message)
}
