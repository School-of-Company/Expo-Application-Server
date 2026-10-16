package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import team.startup.application.domain.application.presentation.dto.ChangeLegacyTraineeAttributionCommand
import team.startup.application.domain.application.presentation.dto.GetLegacyTraineeAttributionOperationCommand
import team.startup.application.domain.application.presentation.dto.LegacyTraineeAttributionOperationStatus
import team.startup.application.domain.application.presentation.dto.LegacyTraineeAttributionRequest
import team.startup.application.domain.application.presentation.dto.LegacyTraineeAttributionResult
import team.startup.application.domain.application.service.GetLegacyTraineeAttributionOperationService
import team.startup.application.domain.application.service.LinkLegacyTraineeAttributionService
import team.startup.application.domain.application.service.RevokeLegacyTraineeAttributionService
import java.util.UUID

@RestController
@RequestMapping("/application/admin/expos/{expoId}/legacy-trainee-attributions")
class LegacyTraineeAttributionController(
    private val link: LinkLegacyTraineeAttributionService,
    private val revoke: RevokeLegacyTraineeAttributionService,
    private val operations: GetLegacyTraineeAttributionOperationService,
) {
    @Operation(
        summary = "확인된 레거시 연수자 귀속 연결",
        description = "명시적 ID와 확인 근거가 필수입니다. 정책 승인 후 활성화하며 동일 UUID Idempotency-Key로 복구합니다. 좌석·신청·출석을 생성하지 않습니다.",
    )
    @PostMapping
    fun link(
        @PathVariable expoId: UUID,
        @RequestHeader("Idempotency-Key") operationId: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @Valid @RequestBody request: LegacyTraineeAttributionRequest,
    ): ResponseEntity<LegacyTraineeAttributionResult> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            link.execute(ChangeLegacyTraineeAttributionCommand(expoId, operationId, authorization, request)),
        )

    @Operation(summary = "레거시 연수자 귀속 해제", description = "이 API로 확인한 연결만 해제합니다. 참가자 귀속만 정리하고 실제 연수 신청·좌석·출석은 유지합니다.")
    @PostMapping("/revoke")
    fun revoke(
        @PathVariable expoId: UUID,
        @RequestHeader("Idempotency-Key") operationId: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @Valid @RequestBody request: LegacyTraineeAttributionRequest,
    ): ResponseEntity<LegacyTraineeAttributionResult> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            revoke.execute(ChangeLegacyTraineeAttributionCommand(expoId, operationId, authorization, request)),
        )

    @Operation(summary = "레거시 귀속 변경 복구 상태", description = "행사 관리자 검증 후 상태·결과만 반환합니다. reason과 개인정보는 반환하지 않습니다.")
    @GetMapping("/operations/{operationId}")
    fun operation(
        @PathVariable expoId: UUID,
        @PathVariable operationId: UUID,
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): ResponseEntity<LegacyTraineeAttributionOperationStatus> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            operations.execute(GetLegacyTraineeAttributionOperationCommand(expoId, operationId, authorization)),
        )
}
