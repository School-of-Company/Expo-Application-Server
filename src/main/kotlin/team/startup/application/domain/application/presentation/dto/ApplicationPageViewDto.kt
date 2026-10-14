package team.startup.application.domain.application.presentation.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import java.time.LocalDate
import java.util.UUID

@Schema(description = "신청화면 진입 기록. visitorId는 브라우저가 한 번 만들어 보관하는 무작위 UUID이며 원문은 저장하지 않습니다.")
data class RecordApplicationPageViewRequest(
    @field:NotNull val visitorId: UUID?,
)

data class RecordApplicationPageViewCommand(
    val expoId: UUID,
    val visitorId: UUID,
)

@Schema(description = "신청화면 조회 집계. 날짜는 Asia/Seoul 기준이며 새로고침도 PV에 포함합니다.")
data class AdminApplicationPageViews(
    val expoId: UUID,
    val today: ApplicationPageViewDay,
    val totalPageViews: Long,
    @field:Schema(description = "기록이 있는 날짜의 일별 PV·UV, 날짜 오름차순") val daily: List<ApplicationPageViewDay>,
)

data class ApplicationPageViewDay(
    val date: LocalDate,
    val pageViews: Long,
    @field:Schema(description = "그날 고유 visitorId 수") val uniqueVisitors: Long,
)
