package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import team.startup.application.domain.application.presentation.dto.ProgramSessionSeatsQuery
import team.startup.application.domain.application.presentation.dto.ProgramSessionSeatsResponse
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.service.GetProgramSessionSeatsService
import java.util.UUID

@RestController
@ConditionalOnProperty(name = ["application.program-sessions.enabled"], havingValue = "true")
class ProgramSessionSeatsController(
    private val service: GetProgramSessionSeatsService,
) {
    @Operation(
        summary = "프로그램·연수 회차 공개 정원 요약",
        description =
            "로그인 없이 조회하는 화면 안내용 값입니다. 박람회·프로그램 종류의 모든 현재 회차를 한 번에 돌려주며 참가자 정보와 " +
                "직군별(channels) 정원은 싣지 않습니다. 신청 시 정원을 다시 판정합니다.",
        responses = [
            ApiResponse(responseCode = "200", description = "회차별 정원 요약"),
            ApiResponse(responseCode = "400", description = "잘못된 박람회 ID 또는 프로그램 종류"),
            ApiResponse(responseCode = "404", description = "박람회 없음"),
            ApiResponse(responseCode = "409", description = "회차 정의 변경 중"),
            ApiResponse(responseCode = "502", description = "Expo 응답 계약 오류"),
            ApiResponse(responseCode = "503", description = "Expo 연동 불가 또는 행사 정의 변경 중"),
        ],
    )
    @GetMapping("/application/expos/{expoId}/program-sessions/{programType}/seats")
    fun seats(
        @PathVariable expoId: UUID,
        @PathVariable programType: ProgramSessionType,
    ): ResponseEntity<ProgramSessionSeatsResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.execute(ProgramSessionSeatsQuery(expoId, programType)))
}
