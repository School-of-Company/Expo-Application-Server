package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import team.startup.application.domain.application.presentation.dto.AddOwnerPreregisterCompanionsCommand
import team.startup.application.domain.application.presentation.dto.AddOwnerPreregisterCompanionsRequest
import team.startup.application.domain.application.presentation.dto.AddOwnerPreregisterSessionCommand
import team.startup.application.domain.application.presentation.dto.AddOwnerPreregisterSessionRequest
import team.startup.application.domain.application.presentation.dto.CancelOwnerApplicationCommand
import team.startup.application.domain.application.presentation.dto.GetOwnerApplicationsCommand
import team.startup.application.domain.application.presentation.dto.OwnerApplicationType
import team.startup.application.domain.application.presentation.dto.OwnerApplicationsResponse
import team.startup.application.domain.application.presentation.dto.OwnerPreregisterCompanionsResult
import team.startup.application.domain.application.presentation.dto.OwnerPreregisterSessionResult
import team.startup.application.domain.application.presentation.dto.OwnerPreregisterWaitlist
import team.startup.application.domain.application.presentation.dto.OwnerPreregisterWaitlistCommand
import team.startup.application.domain.application.service.AddOwnerPreregisterCompanionsService
import team.startup.application.domain.application.service.AddOwnerPreregisterSessionService
import team.startup.application.domain.application.service.CancelOwnerApplicationService
import team.startup.application.domain.application.service.GetOwnerApplicationsService
import team.startup.application.domain.application.service.GetOwnerPreregisterWaitlistService
import team.startup.application.domain.application.service.GetOwnerPreregisterWaitlistsService
import team.startup.application.domain.application.service.WithdrawOwnerPreregisterWaitlistService
import team.startup.application.domain.application.service.impl.OccupationNotOpenException
import team.startup.application.domain.application.service.impl.ParticipantSessionException
import team.startup.application.domain.application.service.impl.PreregisterFormChangedException
import java.util.UUID

@RestController
@RequestMapping("/application/expos/{expoId}/my-applications")
class OwnerApplicationController(
    private val getApplications: GetOwnerApplicationsService,
    private val cancelApplication: CancelOwnerApplicationService,
    private val getWaitlist: GetOwnerPreregisterWaitlistService,
    private val getWaitlists: GetOwnerPreregisterWaitlistsService,
    private val withdrawWaitlist: WithdrawOwnerPreregisterWaitlistService,
    private val addCompanions: AddOwnerPreregisterCompanionsService,
    private val addSession: AddOwnerPreregisterSessionService,
    @Value("\${application.owner.session-cookie-name:PARTICIPANT_SESSION}") private val sessionCookieName: String,
) {
    @GetMapping
    @Operation(
        summary = "내 일행 신청 조회",
        description = "참가자 세션 쿠키로 일행을 확인합니다. 응답 X-CSRF-TOKEN 헤더와 XSRF-TOKEN 쿠키를 취소 요청에 사용합니다.",
        responses = [
            ApiResponse(responseCode = "200", description = "일행 신청 목록"),
            ApiResponse(responseCode = "400", description = "잘못된 박람회 ID"),
            ApiResponse(responseCode = "401", description = "참가자 세션 인증 실패"),
            ApiResponse(responseCode = "404", description = "공급자 조회 대상 없음"),
            ApiResponse(responseCode = "409", description = "공급자 상태 충돌"),
            ApiResponse(responseCode = "502", description = "User 응답 계약 또는 내부 인증 오류"),
            ApiResponse(responseCode = "503", description = "User 연동 불가"),
        ],
    )
    fun get(
        @PathVariable expoId: UUID,
        request: HttpServletRequest,
        csrf: CsrfToken,
    ): ResponseEntity<OwnerApplicationsResponse> =
        ResponseEntity
            .ok()
            .header("Cache-Control", "no-store")
            .header(csrf.headerName, csrf.token)
            .body(getApplications.execute(GetOwnerApplicationsCommand(expoId, session(request))))

    @PostMapping("/{type}/{applicationId}/cancellation")
    @Operation(
        summary = "내 일행 신청 개별 취소",
        description = "참가자 세션과 CSRF 토큰이 필요합니다. 소유권과 취소 조건을 검사하며 공급자 보호 계약이 없으면 503입니다.",
        responses = [
            ApiResponse(responseCode = "204", description = "취소 완료"),
            ApiResponse(responseCode = "400", description = "잘못된 요청"),
            ApiResponse(responseCode = "401", description = "참가자 세션 인증 실패"),
            ApiResponse(responseCode = "403", description = "CSRF 검증 실패"),
            ApiResponse(responseCode = "404", description = "취소 대상 없음"),
            ApiResponse(responseCode = "409", description = "취소 조건 또는 공급자 상태 충돌"),
            ApiResponse(responseCode = "502", description = "공급자 응답 계약 또는 내부 인증 오류"),
            ApiResponse(responseCode = "503", description = "공급자 보호 계약 또는 연동 불가"),
        ],
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancel(
        @PathVariable expoId: UUID,
        @PathVariable type: OwnerApplicationType,
        @PathVariable applicationId: String,
        request: HttpServletRequest,
    ) = cancelApplication.execute(CancelOwnerApplicationCommand(expoId, session(request), type, applicationId))

    @PostMapping("/PREREGISTER/{applicationId}/companions")
    @Operation(
        summary = "내 사전등록 일행에 동행자 추가",
        description =
            "참가자 세션과 CSRF 토큰이 필요합니다. 세션 대표자 본인의 확정 사전등록에만 추가합니다. " +
                "회차 잔여석과 폼 동행자 상한(maxSelection, 대표자 포함 5명)을 박람회 좌석 잠금 안에서 다시 확인합니다. " +
                "자리가 모자라면 대기 접수가 켜져 있고 대기 정원이 남은 경우 회차 대기열 뒤에 세우고(202 WAITING), " +
                "일반 대기 일행과 같은 규칙(#144: 회차 전날 전 자동 승급, 전날부터 참여 제안·수락)으로 확정합니다. " +
                "새 동행자의 QR은 대표자 번호로 발송됩니다. 대기 중인 추가는 내 대기 취소로 철회할 수 있습니다. " +
                "같은 Idempotency-Key로 다시 보내면 같은 추가를 이어서 처리합니다.",
        responses = [
            ApiResponse(responseCode = "201", description = "추가 확정"),
            ApiResponse(responseCode = "202", description = "대기 접수(WAITING, position) 또는 승급 뒤 User 저장 중(PROCESSING). QR 없음"),
            ApiResponse(responseCode = "400", description = "동행자 형식 오류 또는 중복 입력"),
            ApiResponse(responseCode = "401", description = "참가자 세션 인증 실패"),
            ApiResponse(responseCode = "403", description = "CSRF 검증 실패"),
            ApiResponse(responseCode = "404", description = "본인 대표자 사전등록이 아님"),
            ApiResponse(
                responseCode = "409",
                description =
                    "정원·동행자 상한 초과, 취소·마감·시작된 회차, 사전등록 기간 아님, 처리 중인 추가, 이미 일행인 동행자, " +
                        "User 재등록 거절(같은 대표자 QR 재발송 한도)",
            ),
            ApiResponse(responseCode = "502", description = "공급자 응답 계약 또는 내부 인증 오류(좌석 유지, 운영 확인)"),
            ApiResponse(responseCode = "503", description = "참여 서비스 통지 비활성 또는 공급자 연동 불가(같은 키로 재시도)"),
        ],
    )
    fun addCompanions(
        @PathVariable expoId: UUID,
        @PathVariable applicationId: String,
        @Valid @RequestBody body: AddOwnerPreregisterCompanionsRequest,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        request: HttpServletRequest,
    ): ResponseEntity<OwnerPreregisterCompanionsResult> =
        addCompanions
            .execute(
                AddOwnerPreregisterCompanionsCommand(
                    expoId,
                    session(request),
                    applicationId,
                    requireNotNull(body.companions),
                    idempotencyKey,
                ),
            ).let { result ->
                ResponseEntity
                    .status(if (result.status == "CONFIRMED") HttpStatus.CREATED else HttpStatus.ACCEPTED)
                    .header("Cache-Control", "no-store")
                    .body(result)
            }

    @PostMapping("/preregister-sessions")
    @Operation(
        summary = "내 일행의 다른 사전등록 회차 추가",
        description =
            "참가자 세션과 CSRF 토큰이 필요합니다(#199). 이미 신청한 일행에서 이 회차에 갈 참가자를 고르면 처음 신청 답변을 다시 써서 " +
                "일반 신청과 같은 정원·대기 규칙으로 받습니다. 대표자는 빠져도 되지만 학생만으로는 안 됩니다. " +
                "User에 다시 저장하지 않으므로 QR과 문자는 그대로입니다. 신청 뒤 사전등록 폼이 바뀌었으면 409 FORM_CHANGED로 다시 입력을 요구합니다. " +
                "같은 Idempotency-Key로 다시 보내면 같은 추가를 이어서 처리합니다.",
        responses = [
            ApiResponse(responseCode = "201", description = "회차 확정"),
            ApiResponse(responseCode = "202", description = "대기 접수(WAITING, position) 또는 승급 뒤 확정 중(PROCESSING)"),
            ApiResponse(responseCode = "400", description = "형식 오류, 같은 참석자 중복, 일행에 없는 참가자, 학생만 참석, 직업 없는 대상별 정원 회차 참석자"),
            ApiResponse(responseCode = "401", description = "참가자 세션 인증 실패"),
            ApiResponse(responseCode = "403", description = "CSRF 검증 실패"),
            ApiResponse(responseCode = "404", description = "신청한 일행 또는 다시 쓸 답변 없음"),
            ApiResponse(
                responseCode = "409",
                description =
                    "{status:409, code:FORM_CHANGED}(폼 변경, 다시 입력) 또는 이미 신청한 회차, 확정된 다른 회차와 시간 겹침, " +
                        "마감·시작된 회차, 사전등록 기간 아님, 정원 초과, 같은 키의 다른 내용",
            ),
            ApiResponse(responseCode = "502", description = "공급자 응답 계약 또는 내부 인증 오류"),
            ApiResponse(responseCode = "503", description = "참여 서비스 통지 비활성 또는 공급자 연동 불가(같은 키로 재시도)"),
        ],
    )
    fun addSession(
        @PathVariable expoId: UUID,
        @Valid @RequestBody body: AddOwnerPreregisterSessionRequest,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        request: HttpServletRequest,
    ): ResponseEntity<OwnerPreregisterSessionResult> =
        addSession
            .execute(
                AddOwnerPreregisterSessionCommand(
                    expoId,
                    session(request),
                    requireNotNull(body.sessionId),
                    requireNotNull(body.participantIds),
                    idempotencyKey,
                ),
            ).let { result ->
                ResponseEntity
                    .status(if (result.status == "CONFIRMED") HttpStatus.CREATED else HttpStatus.ACCEPTED)
                    .header("Cache-Control", "no-store")
                    .body(result)
            }

    @GetMapping("/preregister-waitlist")
    @Operation(
        summary = "내 사전등록 대기 조회",
        description =
            "참가자 세션의 검증 번호로 대기 일행을 찾습니다. sessionId가 없으면 가장 최근 대기 1건입니다. " +
                "대기 일행은 User 저장 전이라 QR이 없습니다. 응답 X-CSRF-TOKEN 헤더와 XSRF-TOKEN 쿠키를 대기 취소에 사용합니다.",
        responses = [
            ApiResponse(responseCode = "200", description = "대기 또는 처리 중인 일행(바로 잡은 예약 포함)"),
            ApiResponse(responseCode = "401", description = "참가자 세션 인증 실패"),
            ApiResponse(responseCode = "404", description = "대기 일행 없음"),
            ApiResponse(responseCode = "502", description = "User 응답 계약 또는 내부 인증 오류"),
            ApiResponse(responseCode = "503", description = "User 연동 불가"),
        ],
    )
    fun waitlist(
        @PathVariable expoId: UUID,
        @RequestParam(required = false) sessionId: Long?,
        request: HttpServletRequest,
        csrf: CsrfToken,
    ): ResponseEntity<OwnerPreregisterWaitlist> =
        ResponseEntity
            .ok()
            .header("Cache-Control", "no-store")
            .header(csrf.headerName, csrf.token)
            .body(getWaitlist.execute(OwnerPreregisterWaitlistCommand(expoId, session(request), sessionId)))

    @GetMapping("/preregister-waitlists")
    @Operation(
        summary = "내 사전등록 대기 목록 조회",
        description = "회차마다 대기한 일행을 최근 순으로 돌려줍니다(#198). 없으면 빈 배열입니다. 응답 X-CSRF-TOKEN 헤더와 XSRF-TOKEN 쿠키를 대기 취소에 사용합니다.",
        responses = [
            ApiResponse(responseCode = "200", description = "대기 또는 처리 중인 일행 목록(바로 잡은 예약 포함)"),
            ApiResponse(responseCode = "401", description = "참가자 세션 인증 실패"),
            ApiResponse(responseCode = "502", description = "User 응답 계약 또는 내부 인증 오류"),
            ApiResponse(responseCode = "503", description = "User 연동 불가"),
        ],
    )
    fun waitlists(
        @PathVariable expoId: UUID,
        request: HttpServletRequest,
        csrf: CsrfToken,
    ): ResponseEntity<List<OwnerPreregisterWaitlist>> =
        ResponseEntity
            .ok()
            .header("Cache-Control", "no-store")
            .header(csrf.headerName, csrf.token)
            .body(getWaitlists.execute(OwnerPreregisterWaitlistCommand(expoId, session(request))))

    @PostMapping("/preregister-waitlist/cancellation")
    @Operation(
        summary = "내 사전등록 대기 일행 전체 취소",
        description = "참가자 세션과 CSRF 토큰이 필요합니다. sessionId가 없으면 가장 최근 대기 1건을 취소합니다. 취소된 대기는 같은 Idempotency-Key로 다시 만들 수 없고, 재신청은 새 키로 합니다.",
        responses = [
            ApiResponse(responseCode = "204", description = "대기 취소 완료"),
            ApiResponse(responseCode = "401", description = "참가자 세션 인증 실패"),
            ApiResponse(responseCode = "403", description = "CSRF 검증 실패"),
            ApiResponse(responseCode = "404", description = "대기 일행 없음"),
            ApiResponse(responseCode = "409", description = "승급 처리 중이거나 삭제된 행사"),
            ApiResponse(responseCode = "502", description = "User 응답 계약 또는 내부 인증 오류"),
            ApiResponse(responseCode = "503", description = "User 연동 불가"),
        ],
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun withdraw(
        @PathVariable expoId: UUID,
        @RequestParam(required = false) sessionId: Long?,
        request: HttpServletRequest,
    ) = withdrawWaitlist.execute(OwnerPreregisterWaitlistCommand(expoId, session(request), sessionId))

    private fun session(request: HttpServletRequest): String =
        request.cookies
            ?.filter { it.name == sessionCookieName }
            ?.singleOrNull()
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?: throw ParticipantSessionException("SESSION_REQUIRED")
}

// 세션 401에 SESSION_REQUIRED/SESSION_EXPIRED code를(#188), 회차 추가의 폼 변경 409에 FORM_CHANGED code를(#199),
// 유형별 오픈 전 400에 OCCUPATION_NOT_OPEN code를(#209) 싣는다.
// 다른 상태와 응답은 건드리지 않는다.
@RestControllerAdvice(assignableTypes = [OwnerApplicationController::class])
class OwnerApplicationSessionExceptionHandler {
    @ExceptionHandler(ParticipantSessionException::class)
    fun sessionError(ex: ParticipantSessionException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("status" to 401, "code" to ex.code))

    @ExceptionHandler(OccupationNotOpenException::class)
    fun occupationNotOpen(ex: OccupationNotOpenException): ResponseEntity<Map<String, Any>> =
        ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(mapOf("status" to 400, "code" to "OCCUPATION_NOT_OPEN", "message" to ex.reason.orEmpty()))

    @ExceptionHandler(PreregisterFormChangedException::class)
    fun formChanged(ex: PreregisterFormChangedException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("status" to 409, "code" to "FORM_CHANGED"))
}
