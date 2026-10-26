package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AdminCurrentSessionQuery
import team.startup.application.domain.application.presentation.dto.ProgramCapacityInfo
import team.startup.application.domain.application.presentation.dto.ProgramSessionDefinition
import team.startup.application.domain.application.presentation.dto.ProgramSessionSeats
import team.startup.application.domain.application.presentation.dto.ProgramSessionSeatsQuery
import team.startup.application.domain.application.presentation.dto.ProgramSessionSeatsResponse
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.ProgramWaitlistRepository
import team.startup.application.domain.application.service.GetProgramSessionSeatsService
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.time.Clock

@Service
class GetProgramSessionSeatsServiceImpl(
    private val expo: AdminExpoGateway,
    private val ledger: ProgramSessionApplicationRepository,
    private val waitlist: ProgramWaitlistRepository,
    private val mapper: ObjectMapper,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
) : GetProgramSessionSeatsService {
    // 공개 경로라 신청과 경합하는 회차 advisory lock은 잡지 않고, 한 스냅샷에서 모든 회차를 센다.
    private val reads =
        TransactionTemplate(transactionManager).apply {
            isReadOnly = true
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        }

    @Transactional(readOnly = true, propagation = Propagation.NOT_SUPPORTED)
    override fun execute(query: ProgramSessionSeatsQuery): ProgramSessionSeatsResponse {
        val definitions =
            definitions(query).filter { it.policy != null }.onEach {
                if (!requireNotNull(it.policy).admissionCapacitiesValid()) throw ResponseStatusException(HttpStatus.BAD_GATEWAY)
            }
        val now = clock.instant()
        return requireNotNull(
            reads.execute {
                waitlist.checkDefinitions(definitions)
                val usage = ledger.seatUsage(query.expoId, query.programType, definitions.map { it.id })
                ProgramSessionSeatsResponse(
                    query.expoId,
                    query.programType,
                    definitions.map { definition ->
                        val policy = requireNotNull(definition.policy)
                        val used = requireNotNull(usage[definition.id])
                        ProgramSessionSeats(
                            definition.id,
                            policy.closed || !now.isBefore(policy.applicationEndedAt),
                            ProgramCapacityInfo.of(policy.capacity, used.confirmed, policy.waitingCapacity, used.waiting),
                            policy
                                .takeIf { it.hasAdmissionCapacities() }
                                ?.let { ledger.admissionSeats(it, used.preRegistration, used.onsite, used.unknown, used.offered) },
                        )
                    },
                )
            },
        )
    }

    // Expo 현재 회차 페이지(최대 100개)를 definitionVersion으로 고정해 읽는다. 호출 수는 회차 100개당 1회다.
    private fun definitions(query: ProgramSessionSeatsQuery): List<ProgramSessionDefinition> {
        val items = mutableListOf<ProgramSessionDefinition>()
        var version: String? = null
        var page = 0
        do {
            val result =
                expo.sessions(
                    query.expoId,
                    AdminCurrentSessionQuery(query.programType, page = page, size = PAGE, definitionVersion = version),
                )
            version = result.definitionVersion
            items +=
                try {
                    // RegistrationGateway와 같이 저장 정의와 비교할 expoId 표기를 맞춘다.
                    result.items.map {
                        mapper.treeToValue(it, ProgramSessionDefinition::class.java).copy(expoId = query.expoId.toString())
                    }
                } catch (ex: JacksonException) {
                    throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "현재 회차의 좌석 정의가 올바르지 않습니다.", ex)
                }
            page++
        } while (page.toLong() * PAGE < result.totalElements)
        return items
    }

    private companion object {
        const val PAGE = 100
    }
}
