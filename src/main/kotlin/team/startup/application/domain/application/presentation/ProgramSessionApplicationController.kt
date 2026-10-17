package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.ApplyProgramSessionCommand
import team.startup.application.domain.application.presentation.dto.ApplyProgramSessionRequest
import team.startup.application.domain.application.presentation.dto.ProgramSessionReceipt
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.service.ApplyOnsiteProgramSessionService
import team.startup.application.domain.application.service.ApplyProgramSessionService
import java.util.UUID

@RestController
@ConditionalOnProperty(name = ["application.program-sessions.enabled"], havingValue = "true")
class ProgramSessionApplicationController(
    private val service: ApplyProgramSessionService,
    private val onsiteService: ApplyOnsiteProgramSessionService,
    @Value("\${application.owner.session-cookie-name:PARTICIPANT_SESSION}") private val cookieName: String,
) {
    @GetMapping("/application/program-session-csrf")
    @Operation(summary = "프로그램 신청 CSRF 토큰 조회", responses = [ApiResponse(responseCode = "200", description = "CSRF 토큰과 헤더 이름")])
    fun csrf(token: CsrfToken): ResponseEntity<Map<String, String>> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(mapOf("token" to token.token, "headerName" to token.headerName))

    @Operation(
        summary = "인증된 사전등록 참가자의 프로그램 회차 신청",
        responses = [
            ApiResponse(responseCode = "201", description = "신청 접수"),
            ApiResponse(responseCode = "400", description = "잘못된 요청 또는 폼 답변"),
            ApiResponse(responseCode = "401", description = "참가자 세션 인증 실패"),
            ApiResponse(responseCode = "403", description = "CSRF 검증 실패 또는 신청 자격 없음"),
            ApiResponse(responseCode = "404", description = "프로그램 또는 공급자 조회 대상 없음"),
            ApiResponse(responseCode = "409", description = "중복 신청, 정원 또는 폼 버전 충돌"),
            ApiResponse(responseCode = "502", description = "공급자 응답 계약 또는 내부 인증 오류"),
            ApiResponse(responseCode = "503", description = "정책·폼 공급자 계약 또는 연동 불가"),
        ],
    )
    @PostMapping("/application/expos/{expoId}/program-sessions/{programType}/{programId}/applications")
    fun apply(
        @PathVariable expoId: UUID,
        @PathVariable programType: ProgramSessionType,
        @PathVariable programId: Long,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
        @Valid @RequestBody body: ApplyProgramSessionRequest,
        request: HttpServletRequest,
    ): ResponseEntity<ProgramSessionReceipt> = submit(expoId, programType, programId, key, body, request, service::execute)

    @Operation(
        summary = "인증된 박람회 참가자의 프로그램 회차 현장 신청",
        description =
            "사전등록 확정자 또는 현장 등록자만 신청한다. 프로그램 폼 기간 안에서 현장 정원 내 즉시 확정하고, 현장 좌석이 없으면 409이며 대기는 없다.",
        responses = [
            ApiResponse(responseCode = "201", description = "현장 신청 확정"),
            ApiResponse(responseCode = "400", description = "잘못된 요청 또는 폼 답변"),
            ApiResponse(responseCode = "401", description = "참가자 세션 인증 실패"),
            ApiResponse(responseCode = "403", description = "CSRF 검증 실패 또는 신청 자격 없음"),
            ApiResponse(responseCode = "404", description = "프로그램 또는 공급자 조회 대상 없음"),
            ApiResponse(responseCode = "409", description = "중복 신청, 현장 정원·폼 기간 또는 폼 버전 충돌"),
            ApiResponse(responseCode = "502", description = "공급자 응답 계약 또는 내부 인증 오류"),
            ApiResponse(responseCode = "503", description = "정책·폼 공급자 계약 또는 연동 불가"),
        ],
    )
    @PostMapping("/application/expos/{expoId}/program-sessions/{programType}/{programId}/onsite-applications")
    fun applyOnsite(
        @PathVariable expoId: UUID,
        @PathVariable programType: ProgramSessionType,
        @PathVariable programId: Long,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
        @Valid @RequestBody body: ApplyProgramSessionRequest,
        request: HttpServletRequest,
    ): ResponseEntity<ProgramSessionReceipt> = submit(expoId, programType, programId, key, body, request, onsiteService::execute)

    private fun submit(
        expoId: UUID,
        programType: ProgramSessionType,
        programId: Long,
        key: String?,
        body: ApplyProgramSessionRequest,
        request: HttpServletRequest,
        execute: (ApplyProgramSessionCommand) -> ProgramSessionReceipt,
    ): ResponseEntity<ProgramSessionReceipt> {
        val cookies = request.cookies.orEmpty().filter { it.name == cookieName }
        val session =
            cookies.singleOrNull()?.value?.takeIf { it.isNotBlank() }
                ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED")
        if (programId <= 0 || key.isNullOrBlank() || key.length > 200 || !body.answers.isObject) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "프로그램 ID, 멱등 키 또는 답변이 올바르지 않습니다.")
        }
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(
            execute(
                ApplyProgramSessionCommand(
                    expoId.toString(),
                    programType,
                    programId,
                    body.participantId,
                    body.formVersion,
                    body.answers,
                    session,
                    key,
                ),
            ),
        )
    }
}
