package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.constraints.Positive
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.PreregisterSessionCapacityQuery
import team.startup.application.domain.application.presentation.dto.PreregisterSessionCapacityResponse
import team.startup.application.domain.application.service.GetPreregisterSessionCapacityService
import java.util.UUID

@RestController
class PreregisterSessionCapacityController(
    private val service: GetPreregisterSessionCapacityService?,
) {
    @Operation(
        summary = "사전등록 회차 잔여석 조회",
        description = "로그인 없이 조회하는 화면 안내용 값입니다. 제출 시 현재 정원을 다시 판정하며 부족하면 일행 전체를 거절합니다.",
    )
    @GetMapping("/application/expos/{expoId}/preregister-sessions/{sessionId}/capacity")
    fun capacity(
        @PathVariable expoId: UUID,
        @PathVariable @Positive sessionId: Long,
    ): ResponseEntity<PreregisterSessionCapacityResponse> {
        val capacityService = service ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "회차 잔여석 조회가 준비되지 않았습니다.")
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(capacityService.execute(PreregisterSessionCapacityQuery(expoId, sessionId)))
    }
}
