package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import team.startup.application.domain.application.presentation.dto.SubscribeAdminDataChangesCommand
import team.startup.application.domain.application.service.SubscribeAdminDataChangesService
import java.util.UUID

@RestController
@ConditionalOnProperty("application.admin-events.enabled", havingValue = "true")
class AdminDataChangeController(
    private val subscribe: SubscribeAdminDataChangesService,
) {
    @Operation(
        summary = "관리자 신청 현황 변경 구독(SSE)",
        description =
            "Authorization Bearer로 APPLICATION_READ를 검증한 뒤 `admin-data-changed` 이벤트를 보냅니다. " +
                "data는 eventId, expoId, resource(APPLICATIONS), occurredAt뿐이며 받으면 기존 GET을 현재 필터로 다시 조회합니다. " +
                "연결·재연결 직후에도 기존 GET으로 전체 값을 다시 읽습니다. 이벤트를 재생하지 않으며 Last-Event-ID는 쓰지 않습니다. " +
                "heartbeat는 SSE 주석입니다. 연결 수명이 끝나면 서버가 닫고, 재연결 때 권한을 다시 검증합니다. 인증정보를 query로 받지 않습니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "text/event-stream 연결"),
            ApiResponse(responseCode = "400", description = "query 파라미터 포함"),
            ApiResponse(responseCode = "401", description = "관리자 Bearer 없음 또는 만료"),
            ApiResponse(responseCode = "403", description = "행사 APPLICATION_READ 권한 없음"),
            ApiResponse(responseCode = "503", description = "연결 수 한도 초과 또는 권한 공급자 장애"),
        ],
    )
    @GetMapping("/application/admin/expos/{expoId}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun subscribe(
        @PathVariable expoId: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<SseEmitter> {
        // 브라우저 EventSource용 토큰 query를 받지 않는다. 인증정보는 Authorization 헤더로만 받는다.
        if (!request.queryString.isNullOrEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "query 파라미터를 받지 않습니다.")
        val emitter = subscribe.execute(SubscribeAdminDataChangesCommand(expoId, request.getHeader(HttpHeaders.AUTHORIZATION)))
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            // nginx 계열 프록시가 이벤트를 모아 두지 않도록 한다.
            .header("X-Accel-Buffering", "no")
            .body(emitter)
    }
}
