package team.startup.application.domain.application.presentation

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import team.startup.application.domain.application.presentation.dto.CancelProgramSessionCommand
import team.startup.application.domain.application.presentation.dto.PrepareProgramChangeCommand
import team.startup.application.domain.application.presentation.dto.ProgramChangePayload
import team.startup.application.domain.application.presentation.dto.ProgramSessionKey
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.service.CancelProgramSessionService
import team.startup.application.domain.application.service.PrepareProgramChangeService
import team.startup.application.domain.application.service.ReconcileProgramSessionService
import java.util.UUID

@RestController
class ProgramSessionWaitlistController(
    private val prepare: PrepareProgramChangeService,
    private val cancel: CancelProgramSessionService,
    private val reconcile: ReconcileProgramSessionService,
) {
    @PutMapping("/internal/expos/{expoId}/program-changes/{changeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun prepare(
        @PathVariable expoId: UUID,
        @PathVariable changeId: UUID,
        @Valid @RequestBody payload: ProgramChangePayload,
    ) = prepare.execute(PrepareProgramChangeCommand(expoId, changeId, payload))

    @PostMapping("/internal/program-session-applications/{applicationId}/cancellation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancel(
        @PathVariable applicationId: UUID,
    ) = cancel.execute(CancelProgramSessionCommand(applicationId))

    @PostMapping("/internal/expos/{expoId}/program-sessions/{programType}/{programId}/reconciliation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reconcile(
        @PathVariable expoId: UUID,
        @PathVariable programType: ProgramSessionType,
        @PathVariable programId: Long,
    ) = reconcile.execute(ProgramSessionKey(expoId, programType, programId))
}
