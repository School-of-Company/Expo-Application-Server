package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import team.startup.application.domain.application.presentation.dto.AdminDataChangeEvent
import team.startup.application.domain.application.presentation.dto.AdminDataChangeResource
import team.startup.application.global.sse.SseConnectionRegistry
import team.startup.application.global.sse.SseLimits
import team.startup.application.global.sse.SseMessage
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** 행사별 관리자 신청 현황 변경 신호. 연결 관리는 공통 [SseConnectionRegistry]가 맡는다. */
@Component
@ConditionalOnProperty("application.admin-events.enabled", havingValue = "true")
class AdminDataChangeStream(
    mapper: ObjectMapper,
    private val clock: Clock,
    @Value("\${application.admin-events.max-connections:500}") maxConnections: Int,
    @Value("\${application.admin-events.max-connections-per-expo:50}") maxConnectionsPerExpo: Int,
    @Value("\${application.admin-events.connection-lifetime:PT30M}") lifetime: Duration,
    @Value("\${application.admin-events.heartbeat-interval:PT25S}") heartbeat: Duration,
) : DisposableBean {
    private val registry =
        SseConnectionRegistry<UUID>(
            "admin-data",
            SseLimits(maxConnections, maxConnectionsPerExpo, lifetime, heartbeat),
            mapper::writeValueAsString,
        )

    fun open(expoId: UUID): SseEmitter = registry.open(expoId)

    fun changed(expoId: UUID) = registry.publish(expoId, message(expoId))

    /** 전달 경로가 끊겼다 복구되면 그 사이 신호를 잃었을 수 있으므로 모든 구독자에게 재조회를 알린다. */
    fun resyncAll() = registry.publishAll(::message)

    fun connectionCount(expoId: UUID? = null): Int = registry.connectionCount(expoId)

    override fun destroy() = registry.close()

    private fun message(expoId: UUID): SseMessage {
        val event = AdminDataChangeEvent(UUID.randomUUID(), expoId, AdminDataChangeResource.APPLICATIONS, clock.instant())
        return SseMessage(event.eventId.toString(), EVENT_NAME, event)
    }

    companion object {
        const val EVENT_NAME = "admin-data-changed"
    }
}
