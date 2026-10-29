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

    fun parse(value: String): OffsetDateTime {
        if (value.length == 16 && (value[10] == 'T' || value[10] == ' ')) {
            val format = if (value[10] == ' ') spaceFormat else DateTimeFormatter.ISO_LOCAL_DATE_TIME
            return LocalDateTime.parse(value, format).atZone(seoul).toOffsetDateTime()
        }
        return try {
            OffsetDateTime.parse(value)
        } catch (_: DateTimeParseException) {
            val format = if (' ' in value) spaceFormat else DateTimeFormatter.ISO_LOCAL_DATE_TIME
            LocalDateTime.parse(value, format).atZone(seoul).toOffsetDateTime()
        }
    }

    // #251: 표기만 다른 같은 시각은 같은 시각이다. 읽을 수 없는 값은 문자열로 비교한다.
    fun same(
        a: String?,
        b: String?,
    ): Boolean = a == b || (a != null && b != null && runCatching { parse(a).isEqual(parse(b)) }.getOrDefault(false))

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
