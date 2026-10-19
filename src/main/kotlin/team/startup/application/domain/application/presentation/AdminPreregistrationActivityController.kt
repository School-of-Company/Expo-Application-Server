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
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationActivityPage
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationActivityQuery
import team.startup.application.domain.application.presentation.dto.AuthorizeAdminApplicationCommand
import team.startup.application.domain.application.service.AuthorizeAdminApplicationService
import team.startup.application.domain.application.service.GetAdminPreregistrationActivityService
import java.util.UUID

@RestController
class AdminPreregistrationActivityController(
    private val authorization: AuthorizeAdminApplicationService,
    private val activities: GetAdminPreregistrationActivityService,
) {
    @Operation(
        summary = "행사별 사전등록 상태 변경 이력",
        description =
            "APPLICATION_READ 필요. 확정·취소 전이(tb_preregister_transition)와 대기 철회(tb_preregister_reservation)를 " +
                "처리 시각 역순으로 조회합니다. 대기 접수·승급은 시각이 기록되지 않아 포함하지 않습니다. " +
                "과거 행의 시각·실행자는 추정하지 않고 createdAt=null, actorType=UNKNOWN으로 응답합니다. " +
                "q는 확정·취소는 User 현재 이름·번호, 대기 철회는 제출 당시 대표자 이름·번호입니다. 응답은 no-store입니다.",
    )
    @GetMapping("/application/admin/expos/{expoId}/preregistrations/activities")
    fun get(
        @PathVariable expoId: UUID,
        @Valid @ModelAttribute query: AdminPreregistrationActivityQuery,
        request: HttpServletRequest,
    ): ResponseEntity<AdminPreregistrationActivityPage> {
        authorization.execute(AuthorizeAdminApplicationCommand(expoId, request.getHeader("Authorization")))
        if (query.from != null && query.until != null && query.from >= query.until) throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        if (request.parameterMap.any { (key, values) ->
                key !in setOf("action", "sessionId", "from", "until", "page", "size", "q") ||
                    values.size != 1 ||
                    values.single().isBlank()
            }
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(activities.execute(expoId, query))
    }
}
