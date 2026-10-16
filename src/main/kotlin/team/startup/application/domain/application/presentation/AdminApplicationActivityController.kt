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
        description =
            "신청·취소·승급을 시간 역순으로 조회합니다. q는 현재 User 참가자 검색, definition은 제출 당시 스냅샷입니다. " +
                "새 이력은 USER/ADMIN/SYSTEM과 이전 상태를 기록하며 과거 미기록은 UNKNOWN/previousStatusRecorded=false입니다. " +
                "status는 이력 결과 상태이며 목록과 건수에 같은 조건을 적용합니다. 모집 변경·문자 발송은 각 원천 서비스가 소유합니다.",
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
                key !in setOf("action", "applicationId", "from", "until", "page", "size", "q", "programType", "programId", "status") ||
                    values.size != 1 ||
                    values.single().isBlank()
            }
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(activities.execute(expoId, query))
    }
}
