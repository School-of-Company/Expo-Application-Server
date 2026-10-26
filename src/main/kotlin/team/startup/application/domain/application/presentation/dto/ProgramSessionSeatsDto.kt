package team.startup.application.domain.application.presentation.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

data class ProgramSessionSeatsQuery(
    val expoId: UUID,
    val programType: ProgramSessionType,
)

@Schema(description = "프로그램·연수 회차 공개 정원 요약(#331). 회차별 숫자만 싣고 참가자 정보와 직군별(channels) 정원은 싣지 않습니다.")
data class ProgramSessionSeatsResponse(
    val expoId: UUID,
    val programType: ProgramSessionType,
    @field:Schema(description = "신청 정책이 있는 현재 회차. Expo 회차 목록 순서") val sessions: List<ProgramSessionSeats>,
)

data class ProgramSessionSeats(
    @field:Schema(description = "프로그램 회차 ID", example = "1") val programId: Long,
    @field:Schema(description = "사전 접수 마감 여부. 관리자 마감이거나 접수 종료 시각이 지났으면 true. 현장 접수는 admissionSeats로 판단")
    val closed: Boolean,
    @field:Schema(description = "참가자 선택 화면(participants)의 capacity와 같은 계산") val capacity: ProgramCapacityInfo,
    @field:Schema(description = "사전·현장 정원이 나뉜 회차만. 참가자 선택 화면의 admissionSeats와 같은 계산")
    val admissionSeats: ProgramAdmissionSeats? = null,
)
