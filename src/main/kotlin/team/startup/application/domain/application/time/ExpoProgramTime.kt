package team.startup.application.domain.application.time

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

object ExpoProgramTime {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val spaceFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)

    fun parse(value: String): OffsetDateTime =
        try {
            OffsetDateTime.parse(value)
        } catch (_: DateTimeParseException) {
            val format = if (' ' in value) spaceFormat else DateTimeFormatter.ISO_LOCAL_DATE_TIME
            LocalDateTime.parse(value, format).atZone(seoul).toOffsetDateTime()
        }
}
