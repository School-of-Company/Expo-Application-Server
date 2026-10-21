package team.startup.application.domain.application.repository

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.ReportApplicationSnapshotPage
import team.startup.application.domain.application.presentation.dto.ReportApplicationTarget
import team.startup.application.domain.application.presentation.dto.ReportPreregistrationSnapshotPage
import team.startup.application.domain.application.presentation.dto.ReportPreregistrationTarget
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Repository
class ReportApplicationSnapshotRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val mapper: ObjectMapper,
) {
    fun create(
        expoId: UUID,
        items: List<ReportApplicationTarget>,
        limit: Int,
    ): ReportApplicationSnapshotPage = page(expoId, store(expoId, items, limit, PROGRAM), 0, PROGRAM, ::programPage)

    fun read(
        expoId: UUID,
        cursor: String,
    ): ReportApplicationSnapshotPage = decode(cursor).let { (id, offset) -> page(expoId, id, offset, PROGRAM, ::programPage) }

    fun createPreregistration(
        expoId: UUID,
        items: List<ReportPreregistrationTarget>,
        limit: Int,
    ): ReportPreregistrationSnapshotPage =
        page(expoId, store(expoId, items, limit, PREREGISTRATION), 0, PREREGISTRATION, ::preregistrationPage)

    fun readPreregistration(
        expoId: UUID,
        cursor: String,
    ): ReportPreregistrationSnapshotPage =
        decode(cursor).let { (id, offset) -> page(expoId, id, offset, PREREGISTRATION, ::preregistrationPage) }

    private fun store(
        expoId: UUID,
        items: List<Any>,
        limit: Int,
        source: String,
    ): UUID {
        jdbc.update("DELETE FROM tb_report_application_snapshot WHERE expires_at <= clock_timestamp()", emptyMap<String, Any>())
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO tb_report_application_snapshot (id, expo_id, source, snapshot_at, expires_at, page_size, items) " +
                "VALUES (:id, :expo, :source, transaction_timestamp(), transaction_timestamp() + interval '15 minutes', :limit, " +
                "CAST(:items AS jsonb))",
            mapOf("id" to id, "expo" to expoId, "source" to source, "limit" to limit, "items" to mapper.writeValueAsString(items)),
        )
        return id
    }

    private fun programPage(
        meta: SnapshotMeta,
        items: String,
    ) = ReportApplicationSnapshotPage(
        meta.id,
        meta.snapshotAt,
        meta.expiresAt,
        meta.totalElements,
        mapper.readValue(items, mapper.typeFactory.constructCollectionType(List::class.java, ReportApplicationTarget::class.java)),
        meta.nextCursor,
    )

    private fun preregistrationPage(
        meta: SnapshotMeta,
        items: String,
    ) = ReportPreregistrationSnapshotPage(
        meta.id,
        meta.snapshotAt,
        meta.expiresAt,
        meta.totalElements,
        mapper.readValue(items, mapper.typeFactory.constructCollectionType(List::class.java, ReportPreregistrationTarget::class.java)),
        meta.nextCursor,
    )

    private fun decode(cursor: String): Pair<UUID, Int> =
        try {
            val parts = String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":")
            require(parts.size == 2)
            val id = UUID.fromString(parts[0])
            val offset = parts[1].toInt().also { require(it > 0) }
            require(cursor == encodeCursor(id, offset))
            id to offset
        } catch (exception: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid report cursor", exception)
        }

    private fun <P> page(
        expoId: UUID,
        id: UUID,
        offset: Int,
        source: String,
        build: (SnapshotMeta, String) -> P,
    ): P =
        jdbc
            .query(
                "SELECT snapshot_at, expires_at, expires_at <= clock_timestamp() AS expired, page_size, " +
                    "jsonb_array_length(items) AS total, " +
                    "(SELECT COALESCE(jsonb_agg(item ORDER BY position), '[]'::jsonb) " +
                    "FROM jsonb_array_elements(items) WITH ORDINALITY AS target(item, position) " +
                    "WHERE position > :offset AND position <= CAST(:offset AS bigint) + page_size) AS page " +
                    "FROM tb_report_application_snapshot WHERE id = :id AND expo_id = :expo AND source = :source",
                mapOf("id" to id, "expo" to expoId, "offset" to offset, "source" to source),
            ) { rs, _ ->
                if (rs.getBoolean("expired")) throw ResponseStatusException(HttpStatus.GONE, "Report snapshot expired; restart export")
                val total = rs.getInt("total")
                val limit = rs.getInt("page_size")
                if (offset != 0 && (offset >= total || offset % limit != 0)) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid report cursor position")
                }
                build(
                    SnapshotMeta(
                        id,
                        rs.getTimestamp("snapshot_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(),
                        total,
                        if (offset + limit < total) encodeCursor(id, offset + limit) else null,
                    ),
                    rs.getString("page"),
                )
            }.singleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Report snapshot not found")

    private data class SnapshotMeta(
        val id: UUID,
        val snapshotAt: Instant,
        val expiresAt: Instant,
        val totalElements: Int,
        val nextCursor: String?,
    )

    private fun encodeCursor(
        id: UUID,
        offset: Int,
    ): String = Base64.getUrlEncoder().withoutPadding().encodeToString("$id:$offset".toByteArray(StandardCharsets.UTF_8))

    companion object {
        private const val PROGRAM = "PROGRAM"
        private const val PREREGISTRATION = "PREREGISTRATION"
    }
}
