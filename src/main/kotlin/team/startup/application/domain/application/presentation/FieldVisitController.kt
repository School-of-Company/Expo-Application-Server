package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import team.startup.application.domain.application.presentation.dto.FieldVisitCommand
import team.startup.application.domain.application.presentation.dto.GetOwnerApplicationsCommand
import team.startup.application.domain.application.presentation.dto.RegisterFieldVisitRequest
import team.startup.application.domain.application.service.GetFieldVisitPartyService
import team.startup.application.domain.application.service.RegisterFieldVisitService
import team.startup.application.domain.application.service.impl.ParticipantSessionException
import java.util.UUID

@RestController
@RequestMapping("/application/expos/{expoId}/field-visits")
class FieldVisitController(
    private val getParty: GetFieldVisitPartyService,
    private val register: RegisterFieldVisitService,
    @Value("\${application.owner.session-cookie-name:PARTICIPANT_SESSION}") private val cookieName: String,
) {
    @GetMapping
    @Operation(summary = "오늘(KST) 방문일 등록과 기존 일행 조회")
    fun get(
        @PathVariable expoId: UUID,
        request: HttpServletRequest,
        csrf: CsrfToken,
    ) = ResponseEntity
        .ok()
        .header("Cache-Control", "no-store")
        .header(csrf.headerName, csrf.token)
        .body(getParty.execute(GetOwnerApplicationsCommand(expoId, session(request))))

    @PostMapping
    @Operation(summary = "선택한 기존 참가자와 신규 동행자의 오늘 방문일 등록", description = "세션·CSRF·Idempotency-Key 필수. 기존 참가자의 프로필과 QR은 변경하지 않습니다.")
    fun post(
        @PathVariable expoId: UUID,
        @Valid @RequestBody body: RegisterFieldVisitRequest,
        @RequestHeader("Idempotency-Key") key: String,
        request: HttpServletRequest,
    ) = ResponseEntity
        .ok()
        .header(
            "Cache-Control",
            "no-store",
        ).body(register.execute(FieldVisitCommand(expoId, session(request), key, body)))

    private fun session(request: HttpServletRequest): String =
        request.cookies
            .orEmpty()
            .filter { it.name == cookieName }
            .singleOrNull()
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?: throw ParticipantSessionException("SESSION_REQUIRED")
}
