package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
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
import team.startup.application.domain.application.service.ApplyProgramSessionService
import java.util.UUID

@RestController
@ConditionalOnProperty(name = ["application.program-sessions.enabled"], havingValue = "true")
class ProgramSessionApplicationController(
    private val service: ApplyProgramSessionService,
    @Value("\${APPLICATION_PARTICIPANT_SESSION_COOKIE_NAME:PARTICIPANT_SESSION}") private val cookieName: String,
) {
    @GetMapping("/application/program-session-csrf")
    fun csrf(token: CsrfToken): ResponseEntity<Map<String, String>> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(mapOf("token" to token.token, "headerName" to token.headerName))

    @Operation(summary = "인증된 사전등록 참가자의 프로그램 회차 신청")
    @PostMapping("/application/expos/{expoId}/program-sessions/{programType}/{programId}/applications")
    fun apply(
        @PathVariable expoId: UUID,
        @PathVariable programType: ProgramSessionType,
        @PathVariable programId: Long,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
        @Valid @RequestBody body: ApplyProgramSessionRequest,
        request: HttpServletRequest,
    ): ResponseEntity<ProgramSessionReceipt> {
        val cookies = request.cookies.orEmpty().filter { it.name == cookieName }
        val session =
            cookies.singleOrNull()?.value?.takeIf { it.isNotBlank() }
                ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED")
        if (programId <= 0 || key.isNullOrBlank() || key.length > 200 || !body.answers.isObject) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "프로그램 ID, 멱등 키 또는 답변이 올바르지 않습니다.")
        }
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(
            service.execute(
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
