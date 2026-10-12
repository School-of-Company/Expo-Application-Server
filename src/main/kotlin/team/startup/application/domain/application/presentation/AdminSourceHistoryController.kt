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
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AdminExpoHistoryPage
import team.startup.application.domain.application.presentation.dto.AdminExpoHistoryQuery
import team.startup.application.domain.application.presentation.dto.AdminMessageHistoryPage
import team.startup.application.domain.application.presentation.dto.AdminMessageHistoryQuery
import team.startup.application.domain.application.service.GetAdminExpoHistoryService
import team.startup.application.domain.application.service.GetAdminMessageHistoryService
import java.time.Instant
import java.util.UUID

@RestController
class AdminSourceHistoryController(
    private val messages: GetAdminMessageHistoryService,
    private val expo: GetAdminExpoHistoryService,
) {
    @Operation(
        summary = "행사별 그룹 문자 처리 이력",
        description = "매 페이지 원본 관리자 JWT를 검증하고 Notification 원천을 조회합니다. from 포함/to 제외, 시간·eventId 역순 커서. 본문·전화번호는 포함하지 않습니다.",
    )
    @GetMapping("/application/expos/{expoId}/admin-group-messages/history")
    fun messages(
        @PathVariable expoId: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @Valid @ModelAttribute query: AdminMessageHistoryQuery,
        request: HttpServletRequest,
    ): ResponseEntity<AdminMessageHistoryPage> {
        validate(request, setOf("from", "to", "type", "limit", "cursor"), query.from, query.to)
        if (listOfNotNull(query.from, query.to).any {
                it < Instant.EPOCH || it > Instant.ofEpochMilli(9_999_999_999_999L)
            }
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(messages.execute(expoId, authorization, query))
    }

    @Operation(
        summary = "행사별 모집·회차 변경 이력",
        description = "Application 관리자 JWT 검증 후 Expo의 공개 원천 API에 같은 JWT를 전달합니다. 독립 페이지로 조회하며 Application에 중복 저장하지 않습니다.",
    )
    @GetMapping("/application/admin/expos/{expoId}/recruitment-history")
    fun expo(
        @PathVariable expoId: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @Valid @ModelAttribute query: AdminExpoHistoryQuery,
        request: HttpServletRequest,
    ): ResponseEntity<AdminExpoHistoryPage> {
        validate(request, setOf("from", "to", "type", "page", "size"), query.from, query.to)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(expo.execute(expoId, authorization, query))
    }

    private fun validate(
        request: HttpServletRequest,
        keys: Set<String>,
        from: Instant?,
        to: Instant?,
    ) {
        if ((from != null && to != null && from >= to) ||
            request.parameterMap.any { (key, values) -> key !in keys || values.size != 1 || values.single().isBlank() }
        ) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        }
    }
}
