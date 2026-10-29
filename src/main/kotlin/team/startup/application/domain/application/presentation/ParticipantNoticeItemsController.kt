package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import team.startup.application.domain.application.presentation.dto.ParticipantNoticeItemsRequest
import team.startup.application.domain.application.service.GetParticipantNoticeItemsService
import java.util.UUID

@RestController
class ParticipantNoticeItemsController(
    private val service: GetParticipantNoticeItemsService,
) {
    @PostMapping("/internal/expos/{expoId}/participants/notice-items")
    @Operation(
        summary = "Notification QR 문자용 참가자별 확정 회차·프로그램",
        description = "내부 토큰 필수. 요청 순서대로 확정된 사전등록 회차와 프로그램만 돌려주고 이름·전화번호는 담지 않습니다. 읽기 전용입니다.",
        responses = [
            ApiResponse(responseCode = "400", description = "참가자 ID가 없거나 6개 이상이거나 중복·0 이하"),
            ApiResponse(responseCode = "401", description = "내부 토큰 인증 실패"),
            ApiResponse(responseCode = "404", description = "삭제된 박람회"),
        ],
    )
    fun get(
        @PathVariable expoId: UUID,
        @Valid @RequestBody request: ParticipantNoticeItemsRequest,
    ) = ResponseEntity.ok().header("Cache-Control", "no-store").body(service.execute(expoId, request.participantIds))
}
