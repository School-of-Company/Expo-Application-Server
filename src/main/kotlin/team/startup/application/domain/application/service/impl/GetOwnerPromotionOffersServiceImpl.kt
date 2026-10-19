package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import team.startup.application.domain.application.presentation.dto.OwnerPromotionOffer
import team.startup.application.domain.application.presentation.dto.OwnerPromotionOfferCommand
import team.startup.application.domain.application.repository.PromotionOfferRepository
import team.startup.application.domain.application.service.GetOwnerPromotionOffersService
import java.time.Clock

// Program offers come from the session's managed party; a waiting preregistration party has no User participant yet,
// so its offer is found by the verified session phone, like the owner waitlist view.
@Service
class GetOwnerPromotionOffersServiceImpl(
    private val gateway: RegistrationGateway,
    private val offers: PromotionOfferRepository,
    private val shared: PromotionOffers,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) : GetOwnerPromotionOffersService {
    private val reads = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    @Transactional(readOnly = true, propagation = Propagation.NOT_SUPPORTED)
    override fun execute(command: OwnerPromotionOfferCommand): List<OwnerPromotionOffer> {
        val party = gateway.managedParty(command.expoId, command.participantSession)
        val phone = gateway.participantSessionPhone(command.expoId.toString(), command.participantSession)
        val now = clock.instant()
        return requireNotNull(
            reads.execute {
                (
                    offers.forParty(command.expoId, party.participants.map { it.participantId }, party.representativeId) +
                        offers.forPhone(command.expoId, phone)
                ).map { shared.owner(it, now) }
            },
        )
    }
}
