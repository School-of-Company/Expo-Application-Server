package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.entity.StandardProgramApplication
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.ApplyStandardProgramsCommand
import team.startup.application.domain.application.presentation.dto.StandardApplicationResponse
import team.startup.application.domain.application.repository.StandardProgramApplicationRepository
import team.startup.application.domain.application.service.StandardProgramApplicationService
import java.time.format.DateTimeFormatter

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

        programIds.sorted().forEach(applications::lockProgram)
        command.programs.forEach { program ->
            if (applications.isDeleted(program.id)) {
                throw ProgramApplicationConflictException("삭제된 일반 프로그램입니다.")
            }
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

    @Transactional(readOnly = true)
    override fun list(programId: Long): List<StandardApplicationResponse> =
        applications.findAllByStandardProgramIdOrderByIdAsc(programId).map { application ->
            StandardApplicationResponse(
                application.id!!,
                application.participantId,
                application.status,
                application.entryTime?.format(TIME_FORMAT),
                application.leaveTime?.format(TIME_FORMAT),
            )
        }

    @Transactional
    override fun delete(programId: Long) {
        applications.lockProgram(programId)
        applications.markDeleted(programId)
        applications.deleteAllByStandardProgramId(programId)
    }

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
