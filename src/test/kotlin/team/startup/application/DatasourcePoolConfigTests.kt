package team.startup.application

import com.zaxxer.hikari.HikariConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource

class DatasourcePoolConfigTests {
    @Test
    fun `default datasource keeps fifteen connections and fails acquisition after three seconds`() {
        val sources = YamlPropertySourceLoader().load("application", ClassPathResource("application.yaml"))
        val config =
            Binder(ConfigurationPropertySources.from(sources))
                .bind("spring.datasource.hikari", Bindable.of(HikariConfig::class.java))
                .get()

        assertEquals(15, config.maximumPoolSize)
        assertEquals(3000L, config.connectionTimeout)
    }
}
