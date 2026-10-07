package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.repository.TrainingOperationRepository
import team.startup.application.domain.application.repository.TrainingProgramApplicationRepository
import team.startup.application.domain.application.service.DeleteTrainingProgramApplicationsService

@Service
class DeleteTrainingProgramApplicationsServiceImpl(
    private val applications: TrainingProgramApplicationRepository,
    private val operations: TrainingOperationRepository,
) : DeleteTrainingProgramApplicationsService {
    // 신청과 같은 프로그램 잠금 안에서 삭제 표시를 먼저 남겨, 잠금을 기다리던 신청이 삭제 뒤에 행을 남기지 못하게 한다.
    @Transactional
    override fun execute(programId: Long) {
        applications.lockTrainingProgram(programId)
        applications.markDeleted(programId)
        operations.incrementVersionsForProgram(programId)
        applications.deleteAllByTrainingProgramId(programId)
    }
}
