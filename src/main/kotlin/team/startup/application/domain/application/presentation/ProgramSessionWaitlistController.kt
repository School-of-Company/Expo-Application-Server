package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import team.startup.application.domain.application.presentation.dto.PrepareProgramChangeCommand
import team.startup.application.domain.application.presentation.dto.ProgramChangePayload
import team.startup.application.domain.application.presentation.dto.ProgramSessionKey
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.service.PrepareProgramChangeService
import team.startup.application.domain.application.service.ReconcileProgramSessionService
import java.util.UUID

@RestController
@ApiResponses(
    value = [
        ApiResponse(responseCode = "400", description = "잘못된 요청 또는 식별자"),
        ApiResponse(responseCode = "401", description = "X-Internal-Token 인증 실패"),
    ],
)
class ProgramSessionWaitlistController(
    private val prepare: PrepareProgramChangeService,
    private val reconcile: ReconcileProgramSessionService,
) {
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "처리 완료"),
            ApiResponse(responseCode = "409", description = "신청, 정원, 버전 또는 변경 상태 충돌"),
        ],
    )
    @PutMapping("/internal/expos/{expoId}/program-changes/{changeId}")
    fun prepare(
        @PathVariable expoId: UUID,
        @PathVariable changeId: UUID,
        @Valid @RequestBody payload: ProgramChangePayload,
    ): ResponseEntity<Void> {
        prepare.execute(PrepareProgramChangeCommand(expoId, changeId, payload))
        return ResponseEntity.noContent().header(PROMOTION_POLICY_HEADER, "1").build()
    }

    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "처리 완료"),
            ApiResponse(responseCode = "404", description = "요청 대상 또는 공급자 조회 대상 없음"),
            ApiResponse(responseCode = "409", description = "신청, 정원, 버전 또는 변경 상태 충돌"),
            ApiResponse(responseCode = "502", description = "공급자 응답 계약 또는 내부 인증 오류"),
            ApiResponse(responseCode = "503", description = "원천 조회 또는 공급자 연동 불가"),
        ],
    )
    @PostMapping("/internal/expos/{expoId}/program-sessions/{programType}/{programId}/reconciliation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reconcile(
        @PathVariable expoId: UUID,
        @PathVariable programType: ProgramSessionType,
        @PathVariable programId: Long,
    ) = reconcile.execute(ProgramSessionKey(expoId, programType, programId))
}
