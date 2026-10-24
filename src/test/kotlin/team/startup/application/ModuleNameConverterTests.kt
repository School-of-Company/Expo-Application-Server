package team.startup.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import team.startup.application.global.logging.ModuleNameConverter

class ModuleNameConverterTests {
    @Test
    fun `in-house loggers use the name after domain or global`() {
        assertEquals(
            "application",
            ModuleNameConverter.moduleOf("team.startup.application.domain.application.service.impl.PromotionOfferPolicy"),
        )
        assertEquals("security", ModuleNameConverter.moduleOf("team.startup.application.global.security.SecurityConfig"))
    }

    @Test
    fun `other loggers use the parent package`() {
        assertEquals("application", ModuleNameConverter.moduleOf("team.startup.application.ExpoApplicationServerApplication"))
        assertEquals("hikari", ModuleNameConverter.moduleOf("com.zaxxer.hikari.HikariDataSource"))
        assertEquals("ROOT", ModuleNameConverter.moduleOf("ROOT"))
    }
}
