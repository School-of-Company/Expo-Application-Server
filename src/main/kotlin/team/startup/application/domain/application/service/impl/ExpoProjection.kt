package team.startup.application.domain.application.service.impl

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import team.startup.application.domain.application.repository.ExpoProjectionRepository
import team.startup.application.domain.application.repository.ExpoProjectionStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// KAFKA_EXPO_PROJECTION_ENABLED=true일 때만 있는 Expo 읽기 경로(docs/expo-projection.md). 없으면(null) 게이트웨이는 예전 동기 조회만 한다.
// MISS(행 없음·tombstone·삭제 중·회차 없음)면 null을 돌려주고 호출자가 기존 동기 조회로 넘어간다. 같은 대상의 MISS 로그는 한 번만 남긴다.
@Component
@ConditionalOnProperty(name = ["application.expo-projection.enabled"], havingValue = "true")
class ExpoProjection(
    private val store: ExpoProjectionStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val missed = ConcurrentHashMap.newKeySet<String>()

    fun expo(expoId: UUID): ExpoProjectionRepository.Expo? = store.expo(expoId) ?: miss("expo", expoId, null)

    fun session(
        expoId: UUID,
        sessionId: Long,
    ): ExpoProjectionRepository.Session? = store.session(expoId, sessionId) ?: miss("session", expoId, sessionId)

    private fun miss(
        kind: String,
        expoId: UUID,
        sessionId: Long?,
    ): Nothing? {
        if (missed.size < MAX_LOGGED_MISSES && missed.add("$kind:$expoId:$sessionId")) {
            log.info("Expo projection miss, falling back to sync call: kind={}, expoId={}, sessionId={}", kind, expoId, sessionId)
        }
        return null
    }

    companion object {
        private const val MAX_LOGGED_MISSES = 10_000

        // Expo는 expo_id를 VARCHAR(36)로 둔다. 표준 UUID 문자열(대소문자 무관)만 프로젝션 키로 본다.
        fun uuidOrNull(value: String): UUID? =
            runCatching { UUID.fromString(value) }.getOrNull()?.takeIf { it.toString().equals(value, ignoreCase = true) }
    }
}
