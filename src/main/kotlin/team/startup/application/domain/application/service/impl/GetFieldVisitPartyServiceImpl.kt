package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import team.startup.application.domain.application.presentation.dto.FieldVisitParty
import team.startup.application.domain.application.presentation.dto.FieldVisitPartyParticipant
import team.startup.application.domain.application.presentation.dto.GetOwnerApplicationsCommand
import team.startup.application.domain.application.repository.FieldVisitRegistrationRepository
import team.startup.application.domain.application.service.GetFieldVisitPartyService
import team.startup.application.domain.application.time.ExpoProgramTime
import java.time.Clock
import java.time.LocalDate

@Service
class GetFieldVisitPartyServiceImpl(
    private val gateway: RegistrationGateway,
    private val visits: FieldVisitRegistrationRepository,
    private val clock: Clock = Clock.systemUTC(),
) : GetFieldVisitPartyService {
    override fun execute(command: GetOwnerApplicationsCommand): FieldVisitParty {
        val party = gateway.managedParty(command.expoId, command.sessionId)
        val today = LocalDate.now(clock.withZone(ExpoProgramTime.seoul))
        return FieldVisitParty(
            today,
            party.participants.map {
                val visit = visits.find(command.expoId, it.participantId, today)
                FieldVisitPartyParticipant(
                    it.participantId,
                    it.name,
                    it.representative,
                    visits.preregistered(command.expoId, it.participantId, today),
                    visit,
                    it.legacyFieldRegistered && visits.findByParticipants(command.expoId, listOf(it.participantId)).isEmpty(),
                )
            },
        )
    }
}
