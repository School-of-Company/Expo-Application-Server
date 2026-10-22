package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.PreregisterRegistrationReceipt
import team.startup.application.domain.application.service.RegisterStandardFieldService
import team.startup.application.domain.application.service.RegisterStandardPreService
import team.startup.application.domain.application.service.RegisterTraineeFieldService
import team.startup.application.domain.application.service.RegisterTraineePreService
import team.startup.application.domain.application.service.RegistrationCommand
import team.startup.application.domain.application.service.impl.OccupationNotOpenException
import team.startup.application.domain.application.service.impl.ParticipantSessionException
import team.startup.application.domain.application.service.impl.RegistrationWorkflow
import team.startup.application.domain.application.service.impl.WaitlistWithdrawnException

@Schema(description = "박람회 참가자 등록 요청")
data class RegistrationRequest(
    @field:NotBlank @field:Size(max = 10) @field:Schema(description = "참가자 이름", example = "홍길동") val name: String,
    @field:NotBlank @field:Size(max = 15) @field:Schema(description = "입력 전화번호. 인증 대상 행사는 세션의 검증 번호를 사용", example = "01000000000")
    val phoneNumber: String,
    @field:NotBlank @field:Schema(description = "문항 제목을 키로 한 JSON 문자열", example = "{}") val informationJson: String,
    @field:Schema(description = "개인정보 동의 여부", example = "true") val personalInformationStatus: Boolean,
    @field:Size(max = 15) @field:Schema(description = "연수자 등록에 사용하는 선택 값", example = "TRAINING-1")
    val trainingId: String? = null,
    @field:jakarta.validation.constraints.Positive @field:Schema(description = "일반 사전등록 회차 ID", example = "1")
    val sessionId: Long? = null,
)

// #238: 서비스의 409 사유 문구 -> (code, 화면 문구). 다른 사람의 개인정보는 싣지 않는다.
// ponytail: 사유 문구를 키로 쓴다. 문구가 바뀌면 CONFLICT로 떨어지므로 PreregisterRegistrationHttpTests가 code를 단언한다.
private val conflictCodes =
    run {
        val pending = "REQUEST_PENDING" to "이전 신청을 확인하는 중입니다. 나의 신청 현황을 확인하거나 행사 담당자에게 문의해 주세요."
        // User는 같은 번호 일행이 대표자 포함 누적 5명을 넘으면 409로 저장을 거절한다.
        val partyLimit = "PARTY_LIMIT_EXCEEDED" to "이 번호로 등록된 일행과 합쳐 대표자를 포함해 최대 5명까지 신청할 수 있습니다."
        val session = "SESSION_UNAVAILABLE" to "회차 정보가 바뀌었습니다. 새로고침 후 다시 신청해 주세요."
        mapOf(
            "대표자의 활성 신청 또는 복구 중인 신청이 있습니다." to
                ("PHONE_IN_USE" to "이 번호로 이미 신청한 내역이 있습니다. 나의 신청 현황에서 확인해 주세요."),
            "일행 전원을 수용할 수 없습니다." to ("SEATS_UNAVAILABLE" to "남은 자리가 부족해 일행 전원을 신청할 수 없습니다."),
            "마감된 회차입니다." to ("SESSION_CLOSED" to "신청이 마감된 회차입니다."),
            "같은 신청 키의 내용이 다릅니다." to
                ("IDEMPOTENCY_KEY_REUSED" to "같은 신청 키로 다른 내용을 보낼 수 없습니다. 처음부터 다시 신청해 주세요."),
            "User 계약 확인과 운영자 복구가 필요합니다. 좌석을 유지합니다." to pending,
            "이미 활성 신청이 있는 참가자입니다. 좌석을 유지합니다." to pending,
            "User 참가자 저장이 거절되었습니다." to partyLimit,
            "참가자 저장이 거절되었습니다." to partyLimit,
            "삭제된 회차입니다." to session,
            "회차 변경 확인이 필요합니다." to session,
            "승인되지 않은 회차 정의 변경입니다." to session,
            "박람회 삭제 또는 회차 변경 중입니다." to session,
            "삭제된 박람회입니다." to ("EXPO_DELETED" to "삭제된 박람회입니다."),
            RegistrationWorkflow.PREREGISTERED_TODAY to
                ("ALREADY_PREREGISTERED_TODAY" to "이미 사전등록하셨습니다. 받으신 QR로 입장해 주세요."),
            "FIELD_VISIT_SELECTION_REQUIRED" to
                ("FIELD_VISIT_SELECTION_REQUIRED" to "방문일 등록 화면에서 기존 참가자를 선택하거나 새 동행자를 추가해 주세요."),
        )
    }

@RestController
class RegistrationController(
    private val traineePreService: RegisterTraineePreService,
    private val standardPreService: RegisterStandardPreService,
    private val traineeFieldService: RegisterTraineeFieldService,
    private val standardFieldService: RegisterStandardFieldService,
    @Value("\${application.registration.enabled:false}") private val enabled: Boolean,
    @Value("\${application.owner.session-cookie-name:PARTICIPANT_SESSION}") private val sessionCookieName: String = "PARTICIPANT_SESSION",
) {
    @ExceptionHandler(ParticipantSessionException::class)
    fun sessionError(ex: ParticipantSessionException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("status" to 401, "code" to ex.code))

    @ExceptionHandler(OccupationNotOpenException::class)
    fun occupationNotOpen(ex: OccupationNotOpenException): ResponseEntity<Map<String, Any>> =
        ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(mapOf("status" to 400, "code" to "OCCUPATION_NOT_OPEN", "message" to ex.reason.orEmpty()))

    @ExceptionHandler(WaitlistWithdrawnException::class)
    fun withdrawn(): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("status" to 409, "code" to "WAITLIST_WITHDRAWN"))

    // #238: 409 사유별 code와 화면 문구를 싣는다. 409가 아니면 ResponseStatusExceptionResolver와 같이 sendError로 기본 오류 응답에 맡긴다.
    @ExceptionHandler(ResponseStatusException::class)
    fun conflict(
        ex: ResponseStatusException,
        response: HttpServletResponse,
    ): ResponseEntity<Map<String, Any>>? {
        if (ex.statusCode.value() != 409) {
            response.sendError(ex.statusCode.value(), ex.reason)
            return null
        }
        val (code, message) = conflictCodes[ex.reason] ?: ("CONFLICT" to ex.reason.orEmpty())
        return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("status" to 409, "code" to code, "message" to message))
    }

    @Operation(
        summary = "연수자 사전 등록",
        responses = [
            ApiResponse(responseCode = "201", description = "등록 완료"),
            ApiResponse(responseCode = "400", description = "입력 검증 또는 등록·폼 규칙 위반"),
            ApiResponse(responseCode = "401", description = "등록 비활성 시 인증 필요 또는 참가자 세션 인증 실패"),
            ApiResponse(responseCode = "404", description = "박람회, 폼 또는 회차 없음"),
            ApiResponse(responseCode = "409", description = "중복 등록, 정원 또는 공급자 상태 충돌"),
            ApiResponse(responseCode = "502", description = "공급자 응답 계약 또는 내부 인증 오류"),
            ApiResponse(responseCode = "503", description = "등록 비활성 또는 공급자 연동 불가"),
        ],
    )
    @PostMapping("/application/{expoId}")
    fun traineePre(
        @PathVariable expoId: String,
        @Valid @RequestBody request: RegistrationRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) key: String?,
        httpRequest: HttpServletRequest,
    ) = register(expoId, request, key, httpRequest, traineePreService::execute)

    @Operation(
        summary = "일반 사전 등록",
        description =
            "일행 전체를 확정하거나, 확정석이 부족하고 대기 접수가 켜져 있으면 일행 전체를 대기로 받습니다. " +
                "같은 Idempotency-Key 재시도는 대기를 새로 만들지 않고 현재 상태를 돌려줍니다.",
        responses = [
            ApiResponse(responseCode = "201", description = "등록 완료(확정)"),
            ApiResponse(responseCode = "202", description = "대기 접수(WAITING) 또는 승급 뒤 User 저장 중(PROCESSING). QR 없음"),
            ApiResponse(responseCode = "400", description = "입력 검증 또는 등록·폼 규칙 위반"),
            ApiResponse(responseCode = "401", description = "등록 비활성 시 인증 필요 또는 참가자 세션 인증 실패"),
            ApiResponse(responseCode = "404", description = "박람회, 폼 또는 회차 없음"),
            ApiResponse(
                responseCode = "409",
                description =
                    "{status:409, code, message}. code: PHONE_IN_USE(같은 번호의 활성·처리 중 신청), SEATS_UNAVAILABLE(좌석·대기 부족), " +
                        "SESSION_CLOSED(마감), REQUEST_PENDING(이전 신청 확인 중), IDEMPOTENCY_KEY_REUSED(같은 키 다른 내용), " +
                        "PARTY_LIMIT_EXCEEDED(번호의 누적 일행 5명 초과), SESSION_UNAVAILABLE(회차 삭제·변경 중), EXPO_DELETED, " +
                        "WAITLIST_WITHDRAWN(취소된 대기의 같은 키 재시도, message 없음), 그 밖은 CONFLICT",
            ),
            ApiResponse(responseCode = "502", description = "공급자 응답 계약 또는 내부 인증 오류"),
            ApiResponse(responseCode = "503", description = "등록 비활성 또는 공급자 연동 불가"),
        ],
    )
    @PostMapping("/application/pre-standard/{expoId}")
    fun standardPre(
        @PathVariable expoId: String,
        @Valid @RequestBody request: RegistrationRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) key: String?,
        httpRequest: HttpServletRequest,
    ) = register(expoId, request, key, httpRequest, standardPreService::execute)

    @Operation(
        summary = "연수자 현장 등록",
        responses = [
            ApiResponse(responseCode = "201", description = "등록 완료"),
            ApiResponse(responseCode = "400", description = "입력 검증 또는 등록·폼 규칙 위반"),
            ApiResponse(responseCode = "401", description = "등록 비활성 시 인증 필요 또는 참가자 세션 인증 실패"),
            ApiResponse(responseCode = "404", description = "박람회, 폼 또는 회차 없음"),
            ApiResponse(responseCode = "409", description = "중복 등록, 정원 또는 공급자 상태 충돌"),
            ApiResponse(responseCode = "502", description = "공급자 응답 계약 또는 내부 인증 오류"),
            ApiResponse(responseCode = "503", description = "등록 비활성 또는 공급자 연동 불가"),
        ],
    )
    @PostMapping("/application/field/{expoId}")
    fun traineeField(
        @PathVariable expoId: String,
        @Valid @RequestBody request: RegistrationRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) key: String?,
        httpRequest: HttpServletRequest,
    ) = register(expoId, request, key, httpRequest, traineeFieldService::execute)

    @Operation(
        summary = "일반 현장 등록",
        description =
            "방문일 원장 기능 활성화 시 409 FIELD_VISIT_SELECTION_REQUIRED입니다. " +
                "/application/expos/{expoId}/field-visits에서 기존 참가자 선택과 신규 동행자 추가를 구분합니다. " +
                "기능 비활성화 시에는 기존 전화번호 단위 현장등록 계약을 사용합니다.",
        responses = [
            ApiResponse(responseCode = "201", description = "등록 완료"),
            ApiResponse(responseCode = "400", description = "입력 검증 또는 등록·폼 규칙 위반"),
            ApiResponse(responseCode = "401", description = "등록 비활성 시 인증 필요 또는 참가자 세션 인증 실패"),
            ApiResponse(responseCode = "404", description = "박람회, 폼 또는 회차 없음"),
            ApiResponse(
                responseCode = "409",
                description =
                    "{status:409, code, message}. code: ALREADY_PREREGISTERED_TODAY(오늘 회차에 확정된 사전등록이 있음), " +
                        "PARTY_LIMIT_EXCEEDED, 그 밖은 CONFLICT",
            ),
            ApiResponse(responseCode = "502", description = "공급자 응답 계약 또는 내부 인증 오류"),
            ApiResponse(responseCode = "503", description = "등록 비활성 또는 공급자 연동 불가"),
        ],
    )
    @PostMapping("/application/field/standard/{expoId}")
    fun standardField(
        @PathVariable expoId: String,
        @Valid @RequestBody request: RegistrationRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) key: String?,
        httpRequest: HttpServletRequest,
    ) = register(expoId, request, key, httpRequest, standardFieldService::execute)

    private fun register(
        expoId: String,
        request: RegistrationRequest,
        key: String?,
        httpRequest: HttpServletRequest,
        execute: (RegistrationCommand) -> Any?,
    ): ResponseEntity<Any> {
        if (!enabled) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "박람회 등록이 비활성화되었습니다.")
        val session =
            httpRequest.cookies
                .orEmpty()
                .filter { it.name == sessionCookieName }
                .singleOrNull()
                ?.value
        val receipt = execute(RegistrationCommand(expoId, request, key, session))
        if (receipt is PreregisterRegistrationReceipt) return ResponseEntity.status(HttpStatus.ACCEPTED).body(receipt)
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }
}
