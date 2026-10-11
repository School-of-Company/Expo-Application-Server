package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

@Component
class ProgramPromotionPolicy(
    @Value("\${application.promotions.enabled:false}") enabled: Boolean,
    @Value("\${application.promotions.publish-from:}") publishFrom: String,
) {
    private val cutoff = publishFrom.takeIf { it.isNotBlank() }?.let(Instant::parse)

    init {
        require(!enabled || cutoff != null) { "APPLICATION_PROMOTIONS_PUBLISH_FROM must be a fixed activation instant" }
    }

    fun cutoff(): Instant = cutoff ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "승급 발행 시작 시각이 설정되지 않았습니다.")
}
