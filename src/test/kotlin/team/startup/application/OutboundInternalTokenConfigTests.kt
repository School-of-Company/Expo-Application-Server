package team.startup.application

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import team.startup.application.domain.application.service.impl.AdminGroupMessageGateway
import team.startup.application.domain.application.service.impl.RegistrationGateway
import tools.jackson.databind.ObjectMapper

class OutboundInternalTokenConfigTests {
    // application.yaml을 그대로 읽어 APPLICATION_INTERNAL_TOKEN이 송신 토큰으로 대체되지 않는지 확인한다.
    private val runner =
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withBean(ObjectMapper::class.java, { ObjectMapper() })
            .withUserConfiguration(RegistrationGateway::class.java, AdminGroupMessageGateway::class.java)
            .withPropertyValues(
                "APPLICATION_INTERNAL_TOKEN=$SELF_TOKEN",
                "APPLICATION_REGISTRATION_ENABLED=false",
                // CI의 local 프로필이 읽는 .env 값과 무관하게 누락 상태에서 시작한다.
                *listOf("REGISTRATION_EXPO", "REGISTRATION_FORM", "REGISTRATION_USER")
                    .map { "APPLICATION_${it}_INTERNAL_TOKEN=" }
                    .toTypedArray(),
                *listOf("EXPO", "USER", "NOTIFICATION")
                    .flatMap {
                        listOf(
                            "APPLICATION_ADMIN_GROUP_MESSAGE_${it}_URL=",
                            "APPLICATION_ADMIN_GROUP_MESSAGE_${it}_INTERNAL_TOKEN=",
                        )
                    }.toTypedArray(),
            )

    @Test
    fun `disabled registration and unconfigured admin group messaging start without target tokens`() {
        runner.run { assertNull(it.startupFailure) }
    }

    @Test
    fun `enabled registration fails at startup for each missing target token instead of using the self token`() {
        val tokens =
            mapOf(
                "EXPO" to "APPLICATION_REGISTRATION_EXPO_INTERNAL_TOKEN=expo-token",
                "FORM" to "APPLICATION_REGISTRATION_FORM_INTERNAL_TOKEN=form-token",
                "USER" to "APPLICATION_REGISTRATION_USER_INTERNAL_TOKEN=user-token",
            )
        tokens.keys.forEach { missing ->
            runner
                .withPropertyValues("APPLICATION_REGISTRATION_ENABLED=true", *tokens.filterKeys { it != missing }.values.toTypedArray())
                .run { assertStartupFailure(it.startupFailure, "APPLICATION_REGISTRATION_${missing}_INTERNAL_TOKEN") }
        }
        runner
            .withPropertyValues("APPLICATION_REGISTRATION_ENABLED=true", *tokens.values.toTypedArray())
            .run { assertNull(it.startupFailure) }
    }

    @Test
    fun `admin group messaging requires the token of every configured target only`() {
        val targets = listOf("EXPO", "USER", "NOTIFICATION")
        targets.forEach { missing ->
            runner
                .withPropertyValues(
                    *targets.map { "APPLICATION_ADMIN_GROUP_MESSAGE_${it}_URL=http://127.0.0.1:1" }.toTypedArray(),
                    *targets
                        .filter { it != missing }
                        .map { "APPLICATION_ADMIN_GROUP_MESSAGE_${it}_INTERNAL_TOKEN=$it-token" }
                        .toTypedArray(),
                ).run { assertStartupFailure(it.startupFailure, "APPLICATION_ADMIN_GROUP_MESSAGE_${missing}_INTERNAL_TOKEN") }
        }
        runner
            .withPropertyValues(
                "APPLICATION_ADMIN_GROUP_MESSAGE_NOTIFICATION_URL=http://127.0.0.1:1",
                "APPLICATION_ADMIN_GROUP_MESSAGE_NOTIFICATION_INTERNAL_TOKEN=notification-token",
            ).run { assertNull(it.startupFailure) }
    }

    private fun assertStartupFailure(
        failure: Throwable?,
        setting: String,
    ) {
        assertNotNull(failure)
        val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue(messages.any { it.contains(setting) }, messages.toString())
        assertFalse(messages.any { message -> SECRETS.any(message::contains) }, messages.toString())
    }

    private companion object {
        const val SELF_TOKEN = "application-self-token"
        val SECRETS = listOf(SELF_TOKEN, "expo-token", "form-token", "user-token", "EXPO-token", "USER-token", "NOTIFICATION-token")
    }
}
