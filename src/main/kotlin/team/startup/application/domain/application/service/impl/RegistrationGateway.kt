package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

data class ExpoPeriod(
    val startedDay: String,
    val finishedDay: String,
)

data class RegistrationForm(
    val id: String,
    val startDate: String,
    val endDate: String,
    val dynamicForm: List<RegistrationField>,
)

data class RegistrationField(
    val id: Long,
    val title: String,
    val formType: String,
    val jsonData: JsonNode,
    val otherJson: JsonNode?,
    val dynamicFormType: String,
    val requiredStatus: Boolean = false,
)

data class RegistrationQuestion(
    val id: Long,
    val title: String,
    val order: Int,
    val formType: String,
    val jsonData: JsonNode,
    val otherJson: JsonNode?,
    val dynamicFormType: String,
)

data class ParticipantRegistration(
    val expoId: String,
    val name: String,
    val phoneNumber: String,
    val informationJson: String,
    val personalInformationStatus: Boolean,
    val applicationType: String,
    val trainingId: String?,
    val occupation: String?,
    val school: String?,
    val requestId: String,
    val formId: String?,
    val questions: List<RegistrationQuestion>?,
    val region: String? = null,
)

data class ParticipantRegistrationResult(
    val participantId: Long,
    val phoneNumber: String,
    val participantIds: List<Long> = listOf(participantId),
)

data class TraineeRegistrationResult(
    val traineeId: Long,
    val phoneNumber: String,
)

class ParticipantSaveRejectedException(
    status: HttpStatus,
) : ResponseStatusException(status, "User 참가자 저장이 거절되었습니다.")

class ParticipantIdsMismatchException : ResponseStatusException(HttpStatus.BAD_GATEWAY, "User 참가자 ID 응답 확인이 필요합니다.")

@Component
class RegistrationGateway(
    private val mapper: ObjectMapper,
    @Value("\${application.registration.expo-url:}") private val expoUrl: String,
    @Value("\${application.registration.form-url:}") private val formUrl: String,
    @Value("\${application.registration.user-url:}") private val userUrl: String,
    @Value("\${application.internal-token:}") private val internalToken: String,
    @Value("\${application.registration.expo-internal-token:}") private val expoInternalToken: String = internalToken,
    @Value("\${application.registration.form-internal-token:}") private val formInternalToken: String = internalToken,
    @Value("\${application.registration.user-internal-token:}") private val userInternalToken: String = internalToken,
    @Value("\${application.program-sessions.enabled:false}") private val programSessionsEnabled: Boolean = false,
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    fun programDefinition(
        expoId: String,
        type: ProgramSessionType,
        programId: Long,
    ): ProgramSessionDefinition? = programDefinitions(expoId, type, listOf(programId)).singleOrNull()

    fun programDefinitions(
        expoId: String,
        type: ProgramSessionType,
        programIds: List<Long>,
        existing: Boolean = false,
    ): List<ProgramSessionDefinition> {
        if (!programSessionsEnabled || programIds.isEmpty()) return emptyList()
        if (expoUrl.isBlank()) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "프로그램 정책 공급자 주소가 없습니다.")
        val canonicalExpo = UUID.fromString(expoId).toString()
        val ids = programIds.distinct().sorted()
        val nodes =
            if (type == ProgramSessionType.STANDARD) {
                ids.mapNotNull { id ->
                    val response =
                        call(
                            expoUrl,
                            "/internal/expo/$canonicalExpo/standard-programs/$id",
                            allowNotFound = existing,
                            token = expoInternalToken,
                            conflictReason = "프로그램 정책 변경 중입니다.",
                        )
                    if (response.statusCode() == 404) null else decode(response.body(), JsonNode::class.java)
                }
            } else {
                ids.chunked(100).flatMap { trainingDefinitions(canonicalExpo, it, existing) }
            }
        val definitions =
            nodes.map { node ->
                val definition = decode(mapper.writeValueAsString(node), ProgramSessionDefinition::class.java)
                val owner =
                    try {
                        UUID.fromString(definition.expoId).toString()
                    } catch (
                        _: IllegalArgumentException,
                    ) {
                        invalidProgramResponse()
                    }
                if (owner != canonicalExpo || definition.programType != type || definition.id !in ids || definition.revision <= 0) {
                    invalidProgramResponse()
                }
                if (!existing && definition.pendingRevision != null) {
                    throw ResponseStatusException(HttpStatus.CONFLICT, "프로그램 정책 변경 중입니다.")
                }
                definition.copy(expoId = canonicalExpo)
            }
        if (definitions.map { it.id }.distinct().size != definitions.size ||
            (!existing && definitions.size != ids.size)
        ) {
            invalidProgramResponse()
        }
        return definitions
    }

    private fun trainingDefinitions(
        expoId: String,
        ids: List<Long>,
        existing: Boolean,
    ): List<JsonNode> {
        val response =
            call(
                expoUrl,
                "/internal/expo/$expoId/training-programs/batch",
                "POST",
                mapper.writeValueAsString(mapOf("programIds" to ids)),
                allowNotFound = existing,
                token = expoInternalToken,
                conflictReason = "프로그램 정책 변경 중입니다.",
            )
        if (response.statusCode() == 404) {
            if (ids.size == 1) return emptyList()
            return ids.chunked((ids.size + 1) / 2).flatMap { trainingDefinitions(expoId, it, true) }
        }
        val array = decode(response.body(), JsonNode::class.java)
        if (!array.isArray || array.size() != ids.size) invalidProgramResponse()
        val nodes = (0 until array.size()).map { array.get(it) }
        if (nodes.any { !it.path("id").isIntegralNumber } || nodes.map { it.path("id").asLong() }.toSet() != ids.toSet()) {
            invalidProgramResponse()
        }
        return nodes
    }

    fun managedOccupation(
        expoId: String,
        sessionId: String,
        participantId: Long,
    ): String? {
        val session =
            decode(
                call(
                    userUrl,
                    "/internal/participant-sessions/resolve",
                    "POST",
                    mapper.writeValueAsString(mapOf("expoId" to expoId, "sessionId" to sessionId)),
                    token = userInternalToken,
                    participantSession = true,
                ).body(),
                JsonNode::class.java,
            )
        if (!session.path("expoId").isString || session.path("expoId").stringValue() != expoId || !session.path("phoneNumber").isString ||
            session.path("phoneNumber").stringValue().isBlank()
        ) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "참가자 세션 응답이 올바르지 않습니다.")
        }
        val participants =
            decode(
                call(
                    userUrl,
                    "/internal/standard-participants/managed/select",
                    "POST",
                    mapper.writeValueAsString(
                        mapOf(
                            "expoId" to expoId,
                            "phoneNumber" to session.path("phoneNumber").stringValue(),
                            "participantIds" to listOf(participantId),
                        ),
                    ),
                    token = userInternalToken,
                ).body(),
                JsonNode::class.java,
            )
        if (!participants.isArray || participants.size() != 1 || !participants.get(0).path("participantId").isIntegralNumber ||
            participants.get(0).path("participantId").asLong() != participantId
        ) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "관리 참가자 응답이 올바르지 않습니다.")
        }
        val occupation = participants.get(0).get("occupation")
        if (occupation == null || (!occupation.isNull && !occupation.isString)) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "관리 참가자 역할 응답이 올바르지 않습니다.")
        }
        return if (occupation.isNull) null else occupation.stringValue()
    }

    fun resolveTrainee(
        expoId: String,
        participantId: Long,
    ): Long {
        val response =
            decode(
                call(
                    userUrl,
                    "/internal/trainees/resolve-or-create-by-participant",
                    "POST",
                    mapper.writeValueAsString(mapOf("expoId" to expoId, "participantId" to participantId)),
                    token = userInternalToken,
                ).body(),
                JsonNode::class.java,
            )
        val id = response.get("traineeId")
        if (id == null || !id.isIntegralNumber || !id.canConvertToLong() || id.asLong() <= 0) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "연수자 연결 응답이 올바르지 않습니다.")
        }
        return id.asLong()
    }

    private fun invalidProgramResponse(): Nothing = throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "프로그램 정책 응답이 올바르지 않습니다.")

    fun expoPeriod(expoId: String): ExpoPeriod =
        decode(call(expoUrl, "/internal/expo/$expoId", token = expoInternalToken).body(), ExpoPeriod::class.java)

    fun form(
        expoId: String,
        participantType: String,
        applicationType: String,
        required: Boolean,
    ): RegistrationForm? {
        val path = "/internal/forms/$expoId?type=$participantType&applicationType=$applicationType"
        val response = call(formUrl, path, allowNotFound = !required, token = formInternalToken)
        return if (response.statusCode() == 404) null else decode(response.body(), RegistrationForm::class.java)
    }

    @Transactional(propagation = Propagation.NEVER)
    fun participant(
        type: String,
        registration: ParticipantRegistration,
    ): ParticipantRegistrationResult {
        val path = if (type == "STANDARD") "/internal/standard-participants" else "/internal/trainees"
        val response = call(userUrl, path, "POST", mapper.writeValueAsString(registration), token = userInternalToken)
        return if (type == "STANDARD") {
            val body = decode(response.body(), JsonNode::class.java)
            val result =
                if (body.isArray) {
                    if (body.size() !in 1..5) throw ParticipantIdsMismatchException()
                    val ids =
                        (0 until body.size()).map { index ->
                            val id = body.get(index).get("id")
                            if (id == null || !id.isIntegralNumber || !id.canConvertToLong()) invalidParticipantResponse()
                            id.longValue()
                        }
                    ParticipantRegistrationResult(ids.first(), registration.phoneNumber, ids)
                } else {
                    decode(response.body(), ParticipantRegistrationResult::class.java)
                }
            if (result.participantIds.size !in 1..5 || result.participantId <= 0 ||
                result.participantIds.any { it <= 0 } ||
                result.participantIds.distinct().size != result.participantIds.size
            ) {
                throw ParticipantIdsMismatchException()
            }
            result
        } else {
            val trainee = decode(response.body(), TraineeRegistrationResult::class.java)
            if (trainee.traineeId <= 0) invalidParticipantResponse()
            ParticipantRegistrationResult(trainee.traineeId, trainee.phoneNumber)
        }
    }

    private fun invalidParticipantResponse(): Nothing = throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "등록 서비스 참가자 응답이 올바르지 않습니다.")

    fun countStandard(
        expoId: String,
        participantId: Long,
    ) {
        try {
            call(expoUrl, "/internal/expo/$expoId/standard-registrations/$participantId", "PUT", token = expoInternalToken)
        } catch (ex: ResponseStatusException) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "신청 인원을 반영할 수 없습니다.", ex)
        }
    }

    private fun <T> decode(
        body: String,
        type: Class<T>,
    ): T =
        try {
            requireNotNull(mapper.readValue(body, type))
        } catch (ex: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "등록 서비스 응답이 올바르지 않습니다.", ex)
        }

    private fun call(
        base: String,
        path: String,
        method: String = "GET",
        body: String? = null,
        allowNotFound: Boolean = false,
        token: String,
        participantSession: Boolean = false,
        conflictReason: String = "이미 등록된 참가자입니다.",
    ): HttpResponse<String> {
        if (base.isBlank()) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "등록 서비스 연결 주소가 없습니다.")
        val request =
            HttpRequest
                .newBuilder(URI.create(base.trimEnd('/') + path))
                .timeout(Duration.ofSeconds(8))
                .header("X-Internal-Token", token)
                .header("Content-Type", "application/json")
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
                .build()
        val response =
            try {
                client.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (ex: Exception) {
                Thread.currentThread().interruptIfInterrupted(ex)
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "등록 서비스와 연결할 수 없습니다.", ex)
            }
        when (response.statusCode()) {
            in 200..299 -> {
                return response
            }

            401 -> {
                if (participantSession) {
                    throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "참가자 세션이 필요하거나 만료되었습니다.")
                } else {
                    throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "공급자 내부 인증에 실패했습니다.")
                }
            }

            404 -> {
                if (allowNotFound) {
                    return response
                } else if (path == "/internal/standard-participants") {
                    throw ParticipantSaveRejectedException(HttpStatus.NOT_FOUND)
                } else {
                    throw ResponseStatusException(HttpStatus.NOT_FOUND, "등록 대상을 찾을 수 없습니다.")
                }
            }

            409 -> {
                if (path == "/internal/standard-participants") {
                    throw ParticipantSaveRejectedException(HttpStatus.CONFLICT)
                } else {
                    throw ResponseStatusException(HttpStatus.CONFLICT, conflictReason)
                }
            }

            400, 422 -> {
                if (path == "/internal/standard-participants") {
                    throw ParticipantSaveRejectedException(HttpStatus.BAD_GATEWAY)
                } else {
                    throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "등록 서비스 응답이 올바르지 않습니다.")
                }
            }

            in 500..599 -> {
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "등록 서비스가 응답하지 않습니다.")
            }

            else -> {
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "등록 서비스 응답이 올바르지 않습니다.")
            }
        }
    }
}

private fun Thread.interruptIfInterrupted(ex: Exception) {
    if (ex is InterruptedException) interrupt()
}
