package team.startup.application.domain.application.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.presentation.dto.AdminApplicationPageViews
import team.startup.application.domain.application.presentation.dto.ApplicationPageViewDay
import team.startup.application.domain.application.presentation.dto.RecordApplicationPageViewCommand
import team.startup.application.domain.application.repository.ApplicationPageViewRepository
import team.startup.application.domain.application.service.GetAdminApplicationPageViewsService
import team.startup.application.domain.application.service.RecordApplicationPageViewService
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

private val SEOUL = ZoneId.of("Asia/Seoul")

@Service
class RecordApplicationPageViewServiceImpl(
    private val repository: ApplicationPageViewRepository,
) : RecordApplicationPageViewService {
    // ponytail: unknown expoIds are accepted without an Expo call; the Gateway per-IP limit bounds junk rows.
    @Transactional
    override fun execute(command: RecordApplicationPageViewCommand) {
        val date = LocalDate.now(SEOUL)
        // The day and expo salt the hash, so stored visitors cannot be linked across days or expos.
        val hash = MessageDigest.getInstance("SHA-256").digest("${command.expoId}:$date:${command.visitorId}".toByteArray())
        repository.record(command.expoId, date, hash)
    }
}

@Service
class GetAdminApplicationPageViewsServiceImpl(
    private val repository: ApplicationPageViewRepository,
) : GetAdminApplicationPageViewsService {
    @Transactional(readOnly = true)
    override fun execute(expoId: UUID): AdminApplicationPageViews {
        val today = LocalDate.now(SEOUL)
        val daily = repository.daily(expoId)
        return AdminApplicationPageViews(
            expoId,
            daily.find { it.date == today } ?: ApplicationPageViewDay(today, 0, 0),
            daily.sumOf { it.pageViews },
            daily,
        )
    }
}

// Daily totals keep their counters, so deleting visitor hashes never changes past PV or UV.
@Component
@EnableScheduling
class ApplicationPageVisitorPurgeScheduler(
    private val repository: ApplicationPageViewRepository,
    // Proposed 48h grace after the KST day ends (#108 feedback); not a confirmed retention policy.
    @Value("\${application.page-views.visitor-hash-retention-hours:48}") private val retentionHours: Long,
) {
    @Scheduled(fixedDelayString = "\${application.page-views.purge-delay-ms:3600000}", initialDelayString = "60000")
    fun purge() {
        purge(Instant.now())
    }

    fun purge(now: Instant): Int {
        // A day D is purged once D+1 00:00 KST plus the grace has passed.
        val cutoff = now.minus(Duration.ofHours(retentionHours)).atZone(SEOUL).toLocalDate()
        return repository.purgeVisitorsBefore(cutoff)
    }
}
