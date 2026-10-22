package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.FieldVisitCommand
import team.startup.application.domain.application.presentation.dto.FieldVisitReceipt

interface RegisterFieldVisitService {
    fun execute(command: FieldVisitCommand): FieldVisitReceipt
}
