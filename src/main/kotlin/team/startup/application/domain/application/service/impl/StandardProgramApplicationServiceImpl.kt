package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.entity.StandardProgramApplication
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.ApplyStandardProgramsCommand
import team.startup.application.domain.application.repository.StandardProgramApplicationRepository
import team.startup.application.domain.application.service.StandardProgramApplicationService

@Service
class StandardProgramApplicationServiceImpl(
    private val applications: StandardProgramApplicationRepository,
) : StandardProgramApplicationService {
    @Transactional
    override fun execute(command: ApplyStandardProgramsCommand) {
        val programIds = command.programs.map { it.id }
        require(command.participant.id > 0 && command.participant.expoId.isNotBlank())
        require(programIds.isNotEmpty() && programIds.all { it > 0 } && programIds.distinct().size == programIds.size)
        require(command.programs.all { it.expoId == command.participant.expoId })

        programIds.sorted().forEach(applications::lockStandardProgram)
        command.programs.forEach { program ->
            if (applications.existsByParticipantIdAndStandardProgramId(command.participant.id, program.id)) {
                throw ProgramApplicationConflictException("이미 신청한 일반 프로그램입니다.")
            }
        }
        applications.saveAllAndFlush(
            command.programs.map { program ->
                StandardProgramApplication(participantId = command.participant.id, standardProgramId = program.id)
            },
        )
    }
}
