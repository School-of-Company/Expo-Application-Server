package team.startup.application.domain.application.service.impl

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.presentation.dto.AdminGroupMessageConfig
import team.startup.application.domain.application.presentation.dto.AdminGroupMessageJob
import team.startup.application.domain.application.presentation.dto.AdminGroupMessagePreview
import team.startup.application.domain.application.presentation.dto.CreateAdminGroupMessagePreviewRequest
import team.startup.application.domain.application.presentation.dto.ExecuteAdminGroupMessageRequest
import team.startup.application.domain.application.service.CreateAdminGroupMessagePreviewService
import team.startup.application.domain.application.service.ExecuteAdminGroupMessageService
import team.startup.application.domain.application.service.GetAdminGroupMessageConfigService
import team.startup.application.domain.application.service.GetAdminGroupMessageJobService
import team.startup.application.domain.application.service.RetryAdminGroupMessageService
import java.util.UUID

@Service
class GetAdminGroupMessageConfigServiceImpl(
    private val gateway: AdminGroupMessageGateway,
) : GetAdminGroupMessageConfigService {
    override fun execute(
        expoId: UUID,
        authorization: String?,
    ): AdminGroupMessageConfig {
        gateway.authorize(expoId, authorization)
        return gateway.config()
    }
}

@Service
class CreateAdminGroupMessagePreviewServiceImpl(
    private val gateway: AdminGroupMessageGateway,
    private val resolver: AdminGroupMessageTargetResolver,
) : CreateAdminGroupMessagePreviewService {
    override fun execute(
        expoId: UUID,
        authorization: String?,
        request: CreateAdminGroupMessagePreviewRequest,
    ): AdminGroupMessagePreview {
        val requesterId = gateway.authorize(expoId, authorization)
        val resolved = resolver.resolve(expoId, request.source)
        return gateway.preview(
            requesterId,
            expoId,
            request.requestId,
            request.source,
            resolved.fingerprint,
            resolved.applicationCount,
            resolved.targets,
        )
    }
}

@Service
class ExecuteAdminGroupMessageServiceImpl(
    private val gateway: AdminGroupMessageGateway,
    private val resolver: AdminGroupMessageTargetResolver,
) : ExecuteAdminGroupMessageService {
    override fun execute(
        expoId: UUID,
        previewId: UUID,
        authorization: String?,
        request: ExecuteAdminGroupMessageRequest,
    ): AdminGroupMessageJob {
        val requesterId = gateway.authorize(expoId, authorization)
        val resolved = resolver.resolve(expoId, request.source)
        ensureUnchanged(request.targetFingerprint, resolved.fingerprint)
        return gateway.execute(previewId, requesterId, expoId, resolved.fingerprint, request.idempotencyKey)
    }
}

@Service
class GetAdminGroupMessageJobServiceImpl(
    private val gateway: AdminGroupMessageGateway,
) : GetAdminGroupMessageJobService {
    override fun execute(
        expoId: UUID,
        jobId: String,
        authorization: String?,
    ): AdminGroupMessageJob = gateway.status(jobId, gateway.authorize(expoId, authorization), expoId)
}

@Service
class RetryAdminGroupMessageServiceImpl(
    private val gateway: AdminGroupMessageGateway,
    private val resolver: AdminGroupMessageTargetResolver,
) : RetryAdminGroupMessageService {
    override fun execute(
        expoId: UUID,
        jobId: String,
        authorization: String?,
        request: ExecuteAdminGroupMessageRequest,
    ): AdminGroupMessageJob {
        val requesterId = gateway.authorize(expoId, authorization)
        val resolved = resolver.resolve(expoId, request.source)
        ensureUnchanged(request.targetFingerprint, resolved.fingerprint)
        return gateway.retry(jobId, requesterId, expoId, resolved.fingerprint, request.idempotencyKey)
    }
}

private fun ensureUnchanged(
    previewFingerprint: String,
    currentFingerprint: String,
) {
    if (previewFingerprint != currentFingerprint) {
        throw ResponseStatusException(HttpStatus.CONFLICT, "미리보기 이후 권한 또는 발송 대상이 변경되었습니다. 다시 미리보기해 주세요.")
    }
}
