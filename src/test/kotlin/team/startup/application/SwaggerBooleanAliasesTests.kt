package team.startup.application

import org.springframework.boot.test.context.SpringBootTest

// Springdoc's Kotlin expression requires a literal serving flag; its independent override must not bypass pruning.
@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "management.health.redis.enabled=false",
        "SWAGGER_ENABLED=on",
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true",
        "application.program-sessions.enabled=true",
        "application.registration.enabled=true",
        "application.registration.expo-internal-token=expo-token",
        "application.registration.form-internal-token=form-token",
        "application.registration.user-internal-token=user-token",
    ],
)
class SwaggerOnEnabledTests : SwaggerEnabledTests()

@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "management.health.redis.enabled=false",
        "SWAGGER_ENABLED=yes",
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true",
        "application.program-sessions.enabled=true",
        "application.registration.enabled=true",
        "application.registration.expo-internal-token=expo-token",
        "application.registration.form-internal-token=form-token",
        "application.registration.user-internal-token=user-token",
    ],
)
class SwaggerYesEnabledTests : SwaggerEnabledTests()

@SpringBootTest(
    properties = [
        "eureka.client.enabled=false",
        "management.health.redis.enabled=false",
        "SWAGGER_ENABLED=1",
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true",
        "application.program-sessions.enabled=true",
        "application.registration.enabled=true",
        "application.registration.expo-internal-token=expo-token",
        "application.registration.form-internal-token=form-token",
        "application.registration.user-internal-token=user-token",
    ],
)
class SwaggerOneEnabledTests : SwaggerEnabledTests()
