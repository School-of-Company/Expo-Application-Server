package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.repository.PreregisterSessionRepository
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.service.DeleteStandardProgramApplicationsService
import team.startup.application.domain.application.service.DeleteTrainingProgramApplicationsService
import team.startup.application.domain.application.service.PurgePreregisterSessionsService
import java.util.UUID

@Service
class PurgePreregisterSessionsServiceImpl(
    private val repository: PreregisterSessionRepository,
    private val deleteStandard: DeleteStandardProgramApplicationsService,
    private val deleteTraining: DeleteTrainingProgramApplicationsService,
    private val ledger: ProgramSessionApplicationRepository,
) : PurgePreregisterSessionsService {
    // 프로그램별 삭제 표식이 잠금 안에서 새 신청을 막은 뒤 박람회의 회차 원장을 지운다.
    @Transactional
    override fun execute(
        expoId: UUID,
        standardProgramIds: List<Long>,
        trainingProgramIds: List<Long>,
    ) {
        repository.lock(expoId)
        repository.purge(expoId)
        standardProgramIds.distinct().sorted().forEach(deleteStandard::execute)
        trainingProgramIds.distinct().sorted().forEach(deleteTraining::execute)
        ledger.purge(expoId)
    }
}
