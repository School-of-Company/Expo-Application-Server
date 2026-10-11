package team.startup.application.global.swagger

import io.swagger.v3.core.util.Json
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty(prefix = "swagger", name = ["enabled"], havingValue = "true")
class OpenApiConfig(
    @Value("\${swagger.include-internal:false}") private val includeInternal: Boolean,
) {
    @Bean
    fun applicationOpenApi(): OpenAPI =
        OpenAPI().servers(listOf(Server().url("/"))).info(
            Info()
                .title("Expo Application Server API")
                .version("0.1.0")
                .description("박람회 등록·프로그램 신청 API입니다. 내부 API는 서비스 간 X-Internal-Token 인증을 사용합니다."),
        )

    @Bean
    fun applicationOpenApiCustomizer(): OpenApiCustomizer =
        OpenApiCustomizer { api ->
            val components = api.components ?: Components().also { api.components = it }
            if (includeInternal) {
                components.addSecuritySchemes(
                    "internalToken",
                    SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .`in`(SecurityScheme.In.HEADER)
                        .name("X-Internal-Token"),
                )
                api.paths
                    ?.filterKeys { it.startsWith("/internal/") }
                    ?.values
                    ?.forEach { path ->
                        path.readOperations().forEach { it.addSecurityItem(SecurityRequirement().addList("internalToken")) }
                    }
            } else {
                api.paths?.keys?.removeIf { it.startsWith("/internal/") }
                val schemas = components.schemas ?: return@OpenApiCustomizer
                val reachable = mutableSetOf<String>()
                val pending = ArrayDeque(referencedSchemas(Json.mapper().writeValueAsString(api.paths)))
                while (pending.isNotEmpty()) {
                    val name = pending.removeFirst()
                    if (reachable.add(name)) schemas[name]?.let { pending += referencedSchemas(Json.mapper().writeValueAsString(it)) }
                }
                schemas.keys.retainAll(reachable)
            }
        }

    private fun referencedSchemas(json: String): List<String> = SCHEMA_REF.findAll(json).map { it.groupValues[1] }.toList()

    private companion object {
        val SCHEMA_REF = Regex("#/components/schemas/([^\"]+)\"")
    }
}
