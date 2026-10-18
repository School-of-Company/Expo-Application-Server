package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.constraints.Positive
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import team.startup.application.domain.application.presentation.dto.PreregisterSessionCapacityQuery
import team.startup.application.domain.application.presentation.dto.PreregisterSessionCapacityResponse
import team.startup.application.domain.application.service.GetPreregisterSessionCapacityService
import java.util.UUID

@RestController
class PreregisterSessionCapacityController(
    private val service: GetPreregisterSessionCapacityService,
    @Value("\${application.owner.session-cookie-name:PARTICIPANT_SESSION}") private val sessionCookieName: String = "PARTICIPANT_SESSION",
) {
    @Operation(
        summary = "사전등록 회차 잔여석 조회",
        description =
            "로그인 없이 조회하는 화면 안내용 값입니다. 제출 시 현재 정원을 다시 판정하며 부족하면 일행 전체를 거절합니다. " +
                "대상별 정원 회차는 교사·예비교사 숫자를 싣지 않고, 참가자 세션 쿠키로 확인한 대표자 일행에 이 회차 확정 교사·예비교사 " +
                "신청이 있을 때만 targetSeats.ownTeacherRemaining을 채웁니다. 만료·무효 세션은 공개 응답으로 처리합니다.",
        responses = [
            ApiResponse(responseCode = "200", description = "현재 잔여석"),
            ApiResponse(responseCode = "400", description = "잘못된 박람회 또는 회차 ID"),
            ApiResponse(responseCode = "404", description = "회차 없음"),
            ApiResponse(responseCode = "409", description = "삭제 또는 회차 정의 변경 중"),
            ApiResponse(responseCode = "502", description = "Expo 응답 계약 오류"),
            ApiResponse(responseCode = "503", description = "Expo 연동 불가"),
        ],
    )
    @GetMapping("/application/expos/{expoId}/preregister-sessions/{sessionId}/capacity")
    fun capacity(
        @PathVariable expoId: UUID,
        @PathVariable @Positive sessionId: Long,
        request: HttpServletRequest,
    ): ResponseEntity<PreregisterSessionCapacityResponse> {
        val session =
            request.cookies
                ?.filter { it.name == sessionCookieName }
                ?.singleOrNull()
                ?.value
                ?.takeIf { it.isNotBlank() }
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(service.execute(PreregisterSessionCapacityQuery(expoId, sessionId, session)))
    }
}
