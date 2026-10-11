package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
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
@RequestMapping("/internal/expos/{expoId}/preregister-reservations/{requestId}")
class PreregisterReservationController(
    private val get: GetPreregisterReservationService,
    private val retry: RetryPreregisterReservationService,
) {
    @GetMapping
    fun get(
        @PathVariable expoId: UUID,
        @PathVariable requestId: String,
    ) = get.execute(PreregisterReservationKey(expoId, requestId))

    @Operation(
        summary = "User 저장 결과를 공급자에서 확인·수정한 후 동일 요청 재시도",
        description = "좌석을 유지하고 원래 User requestId와 스냅샷을 재사용합니다. 참가자 ID를 임의로 지정하거나 좌석을 반환하지 않습니다.",
    )
    @PostMapping("/retry")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun retry(
        @PathVariable expoId: UUID,
        @PathVariable requestId: String,
    ) = retry.execute(PreregisterReservationKey(expoId, requestId))
}
