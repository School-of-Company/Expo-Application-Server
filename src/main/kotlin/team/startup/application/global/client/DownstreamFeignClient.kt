package team.startup.application.global.client

import feign.Client
import feign.Request
import feign.Response
import team.startup.application.domain.application.service.impl.DownstreamCircuits
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Feign의 인코딩·디코딩 밖에서 기존 회로 집계와 GET 연결 실패 재시도를 유지한다. */
class DownstreamFeignClient(
    private val service: String,
    private val circuits: DownstreamCircuits,
) : Client {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    override fun execute(
        request: Request,
        options: Request.Options,
    ): Response {
        val builder = HttpRequest.newBuilder(URI.create(request.url())).timeout(Duration.ofMillis(options.readTimeoutMillis().toLong()))
        request.headers().forEach { (name, values) ->
            // JDK HttpClient가 본문의 실제 길이로 생성한다.
            if (!name.equals("Content-Length", ignoreCase = true)) values.forEach { builder.header(name, it) }
        }
        builder.method(
            request.httpMethod().name,
            request.body()?.let(HttpRequest.BodyPublishers::ofByteArray) ?: HttpRequest.BodyPublishers.noBody(),
        )
        val response =
            try {
                circuits.send(service, client, builder.build(), HttpResponse.BodyHandlers.ofByteArray())
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("downstream interrupted", exception)
            }
        val headers: Map<String, Collection<String>> = response.headers().map().mapValues { (_, values) -> values }
        return Response
            .builder()
            .status(response.statusCode())
            .reason("")
            .headers(headers)
            .body(response.body())
            .request(request)
            .build()
    }
}
