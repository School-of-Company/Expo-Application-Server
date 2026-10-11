package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.servlet.http.HttpServletRequest
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
import team.startup.application.domain.application.service.RegisterStandardFieldService
import team.startup.application.domain.application.service.RegisterStandardPreService
import team.startup.application.domain.application.service.RegisterTraineeFieldService
import team.startup.application.domain.application.service.RegisterTraineePreService
import team.startup.application.domain.application.service.RegistrationCommand
import team.startup.application.domain.application.service.impl.ParticipantSessionException

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
        execute: (RegistrationCommand) -> Unit,
    ): ResponseEntity<Void> {
        if (!enabled) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "박람회 등록이 비활성화되었습니다.")
        val session =
            httpRequest.cookies
                .orEmpty()
                .filter { it.name == sessionCookieName }
                .singleOrNull()
                ?.value
        execute(RegistrationCommand(expoId, request, key, session))
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }
}
