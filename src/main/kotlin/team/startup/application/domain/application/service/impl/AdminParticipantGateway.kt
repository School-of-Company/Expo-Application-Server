package team.startup.application.domain.application.service.impl

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AdminOccupation
import team.startup.application.domain.application.presentation.dto.AdminParticipantDetail
import team.startup.application.domain.application.presentation.dto.AdminParticipantPage
import team.startup.application.domain.application.presentation.dto.AdminParticipantSearchQuery
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

@Component
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminParticipantGateway(
    private val mapper: ObjectMapper,
    @Value("\${application.registration.user-url:}") url: String,
    @Value("\${application.registration.user-internal-token:}") token: String,
) {
    private val http = AdminSourceHttp(mapper, url, token)
    private val log = LoggerFactory.getLogger(javaClass)

    fun resolve(
        expoId: UUID,
        q: String?,
        occupation: String?,
    ): List<Long>? {
        if (q == null && occupation == null) return null
        require(q == null || (q.isNotBlank() && q.length <= 50))
        occupation?.let(AdminOccupation::valueOf)
        val ids = mutableListOf<Long>()
        var cursor = 0L
        do {
            val page = page(expoId, cursor, q, occupation)
            ids.addAll(page.items.map { it.participantId })
            cursor = page.nextCursor ?: break
        } while (true)
        confirm(expoId, ids)
        return ids
    }

    fun search(
        expoId: UUID,
        query: AdminParticipantSearchQuery,
    ): AdminParticipantPage {
        val page = page(expoId, query.cursor, query.q, size = query.size)
        return AdminParticipantPage(page.items, page.nextCursor)
    }

    fun details(
        expoId: UUID,
        participantIds: List<Long>,
    ): Map<Long, AdminParticipantDetail> {
        if (participantIds.isEmpty()) return emptyMap()
        val wanted = participantIds.toSet()
        require(wanted.all { it > 0 })
        val results = mutableMapOf<Long, AdminParticipantDetail>()
        var cursor = wanted.min() - 1
        do {
            val page = page(expoId, cursor)
            page.items.filter { it.participantId in wanted }.forEach { results[it.participantId] = it }
            if (results.size == wanted.size) break
            val nextWanted = (wanted - results.keys).min()
            if (page.nextCursor == null || page.items.last().participantId >= nextWanted) break
            cursor = nextWanted - 1
        } while (true)
        if (results.keys != wanted) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "참가자 원천에 누락된 ID가 있어 관리자 목록 전체를 반환할 수 없습니다.")
        }
        confirm(expoId, wanted.sorted())
        return results
    }

    /** #261: User가 FIELD로 저장한 참가자의 일행 대표자 ID(대표자 본인이면 자기 ID). 없거나 FIELD가 아니면 null이다. */
    fun fieldRepresentative(
        expoId: UUID,
        participantId: Long,
    ): Long? {
        require(participantId > 0)
        val participant = page(expoId, participantId - 1, size = 1).items.singleOrNull()?.takeIf { it.participantId == participantId }
        return participant?.takeIf { it.applicationType == "FIELD" }?.let { it.representativeId ?: it.participantId }
    }

    /**
     * Current User names for optional actor display. User answers all-or-nothing, so a missing ID (404) or an
     * upstream/contract failure returns null for the whole batch instead of failing the readable history.
     */
    fun names(
        expoId: UUID,
        participantIds: List<Long>,
    ): Map<Long, String>? {
        if (participantIds.isEmpty()) return emptyMap()
        // ponytail: one call; callers pass at most one admin page or report source (<= 10000 rows), under User's 10,000-ID limit.
        val ids = participantIds.distinct().sorted()
        return try {
            val tree =
                http.read(
                    "/internal/standard-participants/names",
                    mapOf("expoId" to expoId.toString(), "participantIds" to ids),
                    propagatedStatuses = setOf(404),
                )
            http.valid {
                require(tree.isArray && tree.size() == ids.size)
                (0 until tree.size())
                    .associate { index ->
                        val node = tree[index]
                        val id = node["participantId"]
                        require(id?.isIntegralNumber == true && id.canConvertToLong() && node["name"]?.isString == true)
                        id.asLong() to node["name"].stringValue()
                    }.also { require(it.keys == ids.toSet()) }
            }
        } catch (exception: ResponseStatusException) {
            log.warn("Actor names unavailable for expo {}: {}", expoId, exception.statusCode)
            null
        }
    }

    private fun confirm(
        expoId: UUID,
        ids: List<Long>,
    ) {
        ids.chunked(500).forEach { batch ->
            val tree = http.read("/internal/standard-participants/details", mapOf("expoId" to expoId, "participantIds" to batch))
            http.valid {
                require(tree.isArray && tree.size() == batch.size)
                require(
                    (0 until tree.size()).map { index ->
                        val id = tree[index]["participantId"]
                        require(id?.isIntegralNumber == true && id.canConvertToLong())
                        id.asLong()
                    } == batch,
                )
            }
        }
    }

    private fun page(
        expoId: UUID,
        cursor: Long,
        q: String? = null,
        occupation: String? = null,
        size: Int = 500,
    ): ParticipantPage {
        val parameters = mutableListOf("cursor=$cursor", "size=$size")
        q?.let { parameters.add("q=${URLEncoder.encode(it.trim(), StandardCharsets.UTF_8)}") }
        occupation?.let { parameters.add("occupation=$it") }
        val tree = http.read("/internal/expos/$expoId/standard-participants/details?${parameters.joinToString("&")}")
        return http.valid {
            require(tree["items"]?.isArray == true && tree.has("nextCursor"))
            val nodes = tree["items"]
            val items =
                (0 until nodes.size()).map { index ->
                    val node = nodes[index]
                    require(
                        node["participantId"]?.isIntegralNumber == true && node["name"]?.isString == true &&
                            node["applicationType"]?.isString == true && node["information"]?.isObject == true,
                    )
                    require(node["participantId"].canConvertToLong())
                    for (field in listOf(
                        "phoneNumber",
                        "notificationPhoneNumber",
                        "occupation",
                        "representativeId",
                        "linkedTraineeId",
                    )) {
                        require(node.has(field))
                    }
                    for (field in listOf("phoneNumber", "notificationPhoneNumber", "occupation")) {
                        require(node[field].isNull || node[field].isString)
                    }
                    for (field in listOf("representativeId", "linkedTraineeId")) {
                        val id = node[field]
                        require(id.isNull || (id.isIntegralNumber && id.canConvertToLong() && id.asLong() > 0))
                    }
                    mapper.treeToValue(node, AdminParticipantDetail::class.java)
                }
            require(
                items.size <= size && items.zipWithNext().all { (left, right) -> left.participantId < right.participantId } &&
                    items.all { it.participantId > cursor && (occupation == null || it.occupation?.name == occupation) },
            )
            val nextNode: JsonNode = tree["nextCursor"]
            require(nextNode.isNull || (nextNode.isIntegralNumber && nextNode.canConvertToLong()))
            val next = nextNode.takeUnless { it.isNull }?.asLong()
            require(next == null || (items.isNotEmpty() && next == items.last().participantId && next > cursor))
            ParticipantPage(items, next)
        }
    }

    private data class ParticipantPage(
        val items: List<AdminParticipantDetail>,
        val nextCursor: Long?,
    )
}
