package team.startup.application.domain.application.service.impl

import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.FieldParticipantResolution
import team.startup.application.domain.application.presentation.dto.FieldVisitRegistration
import team.startup.application.domain.application.presentation.dto.ManagedOwnerParticipant
import team.startup.application.domain.application.presentation.dto.OwnerApplicationParty
import team.startup.application.domain.application.presentation.dto.ParticipantQrResponse
import team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.presentation.dto.RegisterFieldVisitRequest
import team.startup.application.domain.application.presentation.dto.hasValidPlace
import team.startup.application.domain.application.repository.FieldVisitRegistrationRepository
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.UUID

data class ExpoPeriod(
    val startedDay: String,
    val finishedDay: String,
    val participantPhoneVerificationRequired: Boolean = false,
    val participantPhoneVerificationRevision: Long = 0,
)

data class RegistrationForm(
    val id: String,
    val startDate: String,
    val endDate: String,
    val dynamicForm: List<RegistrationField>,
    /** 교원·예비교사 외 유형의 사전등록 시작 시각(#209). Form이 내려주기 전이나 null이면 제한 없다. */
    val occupationOpenAt: OffsetDateTime? = null,
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
    /**
     * 이 대표자의 동행자 중 이 서비스 원장에서 신청이 취소된 참가자 ID(#190). User가 누적 인원 한도에서 뺀다.
     * 멱등 fingerprint가 본문으로 만들어지므로 없거나 비면 싣지 않아 이 필드가 생기기 전 요청과 같다.
     */
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val releasedParticipantIds: List<Long>? = null,
    /**
     * 같은 번호의 대표자에게 활성 신청이 하나도 없을 때만 true(#237). User는 기존 대표자를 이번 요청 값으로 바꾸고 기존 동행자를 모두 일행에서 뺀다.
     * null이면 싣지 않아 이 필드가 생기기 전 요청과 같다.
     */
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val replaceRepresentative: Boolean? = null,
)

data class ManagedParticipantSelection(
    val occupation: String?,
    val fieldRegistered: Boolean,
)

data class ParticipantRegistrationResult(
    val participantId: Long,
    val phoneNumber: String,
    val participantIds: List<Long> = listOf(participantId),
    val groupParticipantIds: List<Long> = participantIds,
    /** 이번 요청으로 User가 새로 만든 참가자. 없으면 알 수 없다. */
    val createdIds: List<Long>? = null,
    /** #275: 이번 요청으로 User가 FIELD로 만든 참가자. 같은 키 재시도는 처음 목록, 새 키 재전송은 빈 목록, 옛 User 응답이면 null이다. */
    val fieldParticipantIds: List<Long>? = null,
)

data class TraineeRegistrationResult(
    val traineeId: Long,
    val phoneNumber: String,
)

class ParticipantSaveRejectedException(
    status: HttpStatus,
) : ResponseStatusException(status, "User 참가자 저장이 거절되었습니다.")

class ParticipantIdsMismatchException : ResponseStatusException(HttpStatus.BAD_GATEWAY, "User 참가자 ID 응답 확인이 필요합니다.")

class ParticipantSessionException(
    val code: String,
) : ResponseStatusException(HttpStatus.UNAUTHORIZED, code)

@Component
class RegistrationGateway(
    private val mapper: ObjectMapper,
    @Value("\${application.registration.expo-url:}") private val expoUrl: String,
    @Value("\${application.registration.form-url:}") private val formUrl: String,
    @Value("\${application.registration.user-url:}") private val userUrl: String,
    @Value("\${application.registration.expo-internal-token:}") private val expoInternalToken: String,
    @Value("\${application.registration.form-internal-token:}") private val formInternalToken: String,
    @Value("\${application.registration.user-internal-token:}") private val userInternalToken: String,
    @Value("\${application.program-sessions.enabled:false}") private val programSessionsEnabled: Boolean = false,
    @Value("\${application.attendance.url:}") private val attendanceUrl: String = "",
    @Value("\${application.attendance.internal-token:}") private val attendanceToken: String = "",
    @Value("\${application.registration.enabled:false}") registrationEnabled: Boolean = false,
    @Value("\${application.field-visits.enabled:false}") private val fieldVisitsEnabled: Boolean = false,
    private val fieldVisits: FieldVisitRegistrationRepository? = null,
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    init {
        if (registrationEnabled) {
            mapOf("EXPO" to expoInternalToken, "FORM" to formInternalToken, "USER" to userInternalToken).forEach { (target, token) ->
                require(
                    token.isNotBlank(),
                ) { "APPLICATION_REGISTRATION_${target}_INTERNAL_TOKEN must be configured when registration is enabled" }
            }
            if (fieldVisitsEnabled) {
                require(attendanceUrl.isNotBlank() && attendanceToken.isNotBlank()) {
                    "APPLICATION_ATTENDANCE_URL and APPLICATION_ATTENDANCE_INTERNAL_TOKEN must be configured for field visits"
                }
            }
        }
    }

    fun programForm(formId: UUID): JsonNode =
        decode(call(formUrl, "/internal/forms/by-id/$formId", token = formInternalToken).body(), JsonNode::class.java)

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
                if (owner != canonicalExpo || definition.programType != type || definition.id !in ids || definition.revision <= 0 ||
                    !definition.hasValidPlace()
                ) {
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

    fun participantOccupations(
        expoId: String,
        participantIds: Set<Long>,
    ): Map<Long, String?> {
        val ids = participantIds.sorted()
        val response =
            decode(
                call(
                    userUrl,
                    "/internal/standard-participants/details",
                    "POST",
                    mapper.writeValueAsString(mapOf("expoId" to expoId, "participantIds" to ids)),
                    token = userInternalToken,
                ).body(),
                JsonNode::class.java,
            )
        if (!response.isArray || response.size() != ids.size) invalidManagedResponse()
        return ids
            .mapIndexed { index, id ->
                val node = response[index]
                val actual = node.path("participantId")
                val occupation = node.get("occupation") ?: invalidManagedResponse()
                if (!actual.isIntegralNumber || !actual.canConvertToLong() || actual.asLong() != id ||
                    (!occupation.isNull && !occupation.isString)
                ) {
                    invalidManagedResponse()
                }
                id to occupation.takeUnless { it.isNull }?.stringValue()
            }.toMap()
    }

    fun managedParticipant(
        expoId: String,
        sessionId: String,
        participantId: Long,
    ): ManagedParticipantSelection {
        val phoneNumber = participantSessionPhone(expoId, sessionId)
        if (fieldVisitsEnabled &&
            fieldVisits?.find(UUID.fromString(expoId), participantId, fieldVisitToday())?.cancellationPending == true
        ) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "FIELD_CANCELLATION_PENDING")
        }
        val participants =
            decode(
                call(
                    userUrl,
                    "/internal/standard-participants/managed/select",
                    "POST",
                    mapper.writeValueAsString(
                        mapOf("expoId" to expoId, "phoneNumber" to phoneNumber, "participantIds" to listOf(participantId)),
                    ),
                    token = userInternalToken,
                ).body(),
                JsonNode::class.java,
            )
        if (!participants.isArray || participants.size() != 1 || !participants.get(0).path("participantId").isIntegralNumber ||
            !participants.get(0).path("participantId").canConvertToLong() ||
            participants.get(0).path("participantId").asLong() != participantId
        ) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "관리 참가자 응답이 올바르지 않습니다.")
        }
        val occupation = participants.get(0).get("occupation")
        if (occupation == null || (!occupation.isNull && !occupation.isString)) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "관리 참가자 역할 응답이 올바르지 않습니다.")
        }
        return ManagedParticipantSelection(
            if (occupation.isNull) null else occupation.stringValue(),
            fieldRegistered(participants.get(0), UUID.fromString(expoId), participantId),
        )
    }

    // 옛 User 응답에는 applicationType이 없다. 없으면 현장 등록으로 보지 않아 현장 자격이 닫힌 채로 남는다.
    private fun fieldRegistered(
        node: JsonNode,
        expoId: UUID,
        participantId: Long,
    ): Boolean {
        if (fieldVisitsEnabled) {
            val visit = fieldVisits?.find(expoId, participantId, fieldVisitToday())
            return visit?.status == "ACTIVE" && !visit.cancellationPending && !visit.deliveryPending
        }
        val type = node.get("applicationType") ?: return false
        if (!type.isString || type.stringValue() !in setOf("PRE", "FIELD")) invalidManagedResponse()
        return type.stringValue() == "FIELD"
    }

    private fun fieldVisitToday() = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))

    fun managedParty(
        expoId: UUID,
        sessionId: String,
    ): OwnerApplicationParty {
        if (sessionId.isBlank()) throw ParticipantSessionException("SESSION_REQUIRED")
        val phoneNumber = participantSessionPhone(expoId.toString(), sessionId)
        val response =
            decode(
                call(
                    userUrl,
                    "/internal/standard-participants/managed",
                    "POST",
                    mapper.writeValueAsString(mapOf("expoId" to expoId, "phoneNumber" to phoneNumber)),
                    token = userInternalToken,
                ).body(),
                JsonNode::class.java,
            )
        val registered = response.get("registered")
        val participants = response.get("participants")
        if (registered == null || !registered.isBoolean || participants == null || !participants.isArray) {
            invalidManagedResponse()
        }
        val party =
            OwnerApplicationParty(
                (0 until participants.size()).map { index ->
                    val node = participants.get(index)
                    val id = node.get("participantId")
                    val name = node.get("name")
                    val occupation = node.get("occupation")
                    val representative = node.get("representative")
                    val school = node.get("school")
                    if (id == null || !id.isIntegralNumber || !id.canConvertToLong() || id.asLong() <= 0 ||
                        name == null || !name.isString || occupation == null || (!occupation.isNull && !occupation.isString) ||
                        representative == null || !representative.isBoolean || (school != null && !school.isNull && !school.isString)
                    ) {
                        invalidManagedResponse()
                    }
                    ManagedOwnerParticipant(
                        id.asLong(),
                        name.stringValue(),
                        if (occupation.isNull) null else occupation.stringValue(),
                        representative.asBoolean(),
                        fieldRegistered(node, expoId, id.asLong()),
                        school?.takeIf { it.isString }?.stringValue(),
                        school != null,
                        node.path("applicationType").asString() == "FIELD",
                    )
                },
            )
        if (party.participants
                .map { it.participantId }
                .distinct()
                .size != party.participants.size ||
            (registered.asBoolean() && (party.participants.isEmpty() || party.representativeId == null)) ||
            (!registered.asBoolean() && party.participants.isNotEmpty())
        ) {
            invalidManagedResponse()
        }
        return party
    }

    private fun invalidManagedResponse(): Nothing = throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "관리 참가자 응답이 올바르지 않습니다.")

    fun participantSessionPhone(
        expoId: String,
        sessionId: String,
    ): String {
        if (sessionId.isBlank()) throw ParticipantSessionException("SESSION_REQUIRED")
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
            !session.path("phoneNumber").stringValue().matches(Regex("01[0-9]{8,9}")) || !session.path("expiresAt").isString
        ) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "참가자 세션 응답이 올바르지 않습니다.")
        }
        val expiresAt =
            try {
                LocalDateTime.parse(session.path("expiresAt").stringValue())
            } catch (ex: DateTimeParseException) {
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "참가자 세션 응답이 올바르지 않습니다.", ex)
            }
        if (!expiresAt.isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            throw ParticipantSessionException("SESSION_EXPIRED")
        }
        return session.path("phoneNumber").stringValue()
    }

    fun resolveTrainee(
        expoId: String,
        participantId: Long,
        trainingId: String? = null,
    ): Long {
        val response =
            decode(
                call(
                    userUrl,
                    "/internal/trainees/resolve-or-create-by-participant",
                    "POST",
                    mapper.writeValueAsString(mapOf("expoId" to expoId, "participantId" to participantId, "trainingId" to trainingId)),
                    token = userInternalToken,
                    conflictReason = "연수자 연결이 거부되었습니다.",
                ).body(),
                JsonNode::class.java,
            )
        val id = response.get("traineeId")
        if (id == null || !id.isIntegralNumber || !id.canConvertToLong() || id.asLong() <= 0) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "연수자 연결 응답이 올바르지 않습니다.")
        }
        return id.asLong()
    }

    fun participantQr(
        expoId: UUID,
        participantId: Long,
        representativeId: Long,
    ): ParticipantQrResponse {
        if (attendanceToken.isBlank()) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
        val response =
            call(
                attendanceUrl,
                "/internal/expos/$expoId/participants/$participantId/qr",
                "POST",
                mapper.writeValueAsString(mapOf("representativeId" to representativeId)),
                token = attendanceToken,
            ).body()
        val node =
            try {
                requireNotNull(mapper.readTree(response))
            } catch (_: Exception) {
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "QR 공급자 응답이 올바르지 않습니다.")
            }
        val id = node.path("participantId")
        val code = node.path("code")
        if (!id.isIntegralNumber || !id.canConvertToLong() || id.asLong() != participantId || !code.isString ||
            code.stringValue().isBlank()
        ) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "QR 공급자 응답이 올바르지 않습니다.")
        }
        return ParticipantQrResponse(participantId, code.stringValue())
    }

    private fun invalidProgramResponse(): Nothing = throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "프로그램 정책 응답이 올바르지 않습니다.")

    fun expoPeriod(expoId: String): ExpoPeriod {
        val response = call(expoUrl, "/internal/expo/$expoId", token = expoInternalToken)
        val node = decode(response.body(), JsonNode::class.java)
        val required = node.path("participantPhoneVerificationRequired")
        val revision = node.path("participantPhoneVerificationRevision")
        if (response.statusCode() != 200 || !required.isBoolean || !revision.isIntegralNumber ||
            !revision.canConvertToLong() || revision.longValue() < 0
        ) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "박람회 전화번호 인증 설정 응답이 올바르지 않습니다.")
        }
        return decode(response.body(), ExpoPeriod::class.java)
    }

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
            body.get("groupParticipantIds")?.let { ids ->
                if (!ids.isArray || ids.any { !it.isIntegralNumber || !it.canConvertToLong() }) {
                    throw ParticipantIdsMismatchException()
                }
            }
            val raw =
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
            val result = withoutReleased(registration, raw)
            if (result.participantIds.size !in 1..5 || result.participantId <= 0 ||
                result.participantIds.any { it <= 0 } ||
                result.participantIds.distinct().size != result.participantIds.size ||
                result.groupParticipantIds.size !in 1..5 ||
                result.groupParticipantIds.any { it <= 0 } ||
                result.groupParticipantIds.distinct().size != result.groupParticipantIds.size ||
                result.fieldParticipantIds?.let { !result.groupParticipantIds.containsAll(it) } == true ||
                (
                    body.has("groupParticipantIds") &&
                        (
                            result.groupParticipantIds.first() != result.participantId ||
                                !result.groupParticipantIds.containsAll(result.participantIds)
                        )
                )
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

    fun resolveFieldParticipants(
        expoId: UUID,
        phoneNumber: String,
        requestId: String,
        request: RegisterFieldVisitRequest,
        form: RegistrationForm? = null,
    ): List<FieldParticipantResolution> {
        if (request.newParticipants.isEmpty()) return emptyList()
        val response =
            fieldVisitCall(
                userUrl,
                "/internal/expos/$expoId/field-visit-participants",
                "POST",
                mapOf(
                    "phoneNumber" to phoneNumber,
                    "requestId" to requestId,
                    "participants" to request.newParticipants,
                    "personalInformationStatus" to request.personalInformationStatus,
                    "formId" to form?.id,
                    "questions" to
                        form?.dynamicForm?.mapIndexed { order, field ->
                            RegistrationQuestion(
                                field.id,
                                field.title,
                                order,
                                field.formType,
                                field.jsonData,
                                field.otherJson,
                                field.dynamicFormType,
                            )
                        },
                ),
                userInternalToken,
            )
        val node = decode(response.body(), JsonNode::class.java)
        if (!node.isArray) throw ParticipantIdsMismatchException()
        val result =
            (0 until node.size()).map {
                decode(
                    node.get(it).toString(),
                    FieldParticipantResolution::class.java,
                )
            }
        if (result.size != request.newParticipants.size ||
            result.map { it.clientReference }.toSet() != request.newParticipants.map { it.clientReference }.toSet() ||
            result.any { it.participantId <= 0 } || result.map { it.participantId }.distinct().size != result.size ||
            result.any { it.participantId in request.participantIds }
        ) {
            throw ParticipantIdsMismatchException()
        }
        return result
    }

    fun projectFieldVisit(registration: FieldVisitRegistration) {
        val path = "/internal/expos/${registration.expoId}/participants/${registration.participantId}/field-visits/${registration.id}"
        val operationId = UUID.nameUUIDFromBytes("${registration.id}:${registration.generation}:${registration.status}".toByteArray())
        val body =
            mapOf(
                "visitDate" to registration.visitDate,
                "generation" to registration.generation,
                "status" to registration.status,
                "operationId" to operationId,
            )
        fieldVisitCall(attendanceUrl, path, "PUT", body, attendanceToken)
        fieldVisitCall(userUrl, path, "PUT", body, userInternalToken)
    }

    private fun fieldVisitCall(
        base: String,
        path: String,
        method: String,
        body: Any,
        token: String,
    ): HttpResponse<String> {
        try {
            val response = call(base, path, method, mapper.writeValueAsString(body), token = token, fieldVisitContract = true)
            if (method == "PUT" && response.statusCode() != 204) {
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "현장 방문일 공급자 응답이 올바르지 않습니다.")
            }
            return response
        } catch (ex: ResponseStatusException) {
            if (ex.statusCode.value() in setOf(404, 405, 501)) {
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "현장 방문일 공급자 계약이 필요합니다.")
            }
            throw ex
        }
    }

    // #196: User 재신청 응답은 이 서비스에서 취소된 동행자(releasedParticipantIds)도 일행에 담는다. 기존 동행자를 아무도 다시 신청하지
    // 않았을 때(이번 일행 = 대표자 + 새로 만든 동행자)만 뺀다. 다시 신청한 동행자는 User가 같은 ID를 돌려주므로 어느 ID인지 알 수 없어
    // 그대로 두고, 인원이 맞지 않으면 완료 단계가 검토로 남긴다.
    private fun withoutReleased(
        registration: ParticipantRegistration,
        result: ParticipantRegistrationResult,
    ): ParticipantRegistrationResult {
        val released = result.groupParticipantIds.toSet() intersect registration.releasedParticipantIds.orEmpty().toSet()
        val created = result.createdIds ?: return result
        if (released.isEmpty() || 1 + created.size != PreregisterSeatCategory.partyOccupations(registration, mapper).size) {
            return result
        }
        return result.copy(participantIds = result.participantIds - released, groupParticipantIds = result.groupParticipantIds - released)
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
        fieldVisitContract: Boolean = false,
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
        if (fieldVisitContract && response.statusCode() in setOf(404, 405, 501)) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "현장 방문일 공급자 계약이 필요합니다.")
        }
        when (response.statusCode()) {
            in 200..299 -> {
                return response
            }

            401 -> {
                if (participantSession) {
                    val code =
                        try {
                            mapper.readTree(response.body()).path("code").stringValue()
                        } catch (_: Exception) {
                            null
                        }
                    throw ParticipantSessionException(
                        if (code == "SESSION_EXPIRED") code else "SESSION_REQUIRED",
                    )
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
