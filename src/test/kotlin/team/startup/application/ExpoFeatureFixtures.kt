package team.startup.application

// #418: Expo `GET /internal/expo/{id}`의 `features` 배열 대역. `off`에 넣은 키만 실효 OFF이며, 바꾼 테스트는 finally에서 되돌린다.
object ExpoFeatureFixtures {
    private val keys =
        listOf(
            "PRE_REGISTRATION",
            "FIELD_REGISTRATION",
            "COMPANION_REGISTRATION",
            "QR_ADMISSION",
            "PAPER_QR",
            "STANDARD_PROGRAM",
            "TRAINING_PROGRAM",
            "STANDARD_ATTENDANCE",
            "TRAINING_ATTENDANCE",
            "SURVEY",
            "SURVEY_LOTTERY",
            "AUTO_NOTIFICATION",
            "BULK_MESSAGE",
        )

    @Volatile var off: Set<String> = emptySet()

    // featureEnabled는 직렬화하지 않으므로(@JsonIgnore) Expo 프로그램 정의 대역 JSON의 첫 객체에 실효 ON을 넣는다.
    fun programEnabled(json: String): String = json.replaceFirst("{", "{\"featureEnabled\":true,")

    fun json(): String =
        keys.joinToString(prefix = "[", postfix = "]") {
            """{"key":"$it","configuredEnabled":${it !in off},"effectiveEnabled":${it !in off}}"""
        }
}
