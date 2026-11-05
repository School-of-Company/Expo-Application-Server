package team.startup.application.global.client.attendance

import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import tools.jackson.databind.JsonNode
import java.util.UUID

@FeignClient(
    name = "expo-attendance-server",
    contextId = "attendanceRead",
    // 빈 URL도 명시적인 target을 가지며, 게이트웨이가 호출 전에 기존 503으로 거절한다.
    url =
        "#{@environment.getProperty('application.attendance.url', '').isBlank() ? " +
            "'http://attendance.invalid' : @environment.getProperty('application.attendance.url').replaceAll('/+$', '')}",
    configuration = [AttendanceClientConfiguration::class],
)
interface AttendanceClient {
    @PostMapping("/internal/program-attendances/summary", consumes = ["application/json"])
    fun summarize(
        @RequestBody request: SummaryRequest,
    ): JsonNode

    @GetMapping("/internal/program-attendances/{type}/{programId}")
    fun attendances(
        @PathVariable("type") type: String,
        @PathVariable("programId") programId: Long,
    ): JsonNode

    @PostMapping("/internal/expos/{expoId}/participants/{participantId}/cancellation-status", consumes = ["application/json"])
    fun cancellationStatus(
        @PathVariable("expoId") expoId: UUID,
        @PathVariable("participantId") participantId: Long,
        @RequestBody request: Map<String, String>,
    ): JsonNode

    data class ProgramRef(
        val programType: ProgramSessionType,
        val programId: Long,
    )

    data class SummaryRequest(
        val programs: List<ProgramRef>,
    )
}
