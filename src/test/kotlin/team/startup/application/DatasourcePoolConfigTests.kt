package team.startup.application

import com.zaxxer.hikari.HikariConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.env.MockEnvironment

class DatasourcePoolConfigTests {
    @Test
    fun `default datasource keeps fifteen connections and fails acquisition after three seconds`() {
        val config = config(MockEnvironment())

        assertEquals(15, config.maximumPoolSize)
        assertEquals(3000L, config.connectionTimeout)
    }

    @Test
    fun `connection acquisition timeout can be overridden`() {
        val config = config(MockEnvironment().withProperty("APPLICATION_DB_CONNECTION_TIMEOUT_MS", "5000"))

        assertEquals(15, config.maximumPoolSize)
        assertEquals(5000L, config.connectionTimeout)
    }

    private fun config(environment: MockEnvironment): HikariConfig {
        val sources = YamlPropertySourceLoader().load("application", ClassPathResource("application.yaml"))
        sources.forEach { environment.propertySources.addLast(it) }
        return Binder
            .get(environment)
            .bind("spring.datasource.hikari", Bindable.of(HikariConfig::class.java))
            .get()
    }
}
