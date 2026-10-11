package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.ManagedParticipantCommand
import team.startup.application.domain.application.presentation.dto.ParticipantQrCommand
import team.startup.application.domain.application.presentation.dto.ProgramParticipantsCommand
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.service.GetParticipantQrService
import team.startup.application.domain.application.service.GetProgramParticipantsService
import java.util.UUID

@RestController
@ConditionalOnProperty(name = ["application.program-sessions.enabled"], havingValue = "true")
class ManagedParticipantController(
    private val participants: GetProgramParticipantsService,
    private val qr: GetParticipantQrService,
    @Value("\${application.owner.session-cookie-name:PARTICIPANT_SESSION}") private val cookieName: String,
) {
    @Operation(summary = "대표자 관리 참가자와 프로그램 역할 자격 조회")
    @GetMapping("/application/expos/{expoId}/program-sessions/{programType}/{programId}/participants")
    fun participants(
        @PathVariable expoId: UUID,
        @PathVariable programType: ProgramSessionType,
        @PathVariable programId: Long,
        request: HttpServletRequest,
    ) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
        participants.execute(ProgramParticipantsCommand(owner(expoId, request), programType, positive(programId))),
    )

    @Operation(summary = "대표자 소유 확정 참가자의 개인 QR 조회")
    @GetMapping("/application/expos/{expoId}/participants/{participantId}/qr")
    fun qr(
        @PathVariable expoId: UUID,
        @PathVariable participantId: Long,
        request: HttpServletRequest,
    ) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
        qr.execute(ParticipantQrCommand(owner(expoId, request), positive(participantId))),
    )

    private fun owner(
        expoId: UUID,
        request: HttpServletRequest,
    ): ManagedParticipantCommand {
        val session =
            request.cookies
                .orEmpty()
                .filter { it.name == cookieName }
                .singleOrNull()
                ?.value
                ?.takeIf { it.isNotBlank() }
                ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED")
        return ManagedParticipantCommand(expoId, session)
    }

    private fun positive(id: Long): Long = id.takeIf { it > 0 } ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST)
}
