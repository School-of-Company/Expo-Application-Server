package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

// #308: 신청 결과 문자 outbox. 상태 변경과 같은 트랜잭션에서만 쌓는다. delivered_at은 Kafka ACK이지 문자 수신이 아니다.
@Repository
class ApplicationNotificationRepository(
    private val jdbc: JdbcTemplate,
) {
    data class Row(
        val id: Long,
        val dedupeKey: String,
        val batchKey: String,
        val type: String,
        val expoId: UUID,
        val applicationId: UUID?,
        val preregisterApplicationId: Long?,
        val requestId: String?,
        val sessionId: Long?,
        val participantId: Long?,
        val result: String?,
        val cancelledBy: String?,
        val occurredAt: Instant,
    )

    data class Batch(
        val batchKey: String,
        val type: String,
        val expoId: UUID,
    )

    // 같은 dedupeKey는 한 번만 쌓인다. 호출자의 트랜잭션에 참여하므로 롤백되면 함께 사라진다.
    fun record(
        dedupeKey: String,
        type: String,
        expoId: UUID,
        applicationId: UUID? = null,
        preregisterApplicationId: Long? = null,
        requestId: String? = null,
        sessionId: Long? = null,
        participantId: Long? = null,
        result: String? = null,
        cancelledBy: String? = null,
    ) {
        jdbc.update(
            "INSERT INTO tb_application_notification (dedupe_key, type, expo_id, application_id, preregister_application_id, " +
                "request_id, session_id, participant_id, result, cancelled_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (dedupe_key) DO NOTHING",
            dedupeKey,
            type,
            expoId,
            applicationId,
            preregisterApplicationId,
            requestId,
            sessionId,
            participantId,
            result,
            cancelledBy,
        )
    }

    fun due(cutoff: Instant): List<Batch> =
        jdbc.query(
            "SELECT batch_key, type, expo_id FROM tb_application_notification " +
                "WHERE delivered_at IS NULL AND skipped_at IS NULL AND occurred_at >= ? AND next_attempt_at <= clock_timestamp() " +
                "GROUP BY batch_key, type, expo_id ORDER BY min(next_attempt_at), min(id) LIMIT 100",
            { rs, _ -> Batch(rs.getString(1), rs.getString(2), rs.getObject(3, UUID::class.java)) },
            Timestamp.from(cutoff),
        )

    fun pending(
        scoped: JdbcTemplate,
        batch: Batch,
        cutoff: Instant,
    ): List<Row> =
        scoped.query(
            "SELECT id, dedupe_key, batch_key, type, expo_id, application_id, preregister_application_id, request_id, session_id, " +
                "participant_id, result, cancelled_by, occurred_at FROM tb_application_notification " +
                "WHERE batch_key = ? AND type = ? AND expo_id = ? AND delivered_at IS NULL AND skipped_at IS NULL " +
                "AND occurred_at >= ? AND next_attempt_at <= clock_timestamp() ORDER BY id",
            { rs, _ ->
                Row(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getObject(5, UUID::class.java),
                    rs.getObject(6, UUID::class.java),
                    rs.getObject(7, Long::class.javaObjectType),
                    rs.getString(8),
                    rs.getObject(9, Long::class.javaObjectType),
                    rs.getObject(10, Long::class.javaObjectType),
                    rs.getString(11),
                    rs.getString(12),
                    rs.getTimestamp(13).toInstant(),
                )
            },
            batch.batchKey,
            batch.type,
            batch.expoId,
            Timestamp.from(cutoff),
        )

    fun delivered(
        scoped: JdbcTemplate,
        ids: Collection<Long>,
    ) {
        if (ids.isEmpty()) return
        NamedParameterJdbcTemplate(scoped).update(
            "UPDATE tb_application_notification SET delivered_at = clock_timestamp() WHERE id IN (:ids) AND delivered_at IS NULL",
            mapOf("ids" to ids),
        )
    }

    fun skipped(
        scoped: JdbcTemplate,
        ids: Collection<Long>,
        reason: String,
    ) {
        if (ids.isEmpty()) return
        NamedParameterJdbcTemplate(scoped).update(
            "UPDATE tb_application_notification SET skipped_at = clock_timestamp(), skip_reason = :reason " +
                "WHERE id IN (:ids) AND skipped_at IS NULL",
            mapOf("ids" to ids, "reason" to reason),
        )
    }

    fun deferred(
        scoped: JdbcTemplate,
        ids: Collection<Long>,
    ) {
        if (ids.isEmpty()) return
        NamedParameterJdbcTemplate(scoped).update(
            "UPDATE tb_application_notification SET attempts = LEAST(attempts + 1, 30), " +
                "next_attempt_at = clock_timestamp() + LEAST(300, power(2, LEAST(attempts, 8))) * interval '1 second' WHERE id IN (:ids)",
            mapOf("ids" to ids),
        )
    }
}
