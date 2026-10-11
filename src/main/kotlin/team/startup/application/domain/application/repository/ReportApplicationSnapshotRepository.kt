package team.startup.application.domain.application.repository

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.ReportApplicationSnapshotPage
import team.startup.application.domain.application.presentation.dto.ReportApplicationTarget
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
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
    ): ReportApplicationSnapshotPage {
        jdbc.update("DELETE FROM tb_report_application_snapshot WHERE expires_at <= clock_timestamp()", emptyMap<String, Any>())
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO tb_report_application_snapshot (id, expo_id, snapshot_at, expires_at, page_size, items) " +
                "VALUES (:id, :expo, transaction_timestamp(), transaction_timestamp() + interval '15 minutes', :limit, CAST(:items AS jsonb))",
            mapOf("id" to id, "expo" to expoId, "limit" to limit, "items" to mapper.writeValueAsString(items)),
        )
        return page(expoId, id, 0)
    }

    fun read(
        expoId: UUID,
        cursor: String,
    ): ReportApplicationSnapshotPage {
        val (id, offset) =
            try {
                val parts = String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":")
                require(parts.size == 2)
                UUID.fromString(parts[0]) to parts[1].toInt().also { require(it > 0) }
            } catch (exception: IllegalArgumentException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid report cursor", exception)
            }
        return page(expoId, id, offset)
    }

    private fun page(
        expoId: UUID,
        id: UUID,
        offset: Int,
    ): ReportApplicationSnapshotPage =
        jdbc
            .query(
                "SELECT snapshot_at, expires_at, expires_at <= clock_timestamp() AS expired, page_size, " +
                    "jsonb_array_length(items) AS total, " +
                    "(SELECT COALESCE(jsonb_agg(item ORDER BY position), '[]'::jsonb) " +
                    "FROM jsonb_array_elements(items) WITH ORDINALITY AS target(item, position) " +
                    "WHERE position > :offset AND position <= CAST(:offset AS bigint) + page_size) AS page " +
                    "FROM tb_report_application_snapshot WHERE id = :id AND expo_id = :expo",
                mapOf("id" to id, "expo" to expoId, "offset" to offset),
            ) { rs, _ ->
                if (rs.getBoolean("expired")) throw ResponseStatusException(HttpStatus.GONE, "Report snapshot expired; restart export")
                val total = rs.getInt("total")
                val limit = rs.getInt("page_size")
                if (offset != 0 && (offset >= total || offset % limit != 0)) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid report cursor position")
                }
                ReportApplicationSnapshotPage(
                    id,
                    rs.getTimestamp("snapshot_at").toInstant(),
                    rs.getTimestamp("expires_at").toInstant(),
                    total,
                    mapper.readValue(
                        rs.getString("page"),
                        mapper.typeFactory.constructCollectionType(List::class.java, ReportApplicationTarget::class.java),
                    ),
                    if (offset + limit < total) {
                        Base64.getUrlEncoder().withoutPadding().encodeToString("$id:${offset + limit}".toByteArray(StandardCharsets.UTF_8))
                    } else {
                        null
                    },
                )
            }.singleOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Report snapshot not found")
}
