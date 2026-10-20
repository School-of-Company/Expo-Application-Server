package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import team.startup.application.domain.application.repository.PreregisterReservation
import java.time.Duration
import java.time.Instant

/**
 * #222: User 저장 재시도가 끝나지 않는 RESERVED 예약을 운영자 확인(needsReview)으로 넘기는 기준.
 * 횟수와 경과 시간 중 먼저 오는 쪽이며, `attempts`는 전송 시작과 실패마다 올라 한 번의 실패에 둘씩 늘 수 있다.
 * 넘긴 뒤에도 좌석은 풀지 않는다.
 */
@Component
class ReservationRetryLimit(
    @Value("\${application.registration.reservation-review.max-attempts:40}") private val maxAttempts: Int,
    @Value("\${application.registration.reservation-review.max-age-minutes:60}") maxAgeMinutes: Long,
) {
    private val maxAge: Duration = Duration.ofMinutes(maxAgeMinutes)

    fun exceeded(
        reservation: PreregisterReservation,
        now: Instant = Instant.now(),
    ): Boolean {
        val startedAt = reservation.retryWindowStartedAt
        val byAge = startedAt != null && !now.isBefore(startedAt.plus(maxAge))
        val byCount = reservation.attempts - reservation.retryWindowAttempts >= maxAttempts
        return byAge || byCount
    }
}
