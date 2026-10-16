package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AdminCurrentSessionPage
import team.startup.application.domain.application.presentation.dto.AdminCurrentSessionQuery
import team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.ProgramWaitlistRepository
import team.startup.application.domain.application.service.GetAdminCurrentSessionsService
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Service
class GetAdminCurrentSessionsServiceImpl(
    private val expo: AdminExpoGateway,
    private val ledger: ProgramSessionApplicationRepository,
    private val waitlist: ProgramWaitlistRepository,
    private val mapper: ObjectMapper,
    transactionManager: PlatformTransactionManager,
) : GetAdminCurrentSessionsService {
    private val reads = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    @Transactional(readOnly = true, propagation = Propagation.NOT_SUPPORTED)
    override fun execute(
        expoId: UUID,
        query: AdminCurrentSessionQuery,
    ): AdminCurrentSessionPage {
        val page = expo.sessions(expoId, query)
        return requireNotNull(
            reads.execute {
                val definitions =
                    try {
                        page.items.map { mapper.treeToValue(it, ProgramSessionDefinition::class.java) }
                    } catch (ex: JacksonException) {
                        throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "현재 회차의 좌석 정의가 올바르지 않습니다.", ex)
                    }
                ledger.lockCapacityReads(definitions)
                page.copy(
                    items =
                        page.items.zip(definitions).map { (item, definition) ->
                            waitlist.checkDefinition(definition)
                            item.deepCopy().asObject().apply {
                                set("admissionSeats", mapper.valueToTree(ledger.admissionSeats(definition)))
                            }
                        },
                )
            },
        )
    }
}
