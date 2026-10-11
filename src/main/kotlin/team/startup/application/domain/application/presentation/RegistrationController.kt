package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import team.startup.application.domain.application.service.RegisterStandardFieldService
import team.startup.application.domain.application.service.RegisterStandardPreService
import team.startup.application.domain.application.service.RegisterTraineeFieldService
import team.startup.application.domain.application.service.RegisterTraineePreService
import team.startup.application.domain.application.service.RegistrationCommand
import team.startup.application.domain.application.service.impl.ParticipantSessionException

data class RegistrationRequest(
    @field:NotBlank @field:Size(max = 10) val name: String,
    @field:NotBlank @field:Size(max = 15) val phoneNumber: String,
    @field:NotBlank val informationJson: String,
    val personalInformationStatus: Boolean,
    @field:Size(max = 15) val trainingId: String? = null,
    @field:jakarta.validation.constraints.Positive val sessionId: Long? = null,
)

@RestController
class RegistrationController(
    private val traineePreService: RegisterTraineePreService,
    private val standardPreService: RegisterStandardPreService,
    private val traineeFieldService: RegisterTraineeFieldService,
    private val standardFieldService: RegisterStandardFieldService,
    @Value("\${application.registration.enabled:false}") private val enabled: Boolean,
    @Value("\${application.owner.session-cookie-name:PARTICIPANT_SESSION}") private val sessionCookieName: String = "PARTICIPANT_SESSION",
) {
    @ExceptionHandler(ParticipantSessionException::class)
    fun sessionError(ex: ParticipantSessionException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("status" to 401, "code" to ex.code))

    @Operation(summary = "연수자 사전 등록")
    @PostMapping("/application/{expoId}")
    fun traineePre(
        @PathVariable expoId: String,
        @Valid @RequestBody request: RegistrationRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) key: String?,
        httpRequest: HttpServletRequest,
    ) = register(expoId, request, key, httpRequest, traineePreService::execute)

    @Operation(summary = "일반 사전 등록")
    @PostMapping("/application/pre-standard/{expoId}")
    fun standardPre(
        @PathVariable expoId: String,
        @Valid @RequestBody request: RegistrationRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) key: String?,
        httpRequest: HttpServletRequest,
    ) = register(expoId, request, key, httpRequest, standardPreService::execute)

    @Operation(summary = "연수자 현장 등록")
    @PostMapping("/application/field/{expoId}")
    fun traineeField(
        @PathVariable expoId: String,
        @Valid @RequestBody request: RegistrationRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) key: String?,
        httpRequest: HttpServletRequest,
    ) = register(expoId, request, key, httpRequest, traineeFieldService::execute)

    @Operation(summary = "일반 현장 등록")
    @PostMapping("/application/field/standard/{expoId}")
    fun standardField(
        @PathVariable expoId: String,
        @Valid @RequestBody request: RegistrationRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) key: String?,
        httpRequest: HttpServletRequest,
    ) = register(expoId, request, key, httpRequest, standardFieldService::execute)

    private fun register(
        expoId: String,
        request: RegistrationRequest,
        key: String?,
        httpRequest: HttpServletRequest,
        execute: (RegistrationCommand) -> Unit,
    ): ResponseEntity<Void> {
        if (!enabled) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "박람회 등록이 비활성화되었습니다.")
        val session =
            httpRequest.cookies
                .orEmpty()
                .filter { it.name == sessionCookieName }
                .singleOrNull()
                ?.value
        execute(RegistrationCommand(expoId, request, key, session))
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }
}
