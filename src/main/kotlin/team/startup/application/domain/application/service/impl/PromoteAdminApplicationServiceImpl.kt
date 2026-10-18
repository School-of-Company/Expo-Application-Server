package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AdminPromotionResult
import team.startup.application.domain.application.presentation.dto.PromoteAdminApplicationCommand
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.ProgramWaitlistRepository
import team.startup.application.domain.application.repository.PromotionOfferRepository
import team.startup.application.domain.application.repository.StudentGuardianBatchRepository
import team.startup.application.domain.application.service.PromoteAdminApplicationService

// #172: after the stored policy stops automatic promotion, an admin with Expo APPLICATION_PROMOTE confirms the waiting
// row (or its whole guardian batch) an operator chose. Expo/User lookups run before the transaction; every promotion
// check runs again under the same participant → schedule/group/program locks as automatic promotion and acceptance.
@Service
class PromoteAdminApplicationServiceImpl(
    private val expo: AdminExpoGateway,
    private val gateway: RegistrationGateway,
    private val applications: ProgramWaitlistRepository,
    private val waitlist: ProgramWaitlist,
    private val guardianBatches: StudentGuardianBatchPromotion,
    private val batches: StudentGuardianBatchRepository,
    private val offers: PromotionOfferRepository,
    private val ledger: ProgramSessionApplicationRepository,
    private val transactions: TransactionTemplate,
) : PromoteAdminApplicationService {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: PromoteAdminApplicationCommand): AdminPromotionResult {
        val access = expo.requirePromoteAccess(command.expoId, command.authorization)
        val original = target(command)
        val definition =
            gateway.programDefinition(original.key.expoId.toString(), original.key.programType, original.key.programId)
                ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "프로그램 정의 공급자가 준비되지 않았습니다.")
        transactions.executeWithoutResult {
            val batchId = batches.batchId(original.id)
            val members = batchId?.let { id -> batches.members(id).filter { it.status == "WAITING" } } ?: listOf(original)
            val guardians = if (batchId == null) emptyList() else batches.links(members).map { it.guardianId }
            (members.map { it.participantId } + guardians).toSet().sorted().forEach(ledger::lockParticipant)
            waitlist.lock(definition)
            applications.observe(definition)
            val current = target(command)
            // A retry, a concurrent admin or an automatic promotion that already confirmed it is not a second seat.
            if (current.status == "CONFIRMED") return@executeWithoutResult
            if (current.status != "WAITING") conflict("NOT_WAITING")
            // An active offer's seats belong to its representative's answer; it confirms or ends through #144 first.
            if (members.any { offers.activeForApplication(it.id) != null }) conflict("OFFER_ACTIVE")
            val reason =
                if (batchId != null) {
                    guardianBatches.confirmManually(definition, current, access.adminId)
                } else {
                    waitlist.confirmManually(definition, current, access.adminId)
                }
            when (reason) {
                null -> Unit
                "BUSY", "UNAVAILABLE", "EXPO_UNAVAILABLE" -> throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, reason)
                else -> conflict(reason)
            }
        }
        return AdminPromotionResult(command.applicationId)
    }

    private fun target(command: PromoteAdminApplicationCommand): ProgramWaitlistRepository.Entry =
        applications.entry(command.applicationId)?.takeIf { it.key.expoId == command.expoId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    private fun conflict(reason: String): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, reason)
}
