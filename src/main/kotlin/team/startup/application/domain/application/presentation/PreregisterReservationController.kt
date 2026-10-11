package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import team.startup.application.domain.application.presentation.dto.PreregisterReservationKey
import team.startup.application.domain.application.service.GetPreregisterReservationService
import team.startup.application.domain.application.service.RetryPreregisterReservationService
import java.util.UUID

@RestController
@ApiResponses(
    value = [
        ApiResponse(responseCode = "400", description = "잘못된 요청 또는 식별자"),
        ApiResponse(responseCode = "401", description = "X-Internal-Token 인증 실패"),
    ],
)
@RequestMapping("/internal/expos/{expoId}/preregister-reservations/{requestId}")
class PreregisterReservationController(
    private val get: GetPreregisterReservationService,
    private val retry: RetryPreregisterReservationService,
) {
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 또는 처리 결과"),
            ApiResponse(responseCode = "404", description = "요청 대상 또는 공급자 조회 대상 없음"),
        ],
    )
    @GetMapping
    fun get(
        @PathVariable expoId: UUID,
        @PathVariable requestId: String,
    ) = get.execute(PreregisterReservationKey(expoId, requestId))

    @Operation(
        summary = "User 저장 결과를 공급자에서 확인·수정한 후 동일 요청 재시도",
        description = "좌석을 유지하고 원래 User requestId와 스냅샷을 재사용합니다. 참가자 ID를 임의로 지정하거나 좌석을 반환하지 않습니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "처리 완료"),
            ApiResponse(responseCode = "404", description = "요청 대상 또는 공급자 조회 대상 없음"),
            ApiResponse(responseCode = "409", description = "신청, 정원, 버전 또는 변경 상태 충돌"),
        ],
    )
    @PostMapping("/retry")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun retry(
        @PathVariable expoId: UUID,
        @PathVariable requestId: String,
    ) = retry.execute(PreregisterReservationKey(expoId, requestId))
}
