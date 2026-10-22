package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import team.startup.application.domain.application.presentation.dto.FieldVisitSourceCommand
import team.startup.application.domain.application.presentation.dto.FieldVisitSourceQuery
import team.startup.application.domain.application.service.GetFieldVisitSourceService
import java.util.UUID

@RestController
class InternalFieldVisitController(
    private val source: GetFieldVisitSourceService,
) {
    @GetMapping("/internal/expos/{expoId}/field-visits")
    @Operation(
        summary = "Report·관리자용 방문일 현장등록 원천",
        description = "내부 토큰 필수. visitDate는 KST 날짜이며 과거 FIELD 값은 이관하지 않습니다. UUID 커서, 최대 500건.",
        responses = [ApiResponse(responseCode = "401", description = "내부 토큰 인증 실패")],
    )
    fun get(
        @PathVariable expoId: UUID,
        @Valid @ModelAttribute query: FieldVisitSourceQuery,
    ) = ResponseEntity.ok().header("Cache-Control", "no-store").body(source.execute(FieldVisitSourceCommand(expoId, query)))
}
