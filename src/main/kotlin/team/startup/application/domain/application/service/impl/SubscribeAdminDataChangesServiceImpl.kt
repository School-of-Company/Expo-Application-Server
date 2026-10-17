package team.startup.application.domain.application.service.impl

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import team.startup.application.domain.application.presentation.dto.AuthorizeAdminApplicationCommand
import team.startup.application.domain.application.presentation.dto.SubscribeAdminDataChangesCommand
import team.startup.application.domain.application.service.AuthorizeAdminApplicationService
import team.startup.application.domain.application.service.SubscribeAdminDataChangesService
import team.startup.application.global.sse.SseConnectionLimitException

@Service
@ConditionalOnProperty("application.admin-events.enabled", havingValue = "true")
class SubscribeAdminDataChangesServiceImpl(
    private val authorization: AuthorizeAdminApplicationService,
    private val stream: AdminDataChangeStream,
) : SubscribeAdminDataChangesService {
    override fun execute(command: SubscribeAdminDataChangesCommand): SseEmitter {
        // 관리자 원장 조회와 같은 APPLICATION_READ 검증. 연결 수명이 끝나 재연결할 때마다 다시 검증한다.
        authorization.execute(AuthorizeAdminApplicationCommand(command.expoId, command.authorization))
        return try {
            stream.open(command.expoId)
        } catch (_: SseConnectionLimitException) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "실시간 연결이 많습니다. 잠시 후 다시 연결해 주세요.")
        }
    }
}
