package team.startup.application.domain.application.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

// Expo `expo.expo.changed`의 로컬 복사본에서 읽는 쪽. 삭제됐거나 삭제 중인 박람회는 없는 것으로 돌려 동기 조회로 넘긴다.
interface ExpoProjectionStore {
    fun expo(expoId: UUID): ExpoProjectionRepository.Expo?
}

@Repository
class ExpoProjectionRepository(
    private val jdbc: JdbcTemplate,
) : ExpoProjectionStore {
    data class Expo(
        val title: String,
        val startedDay: String,
        val finishedDay: String,
        val participantPhoneVerificationRequired: Boolean,
        val participantPhoneVerificationRevision: Long,
        val deleting: Boolean,
    )

    data class Session(
        val id: Long,
        val cancelEndedAt: Instant?,
    )

    // 메시지 하나가 그 박람회의 전체 상태다. expo가 null이면 tombstone이다.
    data class Change(
        val eventId: UUID,
        val version: Long,
        val expoId: UUID,
        val expo: Expo?,
        val sessions: List<Session>,
    )

    // 저장된 version 이하(중복·재전송·늦게 온 옛 메시지)면 아무것도 바꾸지 않고 false다. 호출자의 트랜잭션에 참여한다.
    fun apply(change: Change): Boolean {
        val expo = change.expo
        val updated =
            jdbc.update(
                "INSERT INTO tb_expo_projection (expo_id, version, event_id, deleted, title, started_day, finished_day, " +
                    "phone_verification_required, phone_verification_revision, deleting, applied_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP) " +
                    "ON CONFLICT (expo_id) DO UPDATE SET version = EXCLUDED.version, event_id = EXCLUDED.event_id, " +
                    "deleted = EXCLUDED.deleted, title = EXCLUDED.title, started_day = EXCLUDED.started_day, " +
                    "finished_day = EXCLUDED.finished_day, phone_verification_required = EXCLUDED.phone_verification_required, " +
                    "phone_verification_revision = EXCLUDED.phone_verification_revision, deleting = EXCLUDED.deleting, " +
                    "applied_at = EXCLUDED.applied_at WHERE tb_expo_projection.version < EXCLUDED.version",
                change.expoId,
                change.version,
                change.eventId,
                expo == null,
                expo?.title,
                expo?.startedDay,
                expo?.finishedDay,
                expo?.participantPhoneVerificationRequired,
                expo?.participantPhoneVerificationRevision,
                expo?.deleting,
            )
        if (updated == 0) return false
        jdbc.update("DELETE FROM tb_expo_projection_session WHERE expo_id = ?", change.expoId)
        if (expo != null && change.sessions.isNotEmpty()) {
            jdbc.batchUpdate(
                "INSERT INTO tb_expo_projection_session (expo_id, session_id, cancel_ended_at) VALUES (?, ?, ?)",
                change.sessions.map { arrayOf<Any?>(change.expoId, it.id, it.cancelEndedAt?.let(Timestamp::from)) },
            )
        }
        return true
    }

    fun version(expoId: UUID): Long? =
        jdbc.query("SELECT version FROM tb_expo_projection WHERE expo_id = ?", { rs, _ -> rs.getLong(1) }, expoId).singleOrNull()

    override fun expo(expoId: UUID): Expo? =
        jdbc
            .query(
                "SELECT title, started_day, finished_day, phone_verification_required, phone_verification_revision, deleting " +
                    "FROM tb_expo_projection WHERE expo_id = ? AND NOT deleted AND NOT deleting",
                { rs, _ -> Expo(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getLong(5), rs.getBoolean(6)) },
                expoId,
            ).singleOrNull()

    fun session(
        expoId: UUID,
        sessionId: Long,
    ): Session? =
        jdbc
            .query(
                "SELECT s.session_id, s.cancel_ended_at FROM tb_expo_projection_session s " +
                    "JOIN tb_expo_projection p ON p.expo_id = s.expo_id " +
                    "WHERE s.expo_id = ? AND s.session_id = ? AND NOT p.deleted AND NOT p.deleting",
                { rs, _ -> Session(rs.getLong(1), rs.getTimestamp(2)?.toInstant()) },
                expoId,
                sessionId,
            ).singleOrNull()
}
