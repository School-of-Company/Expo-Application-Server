package team.startup.application.domain.application.exception

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

// #268: 회차 변경 409의 사유 코드. Expo가 관리자 응답에 그대로 전달한다(Expo-Expo-Server#188).
enum class SessionChangeConflictCode(
    val message: String,
) {
    TIME_LOCKED_BY_HISTORY("신청 이력이 있는 회차는 시간을 바꿀 수 없습니다."),
    STRUCTURE_LOCKED_BY_HISTORY("신청 이력이 있는 회차는 삭제하거나 그룹·채널·정원 구성을 바꿀 수 없습니다."),
    BELOW_CONFIRMED_COUNT("확정·사용 중인 좌석보다 정원을 줄일 수 없습니다."),
    BELOW_WAITING_COUNT("현재 대기 인원보다 대기 정원을 줄일 수 없습니다."),
}

class SessionChangeConflictException(
    val code: SessionChangeConflictCode,
) : ResponseStatusException(HttpStatus.CONFLICT, code.message)
