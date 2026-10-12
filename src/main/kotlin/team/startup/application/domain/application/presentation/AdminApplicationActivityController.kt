package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AdminApplicationActivityPage
import team.startup.application.domain.application.presentation.dto.AdminApplicationActivityQuery
import team.startup.application.domain.application.presentation.dto.AuthorizeAdminApplicationCommand
import team.startup.application.domain.application.service.AuthorizeAdminApplicationService
import team.startup.application.domain.application.service.GetAdminApplicationActivityService
import java.util.UUID

@RestController
class AdminApplicationActivityController(
    private val authorization: AuthorizeAdminApplicationService,
    private val activities: GetAdminApplicationActivityService,
) {
    @Operation(
        summary = "행사별 프로그램 신청 처리 이력",
        description = "신청·취소·대기 승급을 시간 역순으로 조회합니다. 관리자 취소는 actorId를 기록하며 과거·자동 처리 이력의 처리자는 null입니다. 모집 변경과 문자 발송은 각 원천 서비스가 소유합니다.",
    )
    @GetMapping("/application/admin/expos/{expoId}/program-session-applications/activities")
    fun get(
        @PathVariable expoId: UUID,
        @Valid @ModelAttribute query: AdminApplicationActivityQuery,
        request: HttpServletRequest,
    ): ResponseEntity<AdminApplicationActivityPage> {
        authorization.execute(AuthorizeAdminApplicationCommand(expoId, request.getHeader("Authorization")))
        if (query.from != null && query.until != null && query.from >= query.until) throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        if (request.parameterMap.any { (key, values) ->
                key !in setOf("action", "applicationId", "from", "until", "page", "size") ||
                    values.size != 1 ||
                    values.single().isBlank()
            }
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(activities.execute(expoId, query))
    }
}
