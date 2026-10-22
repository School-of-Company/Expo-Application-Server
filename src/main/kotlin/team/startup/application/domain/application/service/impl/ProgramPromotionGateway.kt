package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.ProgramPromotionCheck
import team.startup.application.domain.application.presentation.dto.ProgramPromotionEvent
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.time.ExpoProgramTime
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
) : ApplicationNoticeSource {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    // #308: 신청 결과 문자의 대표자 번호와 대상자 본인 이름. 대상자 번호로 대신하지 않는다.
    override fun contact(
        expoId: UUID,
        participantId: Long,
    ): NoticeContact {
        val participant = participant(expoId, participantId)
        return NoticeContact(phone(representative(expoId, participant)), text(participant, "name"))
    }

    fun details(event: ProgramPromotionEvent): ProgramPromotionCheck {
        val participant = participant(event.expoId, event.participantId)
        val phone = phone(representative(event.expoId, participant))
        val program = program(event.expoId, event.programType, event.programId)
        val start = time(program, "startedAt")
        val end = time(program, "endedAt")
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
            participantName = text(participant, "name"),
            programName = text(program, "title"),
            startedAt = start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            endedAt = end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            location = program.get("place")?.takeUnless { it.isNull }?.let { text(program, "place") },
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

    data class ProgramNotice(
        val title: String,
        val startedAt: String,
        val endedAt: String,
        val location: String?,
    )

    // Current Expo title, KST times and place for a #144 offer message. The offer check normalizes the text itself (#236).
    override fun programNotice(
        expoId: UUID,
        type: ProgramSessionType,
        programId: Long,
    ): ProgramNotice {
        val program = program(expoId, type, programId)
        val start = time(program, "startedAt")
        val end = time(program, "endedAt")
        return ProgramNotice(
            text(program, "title"),
            start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            program.get("place")?.takeUnless { it.isNull }?.let { text(program, "place") },
        )
    }

    private fun program(
        expoId: UUID,
        type: ProgramSessionType,
        programId: Long,
    ): JsonNode {
        val program =
            if (type == ProgramSessionType.STANDARD) {
                call(expoUrl, expoToken, "/internal/expo/$expoId/standard-programs/$programId")
            } else {
                val array =
                    call(
                        expoUrl,
                        expoToken,
                        "/internal/expo/$expoId/training-programs/batch",
                        mapOf("programIds" to listOf(programId)),
                    )
                if (!array.isArray || array.size() != 1) invalid()
                array.get(0)
            }
        if (!program.isObject || !program.path("id").isIntegralNumber || !program.path("id").canConvertToLong() ||
            program.path("id").asLong() != programId ||
            expoId(program) != expoId || program.path("programType").asString() != type.name
        ) {
            invalid()
        }
        val start = time(program, "startedAt")
        val end = time(program, "endedAt")
        if (!end.toInstant().isAfter(start.toInstant())) invalid()
        return program
    }

    // The participant's own representative (or the participant itself) receives program notices; #144 offers reuse it.
    fun representativeContact(
        expoId: UUID,
        participantId: Long,
    ): Pair<String?, String> = representative(expoId, participant(expoId, participantId)).let { phone(it) to text(it, "name") }

    private fun representative(
        expoId: UUID,
        participant: JsonNode,
    ): JsonNode {
        val representativeId = participant.get("representativeId") ?: invalid()
        if (representativeId.isNull) return participant
        if (!representativeId.isIntegralNumber || !representativeId.canConvertToLong() || representativeId.asLong() <= 0) invalid()
        return participant(expoId, representativeId.asLong()).also {
            if (it.get("representativeId")?.isNull != true) invalid()
        }
    }

    private fun phone(representative: JsonNode): String? {
        val phoneNode = representative.get("phoneNumber") ?: invalid()
        val phone = if (phoneNode.isNull) null else phoneNode.takeIf { it.isString }?.stringValue()?.filter { it in '0'..'9' } ?: invalid()
        if (phone != null && !phone.matches(Regex("0[0-9]{8,10}"))) invalid()
        return phone
    }

    private fun participant(
        expoId: UUID,
        id: Long,
    ): JsonNode {
        val array =
            call(
                userUrl,
                userToken,
                "/internal/standard-participants/details",
                mapOf(
                    "expoId" to expoId,
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

    private fun text(
        node: JsonNode,
        field: String,
    ): String = node.get(field)?.takeIf { it.isString }?.stringValue() ?: invalid()

    private fun label(
        node: JsonNode,
        field: String,
    ): String {
        val value = text(node, field)
        if (value.isBlank() || value.codePointCount(0, value.length) > 255 ||
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
            ExpoProgramTime.parse(node.get(field)?.takeIf { it.isString }?.stringValue() ?: invalid())
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
