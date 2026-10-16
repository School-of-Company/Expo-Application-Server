package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationPage
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationReadCommand
import team.startup.application.domain.application.service.GetAdminPreregistrationsService

@Service
class GetAdminPreregistrationsServiceImpl(
    private val reader: AdminPreregistrationReader,
) : GetAdminPreregistrationsService {
    @Transactional(readOnly = true, propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: AdminPreregistrationReadCommand): AdminPreregistrationPage {
        val (expoId, query) = command
        val (rows, total) = reader.read(expoId, query, query.size, query.page.toLong() * query.size)
        return AdminPreregistrationPage(rows, total, query.page, query.size)
    }
}
