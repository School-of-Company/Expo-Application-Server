package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
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
@RequestMapping("/internal/expos/{expoId}")
class PreregisterSessionChangeController(
    private val prepare: PreparePreregisterSessionChangeService,
    private val reconcile: ReconcilePreregisterSessionService,
    private val purge: PurgePreregisterSessionsService,
    private val cancel: CancelPreregisterSessionService,
) {
    data class CancellationRequest(
        @field:jakarta.validation.constraints.Positive val representativeId: Long?,
        @field:jakarta.validation.constraints.NotEmpty val applicationIds: List<Long>,
    )

    @Operation(summary = "신뢰된 서비스의 일행 일부 또는 관리자 신청 취소")
    @DeleteMapping("/preregister-sessions/{sessionId}/applications")
    fun cancel(
        @PathVariable expoId: UUID,
        @PathVariable sessionId: Long,
        @Valid @RequestBody request: CancellationRequest,
    ) = cancel.execute(CancelPreregisterSessionCommand(expoId, sessionId, request.representativeId, request.applicationIds))

    @Operation(summary = "회차 변경 승인과 지속 접수 차단")
    @PutMapping("/preregister-sessions/{sessionId}/changes/{nextRevision}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun prepare(
        @PathVariable expoId: UUID,
        @PathVariable sessionId: Long,
        @PathVariable nextRevision: Long,
        @Valid @RequestBody request: PreregisterSessionChangeRequest,
    ) = prepare.execute(PreparePreregisterSessionChangeCommand(expoId, sessionId, nextRevision, request))

    @Operation(summary = "Expo 관찰 결과로 미완료 회차 변경 복구", description = "false이면 접수 차단을 유지합니다. TTL로 해제하지 않습니다.")
    @PostMapping("/preregister-sessions/{sessionId}/reconciliation")
    fun reconcile(
        @PathVariable expoId: UUID,
        @PathVariable sessionId: Long,
    ): Boolean = reconcile.execute(PreregisterSessionKey(expoId, sessionId))

    @Operation(summary = "박람회 회차 원장 정리와 영구 삭제 표식")
    @PostMapping("/purge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun purge(
        @PathVariable expoId: UUID,
    ) = purge.execute(expoId)
}
