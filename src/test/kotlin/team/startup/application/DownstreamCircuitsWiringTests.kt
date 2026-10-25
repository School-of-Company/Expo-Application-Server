package team.startup.application

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.ApplicationContext
import org.springframework.test.util.AopTestUtils
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import team.startup.application.domain.application.service.impl.AdminAttendanceGateway
import team.startup.application.domain.application.service.impl.AdminCancellationParticipantGateway
import team.startup.application.domain.application.service.impl.AdminExpoGateway
import team.startup.application.domain.application.service.impl.AdminGroupMessageGateway
import team.startup.application.domain.application.service.impl.AdminParticipantGateway
import team.startup.application.domain.application.service.impl.AttendanceCancellationAdapter
import team.startup.application.domain.application.service.impl.AttendanceCancellationStatusGateway
import team.startup.application.domain.application.service.impl.DownstreamCircuits
import team.startup.application.domain.application.service.impl.FieldCancellationGateway
import team.startup.application.domain.application.service.impl.LegacyTraineeAttributionGateway
import team.startup.application.domain.application.service.impl.OwnerCancellationDeadlineGateway
import team.startup.application.domain.application.service.impl.OwnerCancellationGateway
import team.startup.application.domain.application.service.impl.PreregisterSessionGateway
import team.startup.application.domain.application.service.impl.ProgramPromotionGateway
import team.startup.application.domain.application.service.impl.RegistrationGateway

/** 게이트웨이가 기본값 대신 컨텍스트의 같은 DownstreamCircuits 빈을 받아야 서비스별 회로가 게이트웨이 사이에서 공유된다. */
@SpringBootTest(properties = ["eureka.client.enabled=false", "management.health.redis.enabled=false"])
@Testcontainers
class DownstreamCircuitsWiringTests {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `모든 게이트웨이는 컨텍스트의 같은 DownstreamCircuits 빈을 쓴다`() {
        val circuits = context.getBean(DownstreamCircuits::class.java)
        listOf(
            RegistrationGateway::class.java,
            ProgramPromotionGateway::class.java,
            AdminGroupMessageGateway::class.java,
            FieldCancellationGateway::class.java,
            OwnerCancellationGateway::class.java,
            OwnerCancellationDeadlineGateway::class.java,
            PreregisterSessionGateway::class.java,
            AttendanceCancellationAdapter::class.java,
            AttendanceCancellationStatusGateway::class.java,
            AdminAttendanceGateway::class.java,
            AdminExpoGateway::class.java,
            AdminParticipantGateway::class.java,
            AdminCancellationParticipantGateway::class.java,
            LegacyTraineeAttributionGateway::class.java,
        ).forEach { type ->
            val target = AopTestUtils.getUltimateTargetObject<Any>(context.getBean(type))
            assertSame(circuits, circuitsOf(target), type.simpleName)
        }
    }

    // AdminSourceHttp를 거치는 게이트웨이는 http 필드 안에 circuits가 있다
    private fun circuitsOf(target: Any): Any? = read(target, "circuits") ?: read(target, "http")?.let { read(it, "circuits") }

    private fun read(
        target: Any,
        name: String,
    ): Any? =
        generateSequence<Class<*>>(target.javaClass) { it.superclass }
            .mapNotNull { type -> type.declaredFields.firstOrNull { it.name == name } }
            .firstOrNull()
            ?.let {
                it.isAccessible = true
                it.get(target)
            }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
