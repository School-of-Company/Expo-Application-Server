package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import team.startup.application.domain.application.presentation.dto.PromotionOfferCheck
import team.startup.application.domain.application.presentation.dto.PromotionOfferDeliveryCommand
import team.startup.application.domain.application.presentation.dto.PromotionOfferDeliveryRequest
import team.startup.application.domain.application.presentation.dto.PromotionOfferDeliveryResult
import team.startup.application.domain.application.presentation.dto.PromotionOfferEvent
import team.startup.application.domain.application.service.CheckPromotionOfferService
import team.startup.application.domain.application.service.RecordPromotionOfferDeliveryService
import java.util.UUID

@RestController
@SecurityRequirement(name = "internalToken")
@ApiResponse(responseCode = "401", description = "X-Internal-Token 인증 실패")
class PromotionOfferController(
    private val check: CheckPromotionOfferService,
    private val record: RecordPromotionOfferDeliveryService,
) {
    @Operation(summary = "참여 제안 발송 직전 확인", description = "현재 제안·대기 상태·발송 시간대(09:00~18:00 KST)·마감을 확인합니다.")
    @PostMapping("/internal/promotion-offers/check")
    fun check(
        @Valid @RequestBody event: PromotionOfferEvent,
    ): PromotionOfferCheck = check.execute(event)

    @Operation(
        summary = "참여 제안 발송 결과 기록",
        description = "ACCEPTED는 공급자 접수 시각으로 응답 기한을 시작합니다. FAILED·UNKNOWN은 좌석을 유지하며 거절·무응답으로 보지 않습니다. 같은 결과는 멱등입니다.",
    )
    @ApiResponse(responseCode = "409", description = "현재 이벤트가 아니거나 다른 접수 결과가 이미 기록됨")
    @PostMapping("/internal/promotion-offers/{offerId}/deliveries")
    fun delivery(
        @PathVariable offerId: UUID,
        @Valid @RequestBody request: PromotionOfferDeliveryRequest,
    ): PromotionOfferDeliveryResult = record.execute(PromotionOfferDeliveryCommand(offerId, request))
}
