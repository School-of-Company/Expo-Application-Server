package team.startup.application.domain.application.repository

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.FieldParticipantResolution
import team.startup.application.domain.application.presentation.dto.FieldVisitReceipt
import team.startup.application.domain.application.presentation.dto.RegisterFieldVisitRequest
import team.startup.application.domain.application.service.impl.RegistrationForm
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.util.UUID

data class StoredFieldVisitRequest(
    val visitDate: LocalDate,
    val request: RegisterFieldVisitRequest,
    val phoneNumber: String,
    val resolved: List<FieldParticipantResolution>?,
    val receipt: FieldVisitReceipt?,
    val plannedReceipt: FieldVisitReceipt? = null,
    val representativeId: Long? = null,
    val partyIds: List<Long> = emptyList(),
    val form: RegistrationForm? = null,
)

@Repository
class FieldVisitRequestRepository(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) {
    fun <T> withLock(
        expoId: UUID,
        phoneHash: String,
        action: () -> T,
    ): T =
        jdbc.withDeliveryLock(75, "$expoId:$phoneHash") { action() }
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "FIELD_REQUEST_PENDING")

    fun reserve(
        expoId: UUID,
        requestId: String,
        ownerHash: String,
        phoneNumber: String,
        day: LocalDate,
        request: RegisterFieldVisitRequest,
        representativeId: Long? = null,
        partyIds: List<Long> = emptyList(),
        form: RegistrationForm? = null,
    ): StoredFieldVisitRequest {
        if (jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM tb_field_visit_request WHERE expo_id=? AND owner_hash=? " +
                    "AND request_id<>? AND receipt IS NULL)",
                Boolean::class.java,
                expoId,
                ownerHash,
                requestId,
            ) == true
        ) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "FIELD_REQUEST_PENDING")
        }
        val command = mapper.writeValueAsString(mapOf("phoneNumber" to phoneNumber, "request" to request))
        jdbc.update(
            "INSERT INTO tb_field_visit_request(" +
                "expo_id,request_id,owner_hash,visit_date,command,representative_id,party_ids,form_snapshot) " +
                "VALUES (?,?,?,?,?::jsonb,?,?::jsonb,?::jsonb) ON CONFLICT DO NOTHING",
            expoId,
            requestId,
            ownerHash,
            day,
            command,
            representativeId,
            mapper.writeValueAsString(partyIds),
            form?.let { mapper.writeValueAsString(it) },
        )
        val stored = jdbc.queryForMap("SELECT * FROM tb_field_visit_request WHERE expo_id=? AND request_id=?", expoId, requestId)
        if (stored["owner_hash"] != ownerHash || mapper.readTree(stored["command"].toString()) != mapper.readTree(command)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED")
        }
        return StoredFieldVisitRequest(
            (stored["visit_date"] as java.sql.Date).toLocalDate(),
            request,
            phoneNumber,
            stored["user_result"]?.let { value ->
                val nodes = mapper.readTree(value.toString())
                (0 until nodes.size()).map { mapper.treeToValue(nodes.get(it), FieldParticipantResolution::class.java) }
            },
            stored["receipt"]?.let { mapper.readValue(it.toString(), FieldVisitReceipt::class.java) },
            stored["planned_receipt"]?.let { mapper.readValue(it.toString(), FieldVisitReceipt::class.java) },
            (stored["representative_id"] as Number?)?.toLong(),
            mapper.readTree(stored["party_ids"].toString()).let { nodes -> (0 until nodes.size()).map { nodes.get(it).longValue() } },
            stored["form_snapshot"]?.let { mapper.readValue(it.toString(), RegistrationForm::class.java) },
        )
    }

    fun plan(
        expoId: UUID,
        requestId: String,
        receipt: FieldVisitReceipt,
    ) {
        jdbc.update(
            "UPDATE tb_field_visit_request SET planned_receipt=?::jsonb WHERE expo_id=? AND request_id=?",
            mapper.writeValueAsString(receipt),
            expoId,
            requestId,
        )
    }

    fun exists(
        expoId: UUID,
        requestId: String,
    ): Boolean =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM tb_field_visit_request WHERE expo_id=? AND request_id=?)",
            Boolean::class.java,
            expoId,
            requestId,
        ) ==
            true

    fun due(): List<Triple<UUID, String, String>> =
        jdbc.query(
            "SELECT expo_id,request_id,owner_hash FROM tb_field_visit_request WHERE receipt IS NULL " +
                "AND next_attempt_at<=CURRENT_TIMESTAMP AND attempts<40 ORDER BY next_attempt_at LIMIT 20",
            { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3)) },
        )

    fun recoverRequest(
        expoId: UUID,
        requestId: String,
        ownerHash: String,
    ): StoredFieldVisitRequest {
        val node =
            mapper.readTree(
                jdbc.queryForObject(
                    "SELECT command::text FROM tb_field_visit_request WHERE expo_id=? AND request_id=?",
                    String::class.java,
                    expoId,
                    requestId,
                ),
            )
        return reserve(
            expoId,
            requestId,
            ownerHash,
            node.get("phoneNumber").stringValue(),
            LocalDate.now(),
            mapper.treeToValue(node.get("request"), RegisterFieldVisitRequest::class.java),
        )
    }

    fun retry(
        expoId: UUID,
        requestId: String,
    ) {
        jdbc.update(
            "UPDATE tb_field_visit_request SET attempts=attempts+1,next_attempt_at=CURRENT_TIMESTAMP+INTERVAL '60 seconds' " +
                "WHERE expo_id=? AND request_id=?",
            expoId,
            requestId,
        )
    }

    fun resolved(
        expoId: UUID,
        requestId: String,
        result: List<FieldParticipantResolution>,
    ) {
        jdbc.update(
            "UPDATE tb_field_visit_request SET user_result=?::jsonb WHERE expo_id=? AND request_id=?",
            mapper.writeValueAsString(result),
            expoId,
            requestId,
        )
    }

    fun complete(
        expoId: UUID,
        requestId: String,
        receipt: FieldVisitReceipt,
    ) {
        jdbc.update(
            "UPDATE tb_field_visit_request SET receipt=?::jsonb WHERE expo_id=? AND request_id=?",
            mapper.writeValueAsString(receipt),
            expoId,
            requestId,
        )
    }
}
