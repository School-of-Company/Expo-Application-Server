package team.startup.application.global.client.attendance

import feign.Client
import feign.FeignException
import feign.Logger
import feign.Request
import feign.RequestInterceptor
import feign.Retryer
import feign.codec.Decoder
import feign.codec.Encoder
import org.springframework.context.annotation.Bean
import team.startup.application.domain.application.service.impl.Downstream
import team.startup.application.domain.application.service.impl.DownstreamCircuits
import team.startup.application.global.client.DownstreamFeignClient
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.TimeUnit

/** @Configuration을 붙이지 않아 토큰과 전송 설정이 다른 클라이언트에 전파되지 않는다. */
class AttendanceClientConfiguration {
    @Bean
    fun attendanceInternalToken(properties: AttendanceClientProperties): RequestInterceptor =
        RequestInterceptor { template -> template.header("X-Internal-Token", properties.internalToken) }

    @Bean
    fun attendanceTransport(circuits: DownstreamCircuits): Client = DownstreamFeignClient(Downstream.ATTENDANCE, circuits)

    @Bean
    fun attendanceOptions(): Request.Options = Request.Options(3, TimeUnit.SECONDS, 8, TimeUnit.SECONDS, false)

    @Bean
    fun attendanceRetryer(): Retryer = Retryer.NEVER_RETRY

    @Bean
    fun attendanceLogLevel(): Logger.Level = Logger.Level.NONE

    @Bean
    fun attendanceEncoder(mapper: ObjectMapper): Encoder =
        Encoder { body, _, template -> template.body(mapper.writeValueAsBytes(body), Charsets.UTF_8) }

    @Bean
    fun attendanceDecoder(mapper: ObjectMapper): Decoder =
        Decoder { response, _ ->
            // 기존 게이트웨이는 200 이외의 성공 상태도 잘못된 상태로 취급한다.
            if (response.status() != 200) throw FeignException.errorStatus("attendance", response)
            response.body()?.asInputStream()?.use { mapper.readTree(it) } ?: error("Missing Attendance response")
        }
}
