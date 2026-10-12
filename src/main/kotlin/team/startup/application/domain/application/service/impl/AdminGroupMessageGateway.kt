package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AdminGroupMessageChannel
import team.startup.application.domain.application.presentation.dto.AdminGroupMessageConfig
import team.startup.application.domain.application.presentation.dto.AdminGroupMessageJob
import team.startup.application.domain.application.presentation.dto.AdminGroupMessagePreview
import team.startup.application.domain.application.presentation.dto.AdminGroupMessageSource
import team.startup.application.domain.application.presentation.dto.AdminGroupMessageTarget
import team.startup.application.domain.application.presentation.dto.AdminMessageHistoryPage
import team.startup.application.domain.application.presentation.dto.AdminMessageHistoryQuery
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

data class AdminGroupMessageParticipant(
    val participantId: Long,
    val name: String,
    val phoneNumber: String?,
    val representativeId: Long?,
)

@Component
class AdminGroupMessageGateway(
    private val mapper: ObjectMapper,
    @Value("\${application.admin-group-messaging.expo-url:}") private val expoUrl: String,
    @Value("\${application.admin-group-messaging.user-url:}") private val userUrl: String,
    @Value("\${application.admin-group-messaging.notification-url:}") private val notificationUrl: String,
    @Value("\${application.admin-group-messaging.expo-internal-token:}") private val expoToken: String,
    @Value("\${application.admin-group-messaging.user-internal-token:}") private val userToken: String,
    @Value("\${application.admin-group-messaging.notification-internal-token:}") private val notificationToken: String,
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    init {
        // 공급자 URL이 설정된 대상만 사용 중인 연동으로 보고 해당 토큰을 요구한다.
        mapOf(
            "EXPO" to (expoUrl to expoToken),
            "USER" to (userUrl to userToken),
            "NOTIFICATION" to (notificationUrl to notificationToken),
        ).forEach { (target, setting) ->
            require(setting.first.isBlank() || setting.second.isNotBlank()) {
                "APPLICATION_ADMIN_GROUP_MESSAGE_${target}_INTERNAL_TOKEN must be configured when its URL is set"
            }
        }
    }

    fun history(
        expoId: UUID,
        query: AdminMessageHistoryQuery,
    ): AdminMessageHistoryPage {
        val parameters = linkedMapOf("limit" to query.limit.toString())
        query.from?.let { parameters["from"] = it.toString() }
        query.to?.let { parameters["to"] = it.toString() }
        query.type?.let { parameters["type"] = it.name }
        query.cursor?.let { parameters["cursor"] = it }
        val suffix = parameters.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, StandardCharsets.UTF_8)}" }
        val tree = node(call(Provider.NOTIFICATION, notificationUrl, "$BASE_PATH/expos/$expoId/history?$suffix", token = notificationToken))
        return try {
            require(tree.path("items").isArray)
            tree.path("items").forEach { item ->
                require(item.path("adminId").isIntegralNumber && item.path("adminId").canConvertToLong())
                for (key in listOf("total", "pending", "accepted", "delivered", "failed", "unknown", "missing")) {
                    require(item.path("counts").path(key).isIntegralNumber && item.path("counts").path(key).canConvertToLong())
                }
            }
            val result = mapper.treeToValue(tree, AdminMessageHistoryPage::class.java)
            require(
                result.items.size <= query.limit && result.items
                    .map { it.eventId }
                    .distinct()
                    .size == result.items.size,
            )
            val cursorPattern = Regex("^\\d{13}:[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

            fun cursor(item: team.startup.application.domain.application.presentation.dto.AdminMessageHistoryItem) =
                "%013d:%s".format(java.util.Locale.ROOT, item.occurredAt.toEpochMilli(), item.eventId)
            result.items.forEach {
                require(it.adminId in 1..MAX_SAFE_INTEGER && it.jobId.matches(Regex("^[a-f0-9]{64}$")))
                require(
                    it.targetScope.kind == "EXPLICIT_TARGETS" &&
                        (it.targetScope.applicationCount == null || it.targetScope.applicationCount > 0),
                )
                require(
                    it.targetScope.programTypes ==
                        it.targetScope.programTypes
                            .distinct()
                            .sortedBy { type -> type.name },
                )
                require(
                    listOf(
                        it.counts.total,
                        it.counts.pending,
                        it.counts.accepted,
                        it.counts.delivered,
                        it.counts.failed,
                        it.counts.unknown,
                        it.counts.missing,
                    ).all { count ->
                        count >=
                            0
                    },
                )
                require(it.occurredAt.toEpochMilli() in 0..9_999_999_999_999L)
                require(
                    (query.type == null || it.type == query.type) && (query.from == null || it.occurredAt >= query.from) &&
                        (query.to == null || it.occurredAt < query.to),
                )
                require(query.cursor == null || cursor(it) < query.cursor)
            }
            require(result.items.zipWithNext().all { (a, b) -> cursor(a) > cursor(b) })
            if (result.nextCursor != null) {
                require(result.nextCursor.matches(cursorPattern) && result.items.size == query.limit && result.items.isNotEmpty())
                require(result.nextCursor == cursor(result.items.last()))
            }
            result
        } catch (_: Exception) {
            invalidProviderResponse("발송 이력")
        }
    }

    fun authorize(
        expoId: UUID,
        authorization: String?,
    ): Long {
        val bearer =
            authorization?.takeIf { it.startsWith("Bearer ") && it.length > 7 }
                ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "관리자 인증이 필요합니다.")
        val response = call(Provider.EXPO, expoUrl, "/internal/expo/$expoId/admin-access", token = expoToken, authorization = bearer)
        val body = node(response)
        val adminId = body.path("adminId")
        if (body.get("expoId")?.takeIf { it.isString }?.stringValue() != expoId.toString() ||
            !adminId.isIntegralNumber || !adminId.canConvertToLong() || adminId.longValue() !in 1..MAX_SAFE_INTEGER ||
            body.get("permission")?.takeIf { it.isString }?.stringValue() != "APPLICATION_READ" ||
            body.get("scope")?.takeIf { it.isString }?.stringValue() != "GLOBAL_ADMIN" ||
            body.get("policyVersion")?.takeIf { it.isString }?.stringValue() != "GLOBAL_ADMIN_APPLICATION_READ_V1"
        ) {
            invalidProviderResponse("행사 관리자 권한")
        }
        return adminId.longValue()
    }

    fun participants(
        expoId: UUID,
        participantIds: Collection<Long>,
    ): Map<Long, AdminGroupMessageParticipant> {
        if (participantIds.isEmpty()) return emptyMap()
        val ids = participantIds.distinct()
        val response =
            call(
                Provider.USER,
                userUrl,
                "/internal/standard-participants/details",
                method = "POST",
                body = mapper.writeValueAsString(mapOf("expoId" to expoId, "participantIds" to ids)),
                token = userToken,
            )
        val array = node(response)
        if (!array.isArray || array.size() != ids.size) invalidProviderResponse("대표 참가자")
        val result =
            (0 until array.size()).map { index ->
                val item = array[index]
                val id = item.path("participantId")
                val name = item.path("name")
                val phone = item.get("phoneNumber")
                val representative = item.get("representativeId")
                if (!id.isIntegralNumber ||
                    !id.canConvertToLong() ||
                    id.longValue() <= 0 ||
                    !name.isString ||
                    name.stringValue().isBlank() ||
                    phone == null || (!phone.isNull && !phone.isString) ||
                    representative == null ||
                    (
                        !representative.isNull &&
                            (!representative.isIntegralNumber || !representative.canConvertToLong() || representative.longValue() <= 0)
                    )
                ) {
                    invalidProviderResponse("대표 참가자")
                }
                AdminGroupMessageParticipant(
                    id.longValue(),
                    name.stringValue(),
                    if (phone.isNull) null else phone.stringValue(),
                    if (representative.isNull) null else representative.longValue(),
                )
            }
        if (result.map { it.participantId }.toSet() != ids.toSet()) invalidProviderResponse("대표 참가자")
        return result.associateBy { it.participantId }
    }

    fun config(): AdminGroupMessageConfig =
        decode(
            call(Provider.NOTIFICATION, notificationUrl, CONFIG_PATH, token = notificationToken),
            AdminGroupMessageConfig::class.java,
        )

    fun preview(
        requesterId: Long,
        expoId: UUID,
        requestId: UUID,
        source: AdminGroupMessageSource,
        fingerprint: String,
        applicationCount: Int,
        targets: List<AdminGroupMessageTarget>,
    ): AdminGroupMessagePreview =
        decode(
            call(
                Provider.NOTIFICATION,
                notificationUrl,
                "$BASE_PATH/previews",
                "POST",
                mapper.writeValueAsString(
                    linkedMapOf<String, Any>(
                        "requesterUserId" to requesterId,
                        "expoId" to expoId,
                        "requestId" to requestId,
                        "targetFingerprint" to fingerprint,
                        "applicationCount" to applicationCount,
                        "channel" to source.channel,
                        "fallbackToSms" to false,
                        "targets" to targets,
                    ).also { request -> source.templateId?.let { request["templateId"] = it } },
                ),
                notificationToken,
            ),
            AdminGroupMessagePreview::class.java,
        )

    fun execute(
        previewId: UUID,
        requesterId: Long,
        expoId: UUID,
        fingerprint: String,
        idempotencyKey: UUID,
    ): AdminGroupMessageJob =
        job(
            "$BASE_PATH/previews/$previewId/jobs",
            mapOf(
                "requesterUserId" to requesterId,
                "expoId" to expoId,
                "targetFingerprint" to fingerprint,
                "idempotencyKey" to idempotencyKey,
            ),
        )

    fun status(
        jobId: String,
        requesterId: Long,
        expoId: UUID,
    ): AdminGroupMessageJob =
        decode(
            call(
                Provider.NOTIFICATION,
                notificationUrl,
                "$BASE_PATH/jobs/$jobId?requesterUserId=$requesterId&expoId=$expoId",
                token = notificationToken,
            ),
            AdminGroupMessageJob::class.java,
        )

    fun retry(
        jobId: String,
        requesterId: Long,
        expoId: UUID,
        fingerprint: String,
        idempotencyKey: UUID,
    ): AdminGroupMessageJob =
        job(
            "$BASE_PATH/jobs/$jobId/retries",
            mapOf(
                "requesterUserId" to requesterId,
                "expoId" to expoId,
                "targetFingerprint" to fingerprint,
                "idempotencyKey" to idempotencyKey,
            ),
        )

    private fun job(
        path: String,
        body: Map<String, Any>,
    ): AdminGroupMessageJob =
        decode(
            call(Provider.NOTIFICATION, notificationUrl, path, "POST", mapper.writeValueAsString(body), notificationToken),
            AdminGroupMessageJob::class.java,
        )

    private fun node(response: HttpResponse<String>): JsonNode = decode(response, JsonNode::class.java)

    private fun <T> decode(
        response: HttpResponse<String>,
        type: Class<T>,
    ): T =
        try {
            requireNotNull(mapper.readValue(response.body(), type))
        } catch (exception: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "내부 공급자 응답이 올바르지 않습니다.", exception)
        }

    private fun call(
        provider: Provider,
        base: String,
        path: String,
        method: String = "GET",
        body: String? = null,
        token: String,
        authorization: String? = null,
    ): HttpResponse<String> {
        if (base.isBlank() || token.isBlank()) {
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "관리자 그룹 발송 공급자가 설정되지 않았습니다.",
            )
        }
        val builder =
            HttpRequest
                .newBuilder(URI.create(base.removeSuffix("/") + path))
                .timeout(Duration.ofSeconds(10))
                .header("X-Internal-Token", token)
        authorization?.let { builder.header("Authorization", it) }
        val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        if (body != null) builder.header("Content-Type", "application/json")
        val response =
            try {
                client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
            } catch (exception: HttpTimeoutException) {
                throw ResponseStatusException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "관리자 그룹 발송 공급자 응답 시간이 초과되었습니다.",
                    exception,
                )
            } catch (exception: IOException) {
                throw ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "관리자 그룹 발송 공급자에 연결할 수 없습니다.",
                    exception,
                )
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "관리자 그룹 발송 공급자 호출이 중단되었습니다.",
                    exception,
                )
            }
        if (response.statusCode() !in 200..299) {
            val supplierStatus = HttpStatus.resolve(response.statusCode())
            val status =
                when {
                    supplierStatus?.is4xxClientError != true -> HttpStatus.SERVICE_UNAVAILABLE
                    provider == Provider.USER -> HttpStatus.BAD_GATEWAY
                    provider == Provider.NOTIFICATION && response.statusCode() in setOf(401, 403) -> HttpStatus.BAD_GATEWAY
                    else -> supplierStatus
                }
            throw ResponseStatusException(status, "관리자 그룹 발송 공급자가 요청을 거절했습니다.")
        }
        return response
    }

    private fun invalidProviderResponse(provider: String): Nothing =
        throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "$provider 공급자 응답이 올바르지 않습니다.")

    private enum class Provider { EXPO, USER, NOTIFICATION }

    private companion object {
        const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
        const val BASE_PATH = "/internal/admin-group-messages"
        const val CONFIG_PATH = "$BASE_PATH/config"
    }
}
