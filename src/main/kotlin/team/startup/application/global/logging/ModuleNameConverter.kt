package team.startup.application.global.logging

import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent

/**
 * 로그의 `module` 필드를 로거 이름에서 만든다. 호출부에서 따로 넣지 않는다.
 * 우리 코드(`team.startup.application.domain.<기능>...`, `team.startup.application.global.<기능>...`)는 domain/global 바로 다음 이름을 쓴다
 * (service·presentation 같은 계층 이름이 아니라 기능별로 묶이게). 그 밖의 로거(라이브러리 등)는 로거 이름의 바로 위 패키지를 쓴다.
 */
class ModuleNameConverter : ClassicConverter() {
    override fun convert(event: ILoggingEvent): String = moduleOf(event.loggerName)

    companion object {
        fun moduleOf(loggerName: String): String {
            val segments = loggerName.split('.')
            if (segments.size >= 5 && segments[0] == "team" && segments[1] == "startup" && segments[2] == "application" &&
                (segments[3] == "domain" || segments[3] == "global")
            ) {
                return segments[4]
            }
            return if (segments.size >= 2) segments[segments.size - 2] else segments.first()
        }
    }
}
