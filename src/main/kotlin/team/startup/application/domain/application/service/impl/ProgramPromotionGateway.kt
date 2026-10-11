package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.ProgramPromotionCheck
import team.startup.application.domain.application.presentation.dto.ProgramPromotionEvent
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Component
class ProgramPromotionGateway(
    private val mapper: ObjectMapper,
    @Value("\${application.registration.user-url:}") private val userUrl: String,
    @Value("\${application.registration.user-internal-token:}") private val userToken: String,
    @Value("\${application.registration.expo-url:}") private val expoUrl: String,
    @Value("\${application.registration.expo-internal-token:}") private val expoToken: String,
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    fun details(event: ProgramPromotionEvent): ProgramPromotionCheck {
        val participant = participant(event, event.participantId)
        val representativeId = participant.get("representativeId") ?: invalid()
        val representative =
            if (representativeId.isNull) {
                participant
            } else {
                if (!representativeId.isIntegralNumber || !representativeId.canConvertToLong() || representativeId.asLong() <= 0) invalid()
                participant(event, representativeId.asLong()).also {
                    if (it.get("representativeId")?.isNull != true) invalid()
                }
            }
        val phoneNode = representative.get("phoneNumber") ?: invalid()
        val phone = if (phoneNode.isNull) null else phoneNode.takeIf { it.isString }?.stringValue()?.filter { it in '0'..'9' } ?: invalid()
        if (phone != null && !phone.matches(Regex("0[0-9]{8,10}"))) invalid()
        val program =
            if (event.programType == ProgramSessionType.STANDARD) {
                call(expoUrl, expoToken, "/internal/expo/${event.expoId}/standard-programs/${event.programId}")
            } else {
                val array =
                    call(
                        expoUrl,
                        expoToken,
                        "/internal/expo/${event.expoId}/training-programs/batch",
                        mapOf("programIds" to listOf(event.programId)),
                    )
                if (!array.isArray || array.size() != 1) invalid()
                array.get(0)
            }
        if (!program.isObject || !program.path("id").isIntegralNumber || !program.path("id").canConvertToLong() ||
            program.path("id").asLong() != event.programId ||
            expoId(program) != event.expoId || program.path("programType").asString() != event.programType.name
        ) {
            invalid()
        }
        val start = time(program, "startedAt")
        val end = time(program, "endedAt")
        if (!end.toInstant().isAfter(start.toInstant())) invalid()
        return ProgramPromotionCheck(
            eligible = true,
            eventId = event.eventId,
            applicationId = event.applicationId,
            expoId = event.expoId,
            participantId = event.participantId,
            programType = event.programType,
            programId = event.programId,
            status = "CONFIRMED",
            representativePhoneNumber = phone,
            participantName = label(participant, "name"),
            programName = label(program, "title"),
            startedAt = start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            endedAt = end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            location = program.get("location")?.takeUnless { it.isNull }?.let { label(program, "location") },
            applicationLookupUrl =
                program.get("applicationLookupUrl")?.takeUnless { it.isNull }?.let {
                    val url = it.takeIf { value -> value.isString }?.stringValue() ?: invalid()
                    val uri =
                        try {
                            URI.create(url)
                        } catch (_: IllegalArgumentException) {
                            invalid()
                        }
                    if (url.length > 500 || uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) invalid()
                    url
                },
        )
    }

    private fun participant(
        event: ProgramPromotionEvent,
        id: Long,
    ): JsonNode {
        val array =
            call(
                userUrl,
                userToken,
                "/internal/standard-participants/details",
                mapOf(
                    "expoId" to event.expoId,
                    "participantIds" to listOf(id),
                ),
            )
        if (!array.isArray || array.size() != 1) invalid()
        return array.get(0).also {
            if (!it.isObject || !it.path("participantId").isIntegralNumber || !it.path("participantId").canConvertToLong() ||
                it.path("participantId").asLong() != id
            ) {
                invalid()
            }
        }
    }

    private fun expoId(node: JsonNode): UUID =
        try {
            UUID.fromString(node.get("expoId")?.takeIf { it.isString }?.stringValue() ?: invalid())
        } catch (_: IllegalArgumentException) {
            invalid()
        }

    private fun label(
        node: JsonNode,
        field: String,
    ): String {
        val value = node.get(field)?.takeIf { it.isString }?.stringValue() ?: invalid()
        if (value.isBlank() || value.length > 100 ||
            value.any {
                Character.isISOControl(it) ||
                    Character.getType(it) in
                    listOf(Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt())
            }
        ) {
            invalid()
        }
        return value
    }

    private fun time(
        node: JsonNode,
        field: String,
    ): OffsetDateTime =
        try {
            OffsetDateTime.parse(node.get(field)?.takeIf { it.isString }?.stringValue() ?: invalid())
        } catch (_: Exception) {
            invalid()
        }

    private fun call(
        base: String,
        token: String,
        path: String,
        body: Map<String, Any>? = null,
    ): JsonNode {
        if (base.isBlank() || token.isBlank()) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "승급 조회 공급자가 설정되지 않았습니다.")
        val response =
            try {
                val builder =
                    HttpRequest
                        .newBuilder(
                            URI.create(base.trimEnd('/') + path),
                        ).timeout(Duration.ofSeconds(8))
                        .header("X-Internal-Token", token)
                if (body ==
                    null
                ) {
                    builder.GET()
                } else {
                    builder
                        .header(
                            "Content-Type",
                            "application/json",
                        ).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                }
                client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            } catch (exception: Exception) {
                if (exception is InterruptedException) Thread.currentThread().interrupt()
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "승급 조회 공급자에 연결할 수 없습니다.")
            }
        if (response.statusCode() in 500..599) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "승급 조회 공급자가 응답하지 않습니다.")
        if (response.statusCode() != 200) invalid()
        return try {
            mapper.readTree(response.body())
        } catch (_: Exception) {
            invalid()
        }
    }

    private fun invalid(): Nothing = throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "승급 조회 공급자 응답이 올바르지 않습니다.")
}
