package team.startup.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import jakarta.servlet.AsyncEvent
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.mock.web.MockAsyncContext
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import team.startup.application.global.logging.HttpAccessLogConfig
import team.startup.application.global.logging.HttpAccessLogFilter
import team.startup.application.global.logging.ModuleNameConverter
import java.io.IOException
import org.slf4j.LoggerFactory as Slf4jLoggerFactory

class HttpAccessLogFilterTests {
    private val logger = (Slf4jLoggerFactory.getILoggerFactory() as LoggerContext).getLogger(HttpAccessLogFilter.LOGGER_NAME)
    private val appender = ListAppender<ILoggingEvent>().apply { start() }
    private val filter = HttpAccessLogFilter().also { logger.addAppender(appender) }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
    }

    @Test
    fun `logs once with the matched route template and status under module HttpAccess`() {
        val mockMvc = MockMvcBuilders.standaloneSetup(ThingController()).addFilters<StandaloneMockMvcBuilder>(filter).build()
        mockMvc.perform(get("/things/42?phone=01012345678")).andExpect(status().isOk)

        val event = appender.list.single()
        assertEquals(Level.INFO, event.level)
        assertTrue(Regex("""GET /things/\{id} status: 200 \(\d+ms\)""").matches(event.formattedMessage), event.formattedMessage)
        assertEquals("HttpAccess", ModuleNameConverter.moduleOf(event.loggerName))
    }

    @Test
    fun `level follows the status and the raw path drops query and path parameters`() {
        run(status = 404, uri = "/application/unknown;jsessionid=abc", query = "phone=01012345678")
        run(status = 503, uri = "/application/x")
        run(status = 201, uri = "/application/y")

        assertEquals(listOf(Level.WARN, Level.ERROR, Level.INFO), appender.list.map { it.level })
        assertTrue(appender.list[0].formattedMessage.startsWith("GET /application/unknown status: 404 ("))
        assertTrue(appender.list.none { it.formattedMessage.contains("01012345678") || it.formattedMessage.contains("jsessionid") })
    }

    @Test
    fun `raw path is truncated to 200 characters`() {
        run(status = 404, uri = "/" + "a".repeat(300))

        val path =
            appender.list
                .single()
                .formattedMessage
                .split(' ')[1]
        assertEquals(200, path.length)
    }

    @Test
    fun `security rejections without a handler are logged with the raw path`() {
        run(status = 401, uri = "/internal/program-applications")

        val event = appender.list.single()
        assertEquals(Level.WARN, event.level)
        assertTrue(event.formattedMessage.startsWith("GET /internal/program-applications status: 401 ("))
    }

    @Test
    fun `actuator and api docs are skipped`() {
        listOf(
            "/actuator/health",
            "/actuator/prometheus",
            "/application/v3/api-docs",
            "/application/swagger-ui/index.html",
            "/application/swagger-ui.html",
        ).forEach { run(status = 200, uri = it) }

        assertEquals(emptyList<ILoggingEvent>(), appender.list)
    }

    @Test
    fun `unhandled exception is logged as 500 and rethrown`() {
        val request = MockHttpServletRequest("POST", "/application/z")
        val chain = FilterChain { _, _ -> throw IllegalStateException("boom") }

        assertThrows(IllegalStateException::class.java) { filter.doFilter(request, MockHttpServletResponse(), chain) }
        assertEquals(Level.ERROR, appender.list.single().level)
        assertTrue(
            appender.list
                .single()
                .formattedMessage
                .startsWith("POST /application/z status: 500 ("),
        )
    }

    @Test
    fun `client abort is logged once as 499 at ERROR`() {
        val request = MockHttpServletRequest("GET", "/application/z")
        val chain = FilterChain { _, _ -> throw IOException("Broken pipe") }

        assertThrows(IOException::class.java) { filter.doFilter(request, MockHttpServletResponse(), chain) }
        assertEquals(Level.ERROR, appender.list.single().level)
        assertTrue(
            appender.list
                .single()
                .formattedMessage
                .startsWith("GET /application/z status: 499 ("),
        )
    }

    @Test
    fun `async request is logged when it completes, not when the first dispatch returns`() {
        val (request, response) = startAsync()
        assertEquals(emptyList<ILoggingEvent>(), appender.list)

        (request.asyncContext as MockAsyncContext).complete()

        assertTrue(
            appender.list
                .single()
                .formattedMessage
                .startsWith("GET /application/admin/expos/{expoId}/events status: 200 ("),
        )
        assertEquals(200, response.status)
    }

    @Test
    fun `async client disconnect is logged once as 499`() {
        val (request) = startAsync()
        val context = request.asyncContext as MockAsyncContext

        context.listeners.toList().forEach { it.onError(AsyncEvent(context, IOException("Broken pipe"))) }
        context.complete()

        val event = appender.list.single()
        assertEquals(Level.ERROR, event.level)
        assertTrue(event.formattedMessage.contains(" status: 499 ("))
    }

    @Test
    fun `sse emitter keeps streaming through the filter`() {
        val controller = ThingController()
        val mockMvc = MockMvcBuilders.standaloneSetup(controller).addFilters<StandaloneMockMvcBuilder>(filter).build()

        val result = mockMvc.perform(get("/things/events")).andExpect(request().asyncStarted()).andReturn()
        controller.emitter.send(SseEmitter.event().name("changed").data("1"))
        assertEquals(emptyList<ILoggingEvent>(), appender.list)
        assertTrue(result.response.contentAsString.contains("event:changed"))

        controller.emitter.complete()
        (result.request.asyncContext as MockAsyncContext).complete()
        assertTrue(
            appender.list
                .single()
                .formattedMessage
                .startsWith("GET /things/events status: 200 ("),
        )
    }

    @Test
    fun `filter is registered by default and disabled by property or environment variable`() {
        val runner = ApplicationContextRunner().withUserConfiguration(HttpAccessLogConfig::class.java)

        runner.run { assertEquals(1, it.getBeansOfType(FilterRegistrationBean::class.java).size) }
        runner.withPropertyValues("logging.http-access.enabled=false").run {
            assertTrue(it.getBeansOfType(FilterRegistrationBean::class.java).isEmpty())
        }
        runner
            .withInitializer {
                it.environment.propertySources.addFirst(
                    SystemEnvironmentPropertySource("test-env", mapOf<String, Any>("LOGGING_HTTP_ACCESS_ENABLED" to "false")),
                )
            }.run { assertFalse(it.containsBean("httpAccessLogFilter")) }
    }

    private fun run(
        status: Int,
        uri: String,
        query: String? = null,
    ) {
        val request = MockHttpServletRequest("GET", uri).apply { queryString = query }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, MockFilterChain().also { response.status = status })
    }

    private fun startAsync(): Pair<MockHttpServletRequest, MockHttpServletResponse> {
        val request = MockHttpServletRequest("GET", "/application/admin/expos/1/events").apply { isAsyncSupported = true }
        val response = MockHttpServletResponse()
        val chain =
            FilterChain { req, _ ->
                req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/application/admin/expos/{expoId}/events")
                req.startAsync()
            }
        filter.doFilter(request, response, chain)
        return request to response
    }

    @RestController
    class ThingController {
        lateinit var emitter: SseEmitter

        @GetMapping("/things/{id}")
        fun thing(
            @PathVariable id: Long,
        ) = mapOf("id" to id)

        @GetMapping("/things/events")
        fun events(): SseEmitter = SseEmitter(0L).also { emitter = it }
    }
}
