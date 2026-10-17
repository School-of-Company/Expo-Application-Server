package team.startup.application.domain.application.time

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

object ExpoProgramTime {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val spaceFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)
    private val dateFormat = DateTimeFormatter.ofPattern("MM.dd")
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    private val weekdays = listOf("월", "화", "수", "목", "금", "토", "일")

    fun parse(value: String): OffsetDateTime =
        try {
            OffsetDateTime.parse(value)
        } catch (_: DateTimeParseException) {
            val format = if (' ' in value) spaceFormat else DateTimeFormatter.ISO_LOCAL_DATE_TIME
            LocalDateTime.parse(value, format).atZone(seoul).toOffsetDateTime()
        }

    // 안내 문자 `{일시}`: 같은 날 `10.31(토) 09:30~12:30`, 다른 날 `10.31(토) 09:30 ~ 11.01(일) 16:00`, 시작만 `10.31(토) 09:30`
    fun display(
        startedAt: String,
        endedAt: String?,
    ): String {
        val start = parse(startedAt).atZoneSameInstant(seoul)
        val end = endedAt?.let { parse(it).atZoneSameInstant(seoul) }

        fun day(value: ZonedDateTime) = "${value.format(dateFormat)}(${weekdays[value.dayOfWeek.value - 1]})"
        val started = "${day(start)} ${start.format(timeFormat)}"
        return when {
            end == null -> started
            end.toLocalDate() == start.toLocalDate() -> "$started~${end.format(timeFormat)}"
            else -> "$started ~ ${day(end)} ${end.format(timeFormat)}"
        }
    }
}
