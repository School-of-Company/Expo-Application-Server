package team.startup.application.domain.application.presentation

import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
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

data class RegistrationRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val phoneNumber: String,
    @field:NotBlank val informationJson: String,
    val personalInformationStatus: Boolean,
    val trainingId: String? = null,
)

@RestController
class RegistrationController(
    private val traineePreService: RegisterTraineePreService,
    private val standardPreService: RegisterStandardPreService,
    private val traineeFieldService: RegisterTraineeFieldService,
    private val standardFieldService: RegisterStandardFieldService,
    @Value("\${application.registration.enabled:false}") private val enabled: Boolean,
) {
    @Operation(summary = "연수자 사전 등록")
    @PostMapping("/application/{expoId}")
    fun traineePre(
        @PathVariable expoId: String,
        @Valid @RequestBody request: RegistrationRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) key: String?,
    ) = register(expoId, request, key, traineePreService::execute)

    @Operation(summary = "일반 사전 등록")
    @PostMapping("/application/pre-standard/{expoId}")
    fun standardPre(
        @PathVariable expoId: String,
        @Valid @RequestBody request: RegistrationRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) key: String?,
    ) = register(expoId, request, key, standardPreService::execute)

    @Operation(summary = "연수자 현장 등록")
    @PostMapping("/application/field/{expoId}")
    fun traineeField(
        @PathVariable expoId: String,
        @Valid @RequestBody request: RegistrationRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) key: String?,
    ) = register(expoId, request, key, traineeFieldService::execute)

    @Operation(summary = "일반 현장 등록")
    @PostMapping("/application/field/standard/{expoId}")
    fun standardField(
        @PathVariable expoId: String,
        @Valid @RequestBody request: RegistrationRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) key: String?,
    ) = register(expoId, request, key, standardFieldService::execute)

    private fun register(
        expoId: String,
        request: RegistrationRequest,
        key: String?,
        execute: (RegistrationCommand) -> Unit,
    ): ResponseEntity<Void> {
        if (!enabled) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "박람회 등록이 비활성화되었습니다.")
        execute(RegistrationCommand(expoId, request, key))
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }
}
