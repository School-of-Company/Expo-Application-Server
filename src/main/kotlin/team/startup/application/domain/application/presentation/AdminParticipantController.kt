package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AdminParticipantPage
import team.startup.application.domain.application.presentation.dto.AdminParticipantSearchQuery
import team.startup.application.domain.application.presentation.dto.AuthorizeAdminApplicationCommand
import team.startup.application.domain.application.presentation.dto.ParticipantQrResponse
import team.startup.application.domain.application.service.AuthorizeAdminApplicationService
import team.startup.application.domain.application.service.GetAdminParticipantQrService
import team.startup.application.domain.application.service.SearchAdminParticipantsService
import java.util.UUID

@RestController
@ApiResponses(
    value = [
        ApiResponse(responseCode = "400", description = "잘못된 요청 또는 식별자"),
        ApiResponse(responseCode = "401", description = "관리자 Bearer JWT 인증 실패"),
        ApiResponse(responseCode = "403", description = "관리자의 행사 APPLICATION_READ 권한 거부"),
        ApiResponse(responseCode = "502", description = "공급자 응답 계약 오류"),
        ApiResponse(responseCode = "503", description = "원천 조회 또는 공급자 연동 불가"),
    ],
)
@RequestMapping("/application/admin/expos/{expoId}/participants")
class AdminParticipantController(
    private val authorization: AuthorizeAdminApplicationService,
    private val search: SearchAdminParticipantsService,
    private val qr: GetAdminParticipantQrService,
) {
    @Operation(
        summary = "관리자 입장등록 참가자 검색",
        description =
            "QR 분실 등 현장 조회용입니다. q는 User 이름 부분 일치이며 숫자와 하이픈뿐이면 번호도 찾습니다. " +
                "User 참가자 id 오름차순으로 cursor 뒤부터 size개를 돌려주고 마지막 페이지는 nextCursor가 null입니다. " +
                "프로그램 신청 내역은 program-session-applications의 q로 조회합니다.",
    )
    @GetMapping
    fun search(
        @PathVariable expoId: UUID,
        @Valid @ModelAttribute query: AdminParticipantSearchQuery,
        @RequestHeader("Authorization", required = false) bearer: String?,
        request: HttpServletRequest,
    ): ResponseEntity<AdminParticipantPage> {
        authorization.execute(AuthorizeAdminApplicationCommand(expoId, bearer))
        if (request.parameterMap.any { (key, values) -> key !in setOf("q", "cursor", "size") || values.size != 1 }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported or repeated query parameter")
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(search.execute(expoId, query))
    }

    @Operation(
        summary = "관리자 참가자 QR 조회",
        description =
            "사전등록이 확정되고 취소되지 않은 참가자의 Attendance QR 값을 돌려줍니다. " +
                "확정 사전등록이 없거나 취소된 참가자, Attendance가 거부한 참가자는 구분 없이 404입니다. code는 기록하지 않습니다.",
    )
    @ApiResponses(value = [ApiResponse(responseCode = "404", description = "QR을 조회할 수 없는 참가자")])
    @GetMapping("/{participantId}/qr")
    fun qr(
        @PathVariable expoId: UUID,
        @PathVariable participantId: Long,
        @RequestHeader("Authorization", required = false) bearer: String?,
    ): ResponseEntity<ParticipantQrResponse> {
        authorization.execute(AuthorizeAdminApplicationCommand(expoId, bearer))
        if (participantId <= 0) throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(qr.execute(expoId, participantId))
    }
}
