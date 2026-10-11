package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.AdminOccupation
import team.startup.application.domain.application.presentation.dto.AdminParticipantDetail
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
        http.valid { require(results.keys == wanted) }
        confirm(expoId, wanted.sorted())
        return results
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
    ): ParticipantPage {
        val parameters = mutableListOf("cursor=$cursor", "size=500")
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
                items.size <= 500 && items.zipWithNext().all { (left, right) -> left.participantId < right.participantId } &&
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
