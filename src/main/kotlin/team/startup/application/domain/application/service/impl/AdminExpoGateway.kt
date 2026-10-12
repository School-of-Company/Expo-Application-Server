package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AdminCurrentSessionPage
import team.startup.application.domain.application.presentation.dto.AdminCurrentSessionQuery
import team.startup.application.domain.application.presentation.dto.AdminExpoAccess
import team.startup.application.domain.application.presentation.dto.AdminExpoHistoryPage
import team.startup.application.domain.application.presentation.dto.AdminExpoHistoryQuery
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import tools.jackson.databind.ObjectMapper
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.util.UUID

@Component
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminExpoGateway(
    private val mapper: ObjectMapper,
    @Value("\${application.registration.expo-url:}") url: String,
    @Value("\${application.registration.expo-internal-token:}") token: String,
) {
    private val http = AdminSourceHttp(mapper, url, token)

    fun history(
        expoId: UUID,
        authorization: String,
        query: AdminExpoHistoryQuery,
    ): AdminExpoHistoryPage {
        val parameters = linkedMapOf("page" to query.page.toString(), "size" to query.size.toString())
        query.type?.let { parameters["type"] = it.name }
        query.from?.let { parameters["from"] = it.toString() }
        query.to?.let { parameters["to"] = it.toString() }
        val suffix = parameters.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, StandardCharsets.UTF_8)}" }
        val tree =
            http.read(
                "/expo/$expoId/admin-change-history?$suffix",
                authorization = authorization,
                propagatedStatuses = setOf(400, 401, 403, 404),
            )
        return http.valid {
            for (key in listOf("page", "size", "totalElements")) {
                require(tree.path(key).isIntegralNumber && tree.path(key).canConvertToLong())
            }
            val result = mapper.treeToValue(tree, AdminExpoHistoryPage::class.java)
            require(result.expoId == expoId && result.page == query.page && result.size == query.size && result.totalElements >= 0)
            require(
                result.items.size.toLong() ==
                    minOf((result.totalElements - query.page.toLong() * query.size).coerceAtLeast(0), query.size.toLong()),
            )
            require(
                result.items
                    .map { it.id }
                    .distinct()
                    .size == result.items.size,
            )
            result.items.forEach {
                require(it.id > 0 && it.targetId > 0 && (it.adminId == null || it.adminId > 0) && it.summary.isNotBlank())
                require(
                    (query.type == null || it.type == query.type) && (query.from == null || it.occurredAt >= query.from) &&
                        (query.to == null || it.occurredAt < query.to),
                )
            }
            require(
                result.items.zipWithNext().all { (a, b) ->
                    a.occurredAt > b.occurredAt || (a.occurredAt == b.occurredAt && a.id > b.id)
                },
            )
            result
        }
    }

    fun requireCancelAccess(
        expoId: UUID,
        authorization: String?,
    ): AdminExpoAccess {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.removePrefix("Bearer ").isBlank()) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "관리자 인증이 필요합니다.")
        }
        val tree =
            http.read(
                "/internal/expo/$expoId/admin-application-cancel-access",
                authorization = authorization,
                propagatedStatuses = setOf(401, 403, 404),
            )
        return http.valid {
            require(tree["adminId"]?.isIntegralNumber == true && tree["adminId"].canConvertToLong())
            mapper.treeToValue(tree, AdminExpoAccess::class.java).also {
                require(
                    it.expoId == expoId && it.adminId > 0 && it.permission == "APPLICATION_CANCEL" &&
                        it.policyVersion == "EXPO_ADMIN_APPLICATION_CANCEL_V1" && it.scope == "EXPO_ADMIN",
                )
            }
        }
    }

    fun requireAccess(
        expoId: UUID,
        authorization: String?,
    ): AdminExpoAccess {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.removePrefix("Bearer ").isBlank()) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "관리자 인증이 필요합니다.")
        }
        val tree =
            http.read(
                "/internal/expo/$expoId/admin-access",
                authorization = authorization,
                propagatedStatuses = setOf(401, 403, 404),
            )
        return http.valid {
            require(tree["adminId"]?.isIntegralNumber == true && tree["adminId"].canConvertToLong())
            mapper.treeToValue(tree, AdminExpoAccess::class.java).also {
                require(
                    it.expoId == expoId && it.adminId > 0 && it.permission == "APPLICATION_READ" &&
                        it.policyVersion == "GLOBAL_ADMIN_APPLICATION_READ_V1" && it.scope == "GLOBAL_ADMIN",
                )
            }
        }
    }

    fun sessions(
        expoId: UUID,
        query: AdminCurrentSessionQuery,
    ): AdminCurrentSessionPage {
        val parameters = linkedMapOf("page" to query.page.toString(), "size" to query.size.toString())
        query.programType?.let { parameters["programType"] = it.name }
        query.programId?.let { parameters["programId"] = it.toString() }
        query.groupId?.let { parameters["groupId"] = it.toString() }
        query.definitionVersion?.let { parameters["definitionVersion"] = it }
        val suffix = parameters.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, StandardCharsets.UTF_8)}" }
        val tree = http.read("/internal/expo/$expoId/program-sessions?$suffix", propagatedStatuses = setOf(404, 409))
        return http.valid {
            require(
                tree["page"]?.isIntegralNumber == true && tree["page"].canConvertToInt() && tree["size"]?.isIntegralNumber == true &&
                    tree["size"].canConvertToInt() &&
                    tree["totalElements"]?.isIntegralNumber == true && tree["totalElements"].canConvertToLong(),
            )
            val result = mapper.treeToValue(tree, AdminCurrentSessionPage::class.java)
            require(
                result.expoId == expoId && result.page == query.page && result.size == query.size && result.totalElements >= 0 &&
                    result.definitionVersion.isNotBlank() &&
                    (query.definitionVersion == null || result.definitionVersion == query.definitionVersion),
            )
            val remaining = (result.totalElements - query.page.toLong() * query.size).coerceAtLeast(0)
            require(result.items.size.toLong() == minOf(remaining, query.size.toLong()))
            val refs =
                result.items.map {
                    require(
                        UUID.fromString(it["expoId"].asString()) == expoId && it["id"].isIntegralNumber && it["id"].canConvertToLong() &&
                            it["id"].asLong() > 0,
                    )
                    val type = ProgramSessionType.valueOf(it["programType"].asString())
                    val group = UUID.fromString(it["groupId"].asString())
                    require(
                        it["title"].isString && it["revision"].isIntegralNumber && it["revision"].canConvertToLong() &&
                            it["revision"].asLong() > 0,
                    )
                    require(LocalDateTime.parse(it["startedAt"].asString()) < LocalDateTime.parse(it["endedAt"].asString()))
                    require(
                        (query.programType == null || type == query.programType) &&
                            (query.programId == null || it["id"].asLong() == query.programId) &&
                            (query.groupId == null || group == query.groupId),
                    )
                    type to it["id"].asLong()
                }
            require(refs.distinct().size == refs.size)
            result
        }
    }
}
