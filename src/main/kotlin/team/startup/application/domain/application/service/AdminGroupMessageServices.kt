package team.startup.application.domain.application.service

import team.startup.application.domain.application.presentation.dto.AdminGroupMessageConfig
import team.startup.application.domain.application.presentation.dto.AdminGroupMessageJob
import team.startup.application.domain.application.presentation.dto.AdminGroupMessagePreview
import team.startup.application.domain.application.presentation.dto.CreateAdminGroupMessagePreviewRequest
import team.startup.application.domain.application.presentation.dto.ExecuteAdminGroupMessageRequest
import java.util.UUID

interface GetAdminGroupMessageConfigService {
    fun execute(
        expoId: UUID,
        authorization: String?,
    ): AdminGroupMessageConfig
}

interface CreateAdminGroupMessagePreviewService {
    fun execute(
        expoId: UUID,
        authorization: String?,
        request: CreateAdminGroupMessagePreviewRequest,
    ): AdminGroupMessagePreview
}

interface ExecuteAdminGroupMessageService {
    fun execute(
        expoId: UUID,
        previewId: UUID,
        authorization: String?,
        request: ExecuteAdminGroupMessageRequest,
    ): AdminGroupMessageJob
}

interface GetAdminGroupMessageJobService {
    fun execute(
        expoId: UUID,
        jobId: String,
        authorization: String?,
    ): AdminGroupMessageJob
}

interface RetryAdminGroupMessageService {
    fun execute(
        expoId: UUID,
        jobId: String,
        authorization: String?,
        request: ExecuteAdminGroupMessageRequest,
    ): AdminGroupMessageJob
}
