package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationDetailCommand
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationQuery
import team.startup.application.domain.application.presentation.dto.AdminPreregistrationRow
import team.startup.application.domain.application.service.GetAdminPreregistrationDetailService

@Service
class GetAdminPreregistrationDetailServiceImpl(
    private val reader: AdminPreregistrationReader,
) : GetAdminPreregistrationDetailService {
    @Transactional(readOnly = true, propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: AdminPreregistrationDetailCommand): AdminPreregistrationRow =
        reader.read(command.expoId, AdminPreregistrationQuery(), 1, keys = listOf(command.key)).first.single()
}
