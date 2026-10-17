package team.startup.application.domain.application.service

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import team.startup.application.domain.application.presentation.dto.SubscribeAdminDataChangesCommand

interface SubscribeAdminDataChangesService {
    fun execute(command: SubscribeAdminDataChangesCommand): SseEmitter
}
