package team.startup.application.domain.application.presentation.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

enum class AdminDataChangeResource { APPLICATIONS, ATTENDANCE }

/** SSE `admin-data-changed`의 data. 다시 조회할 리소스만 알리며 이름·전화번호·폼 답변·토큰을 담지 않는다. */
@Schema(description = "관리자 데이터 변경 신호. 받으면 해당 리소스의 기존 GET을 현재 필터로 다시 조회한다.")
data class AdminDataChangeEvent(
    @field:Schema(description = "이벤트 ID. SSE id와 같다") val eventId: UUID,
    @field:Schema(description = "행사 ID") val expoId: UUID,
    @field:Schema(description = "변경된 리소스. Application은 APPLICATIONS만 보낸다") val resource: AdminDataChangeResource,
    @field:Schema(description = "신호를 만든 시각") val occurredAt: Instant,
)

data class SubscribeAdminDataChangesCommand(
    val expoId: UUID,
    val authorization: String?,
)
