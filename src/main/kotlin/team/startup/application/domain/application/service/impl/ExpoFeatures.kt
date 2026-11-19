package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition
import tools.jackson.databind.JsonNode

/**
 * #418: Expo 박람회 기능 설정(Expo-Expo-Server docs/expo-feature-settings.md "내부 정책 조회 계약")의 소비자 쪽 해석.
 * `GET /internal/expo/{id}`의 `features[].effectiveEnabled`만 본다. OFF면 409 FEATURE_DISABLED, 필드 누락·해석 불가면 503이며
 * 기본 ON/OFF로 대신하지 않는다. 정책 조회는 호출자가 DB 잠금 밖에서 끝낸다.
 */
object ExpoFeature {
    const val PRE_REGISTRATION = "PRE_REGISTRATION"
    const val FIELD_REGISTRATION = "FIELD_REGISTRATION"
    const val COMPANION_REGISTRATION = "COMPANION_REGISTRATION"
    const val AUTO_NOTIFICATION = "AUTO_NOTIFICATION"
    const val BULK_MESSAGE = "BULK_MESSAGE"

    const val DISABLED = "FEATURE_DISABLED"

    // ExpoPeriod를 직접 만든 값(테스트 대역)의 기본값. Expo 응답은 expoPeriod()가 언제나 실제 값이나 null로 덮어쓴다.
    val ALL_ON: Map<String, Boolean> =
        listOf(PRE_REGISTRATION, FIELD_REGISTRATION, COMPANION_REGISTRATION, AUTO_NOTIFICATION, BULK_MESSAGE).associateWith { true }

    // `[{key, effectiveEnabled}]` 배열이 아니거나 항목이 하나라도 이상하면 null(해석 불가)이다.
    fun parse(node: JsonNode?): Map<String, Boolean>? {
        if (node == null || !node.isArray) return null
        val result = mutableMapOf<String, Boolean>()
        for (index in 0 until node.size()) {
            val key = node.get(index).get("key")
            val enabled = node.get(index).get("effectiveEnabled")
            if (key?.isString != true || enabled?.isBoolean != true || result.put(key.stringValue(), enabled.booleanValue()) != null) {
                return null
            }
        }
        return result
    }

    fun require(
        features: Map<String, Boolean>?,
        vararg keys: String,
    ) {
        if (features == null || keys.any { features[it] == null }) unavailable()
        if (keys.any { features[it] == false }) throw FeatureDisabledException()
    }

    fun unavailable(): Nothing = throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "박람회 기능 설정을 확인할 수 없습니다.")
}

class FeatureDisabledException : ResponseStatusException(HttpStatus.CONFLICT, ExpoFeature.DISABLED)

fun ExpoPeriod.requireFeatures(vararg keys: String) = ExpoFeature.require(featureStates, *keys)

// 배경 작업용: ON true, OFF false. 확인 불가(503 등)는 예외 그대로 호출자가 보류한다.
fun RegistrationGateway.featureEnabled(
    expoId: String,
    key: String,
): Boolean =
    try {
        requireFeatures(expoId, key)
        true
    } catch (_: FeatureDisabledException) {
        false
    }

// 발행기용: 확인 불가(조회 실패)는 null이다.
fun RegistrationGateway.featureStateOrNull(
    expoId: String,
    key: String,
): Boolean? =
    try {
        featureEnabled(expoId, key)
    } catch (_: ResponseStatusException) {
        null
    }

// 프로그램 정의의 `featureEnabled`(그 종류 프로그램 기능의 실효 값). 빈 목록·404와 구분되는 OFF 사유다.
fun ProgramSessionDefinition.requireFeatureEnabled() {
    when (featureEnabled) {
        true -> Unit
        false -> throw FeatureDisabledException()
        null -> ExpoFeature.unavailable()
    }
}
