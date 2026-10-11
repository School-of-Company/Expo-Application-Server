package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import team.startup.application.domain.application.presentation.dto.CancelPreregisterSessionCommand
import team.startup.application.domain.application.presentation.dto.PreparePreregisterSessionChangeCommand
import team.startup.application.domain.application.presentation.dto.PreregisterSessionChangeRequest
import team.startup.application.domain.application.presentation.dto.PreregisterSessionKey
import team.startup.application.domain.application.service.CancelPreregisterSessionService
import team.startup.application.domain.application.service.PreparePreregisterSessionChangeService
import team.startup.application.domain.application.service.PurgePreregisterSessionsService
import team.startup.application.domain.application.service.ReconcilePreregisterSessionService
import java.util.UUID

@RestController
@ApiResponses(
    value = [
        ApiResponse(responseCode = "400", description = "잘못된 요청 또는 식별자"),
        ApiResponse(responseCode = "401", description = "X-Internal-Token 인증 실패"),
    ],
)
@RequestMapping("/internal/expos/{expoId}")
class PreregisterSessionChangeController(
    private val prepare: PreparePreregisterSessionChangeService,
    private val reconcile: ReconcilePreregisterSessionService,
    private val purge: PurgePreregisterSessionsService,
    private val cancel: CancelPreregisterSessionService,
) {
    @Schema(description = "신뢰된 서비스가 확인한 취소 대상 신청 ID 목록", example = """{"representativeId":123,"applicationIds":[123]}""")
    data class CancellationRequest(
        @field:jakarta.validation.constraints.Positive val representativeId: Long?,
        @field:jakarta.validation.constraints.NotEmpty val applicationIds: List<Long>,
    )

    @Operation(summary = "신뢰된 서비스의 일행 일부 또는 관리자 신청 취소")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 또는 처리 결과"),
            ApiResponse(responseCode = "404", description = "회차 없음"),
            ApiResponse(responseCode = "409", description = "신청, 정원, 버전 또는 변경 상태 충돌"),
            ApiResponse(responseCode = "502", description = "Expo 응답 계약 오류"),
            ApiResponse(responseCode = "503", description = "Expo 연동 불가"),
        ],
    )
    @DeleteMapping("/preregister-sessions/{sessionId}/applications")
    fun cancel(
        @PathVariable expoId: UUID,
        @PathVariable sessionId: Long,
        @Valid @RequestBody request: CancellationRequest,
    ) = cancel.execute(CancelPreregisterSessionCommand(expoId, sessionId, request.representativeId, request.applicationIds))

    @Operation(summary = "회차 변경 승인과 지속 접수 차단")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "처리 완료"),
            ApiResponse(responseCode = "404", description = "요청 대상 또는 공급자 조회 대상 없음"),
            ApiResponse(responseCode = "409", description = "신청, 정원, 버전 또는 변경 상태 충돌"),
            ApiResponse(responseCode = "502", description = "공급자 응답 계약 또는 내부 인증 오류"),
            ApiResponse(responseCode = "503", description = "원천 조회 또는 공급자 연동 불가"),
        ],
    )
    @PutMapping("/preregister-sessions/{sessionId}/changes/{nextRevision}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun prepare(
        @PathVariable expoId: UUID,
        @PathVariable sessionId: Long,
        @PathVariable nextRevision: Long,
        @Valid @RequestBody request: PreregisterSessionChangeRequest,
    ) = prepare.execute(PreparePreregisterSessionChangeCommand(expoId, sessionId, nextRevision, request))

    @Operation(summary = "Expo 관찰 결과로 미완료 회차 변경 복구", description = "false이면 접수 차단을 유지합니다. TTL로 해제하지 않습니다.")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 또는 처리 결과"),
            ApiResponse(responseCode = "409", description = "신청, 정원, 버전 또는 변경 상태 충돌"),
            ApiResponse(responseCode = "502", description = "공급자 응답 계약 또는 내부 인증 오류"),
            ApiResponse(responseCode = "503", description = "원천 조회 또는 공급자 연동 불가"),
        ],
    )
    @PostMapping("/preregister-sessions/{sessionId}/reconciliation")
    fun reconcile(
        @PathVariable expoId: UUID,
        @PathVariable sessionId: Long,
    ): Boolean = reconcile.execute(PreregisterSessionKey(expoId, sessionId))

    @Operation(summary = "박람회 회차 원장 정리와 영구 삭제 표식")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "처리 완료"),
        ],
    )
    @PostMapping("/purge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun purge(
        @PathVariable expoId: UUID,
    ) = purge.execute(expoId)
}
