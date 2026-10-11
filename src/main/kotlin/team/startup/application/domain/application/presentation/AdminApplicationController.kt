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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AdminApplicationPage
import team.startup.application.domain.application.presentation.dto.AdminApplicationQuery
import team.startup.application.domain.application.service.GetAdminApplicationsService
import java.util.UUID

@RestController
@RequestMapping("/internal/expos/{expoId}/program-session-applications")
class AdminApplicationController(
    private val service: GetAdminApplicationsService,
) {
    @Operation(
        summary = "내부 프로그램 회차 접수 원장 조회",
        description =
            "내부 토큰 전용. 검색은 제출 당시 프로그램 제목, 일자는 접수 생성 시각(from 포함/until 제외). " +
                "동일 필터의 목록/전체 건수/상태 집계를 반환하며 applicationId로 답변/이력을 조회합니다. 관리자 행사 권한 API는 제공하지 않습니다.",
    )
    @GetMapping
    fun list(
        @PathVariable expoId: UUID,
        @Valid @ModelAttribute query: AdminApplicationQuery,
        request: HttpServletRequest,
    ): ResponseEntity<AdminApplicationPage> {
        val allowed =
            setOf("programType", "programId", "groupId", "applicationId", "status", "search", "from", "until", "page", "size", "sort")
        if (request.parameterMap.any { (key, values) -> key !in allowed || values.size != 1 || values.single().isBlank() }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported, repeated or empty query parameter")
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.execute(expoId, query))
    }
}
