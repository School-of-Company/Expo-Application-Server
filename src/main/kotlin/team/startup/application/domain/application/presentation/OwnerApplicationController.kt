package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.CancelOwnerApplicationCommand
import team.startup.application.domain.application.presentation.dto.GetOwnerApplicationsCommand
import team.startup.application.domain.application.presentation.dto.OwnerApplicationType
import team.startup.application.domain.application.presentation.dto.OwnerApplicationsResponse
import team.startup.application.domain.application.service.CancelOwnerApplicationService
import team.startup.application.domain.application.service.GetOwnerApplicationsService
import java.util.UUID

@RestController
@RequestMapping("/application/expos/{expoId}/my-applications")
class OwnerApplicationController(
    private val getApplications: GetOwnerApplicationsService,
    private val cancelApplication: CancelOwnerApplicationService,
    @Value("\${application.owner.session-cookie-name:PARTICIPANT_SESSION}") private val sessionCookieName: String,
) {
    @GetMapping
    @Operation(summary = "내 일행 신청 조회", description = "참가자 세션 쿠키로 일행을 확인합니다. 응답 X-CSRF-TOKEN과 JSESSIONID를 취소 요청에 사용합니다.")
    fun get(
        @PathVariable expoId: UUID,
        request: HttpServletRequest,
        csrf: CsrfToken,
    ): ResponseEntity<OwnerApplicationsResponse> =
        ResponseEntity
            .ok()
            .header("Cache-Control", "no-store")
            .header(csrf.headerName, csrf.token)
            .body(getApplications.execute(GetOwnerApplicationsCommand(expoId, session(request))))

    @PostMapping("/{type}/{applicationId}/cancellation")
    @Operation(summary = "내 일행 신청 개별 취소", description = "참가자 세션과 CSRF 토큰이 필요합니다. 소유권과 취소 조건을 검사하며 공급자 보호 계약이 없으면 503입니다.")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancel(
        @PathVariable expoId: UUID,
        @PathVariable type: OwnerApplicationType,
        @PathVariable applicationId: String,
        request: HttpServletRequest,
    ) = cancelApplication.execute(CancelOwnerApplicationCommand(expoId, session(request), type, applicationId))

    private fun session(request: HttpServletRequest): String =
        request.cookies
            ?.filter { it.name == sessionCookieName }
            ?.singleOrNull()
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "참가자 세션이 필요합니다.")
}
