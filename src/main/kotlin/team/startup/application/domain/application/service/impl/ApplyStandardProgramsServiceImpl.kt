package team.startup.application.domain.application.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.startup.application.domain.application.entity.StandardProgramApplication
import team.startup.application.domain.application.exception.ProgramApplicationConflictException
import team.startup.application.domain.application.presentation.dto.ApplyStandardProgramsCommand
import team.startup.application.domain.application.presentation.dto.ProgramSessionType
import team.startup.application.domain.application.repository.ProgramSessionApplicationRepository
import team.startup.application.domain.application.repository.ProgramWaitlistRepository
import team.startup.application.domain.application.repository.StandardProgramApplicationRepository
import team.startup.application.domain.application.service.ApplyStandardProgramsService

@Service
class ApplyStandardProgramsServiceImpl(
    private val applications: StandardProgramApplicationRepository,
    private val gateway: RegistrationGateway,
    private val ledger: ProgramSessionApplicationRepository,
    private val repository: ProgramWaitlistRepository,
) : ApplyStandardProgramsService {
    @Transactional
    override fun execute(command: ApplyStandardProgramsCommand) {
        val programIds = command.programs.map { it.id }
        require(command.participant.id > 0 && command.participant.expoId.isNotBlank())
        require(programIds.isNotEmpty() && programIds.all { it > 0 } && programIds.distinct().size == programIds.size)
        require(command.programs.all { it.expoId == command.participant.expoId })

        val oldIds = applications.findAllByParticipantId(command.participant.id).map { it.standardProgramId }.toSet()
        val definitions = gateway.programDefinitions(command.participant.expoId, ProgramSessionType.STANDARD, programIds)
        val known =
            if (definitions.isEmpty()) {
                emptyMap()
            } else {
                ledger.knownGroups(
                    command.participant.expoId,
                    ProgramSessionType.STANDARD,
                    command.participant.id,
                )
            }
        val existingDefinitions =
            gateway.programDefinitions(
                command.participant.expoId,
                ProgramSessionType.STANDARD,
                oldIds.filter { it !in known },
                existing = true,
            )
        if (definitions.any { it.policy != null }) {
            throw ProgramApplicationConflictException("정책이 설정된 프로그램은 참가자와 폼 답변을 포함하는 신청이 필요합니다.")
        }
        ledger.lockParticipant(command.participant.id)
        if (definitions.isNotEmpty() &&
            applications.findAllByParticipantId(command.participant.id).map { it.standardProgramId }.toSet() != oldIds
        ) {
            throw ProgramApplicationConflictException("참가자 신청이 변경되었습니다. 재시도해 주세요.")
        }
        ledger.lockGroups(definitions + existingDefinitions)
        programIds.sorted().forEach(applications::lockProgram)
        definitions.forEach(repository::observe)
        ledger.ensureLegacyGroups(definitions, existingDefinitions, command.participant.id)
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
}
