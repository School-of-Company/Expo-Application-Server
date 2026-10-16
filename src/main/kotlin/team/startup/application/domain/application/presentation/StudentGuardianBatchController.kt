package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.ApplyStudentGuardianBatchCommand
import team.startup.application.domain.application.presentation.dto.ProgramSessionKey
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.presentation.dto.StudentGuardianBatchReceipt
import team.startup.application.domain.application.presentation.dto.StudentGuardianBatchRequest
import team.startup.application.domain.application.service.ApplyStudentGuardianBatchService
import java.util.UUID

@RestController
@ConditionalOnProperty(name = ["application.program-sessions.enabled"], havingValue = "true")
class StudentGuardianBatchController(
    private val service: ApplyStudentGuardianBatchService,
    @Value("\${application.owner.session-cookie-name:PARTICIPANT_SESSION}") private val cookieName: String,
) {
    @Operation(
        summary = "학생·보호자의 개별 폼과 연결을 한 번에 접수",
        description = "신규 참가자는 전원 확정 또는 전원 대기. 기존 확정 보호자는 폼·좌석을 재사용하며 답변을 생략합니다.",
        responses = [
            ApiResponse(responseCode = "201", description = "원자적 접수 또는 동일 멱등 요청의 원본 결과"),
            ApiResponse(responseCode = "400", description = "잘못된 연결·답변"),
            ApiResponse(responseCode = "401", description = "세션 인증 필요"),
            ApiResponse(responseCode = "403", description = "CSRF·관리 일행·학년·역할 자격 불충족"),
            ApiResponse(responseCode = "409", description = "중복·시간 충돌·정원·정의 변경"),
            ApiResponse(responseCode = "503", description = "학년· 동반 정책·시간 충돌 계약 공급자 미설정"),
        ],
    )
    @PostMapping("/application/expos/{expoId}/program-sessions/{programType}/{programId}/application-batches")
    fun apply(
        @PathVariable expoId: UUID,
        @PathVariable programType: ProgramSessionType,
        @PathVariable programId: Long,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
        @Valid @RequestBody body: StudentGuardianBatchRequest,
        request: HttpServletRequest,
    ): ResponseEntity<StudentGuardianBatchReceipt> {
        val session = request.cookies.orEmpty().filter { it.name == cookieName }.singleOrNull()?.value?.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED")
        if (programId <= 0 || key.isNullOrBlank() || key.length > 200) throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(
            service.execute(ApplyStudentGuardianBatchCommand(ProgramSessionKey(expoId, programType, programId), session, key, body)),
        )
    }
}
