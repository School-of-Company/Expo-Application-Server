package team.startup.application.global.client.attendance

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "application.attendance")
data class AttendanceClientProperties(
    val url: String = "",
    val internalToken: String = "",
) {
    val configured: Boolean get() = url.isNotBlank() && internalToken.isNotBlank()

    override fun toString() = "AttendanceClientProperties(url=$url, internalToken=***)"
}
