import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

val baseUrl = requireNotNull(System.getenv("GROUP_MESSAGE_NOTIFICATION_CONTRACT_URL")) {
    "GROUP_MESSAGE_NOTIFICATION_CONTRACT_URL is required"
}.removeSuffix("/")
val token = requireNotNull(System.getenv("GROUP_MESSAGE_NOTIFICATION_CONTRACT_TOKEN")) {
    "GROUP_MESSAGE_NOTIFICATION_CONTRACT_TOKEN is required"
}
val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

fun request(method: String, path: String, body: String? = null): HttpResponse<String> {
    val builder =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(10))
            .header("X-Internal-Token", token)
    val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
    if (body != null) builder.header("Content-Type", "application/json")
    return client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
}

fun expect(response: HttpResponse<String>, status: Int, vararg fragments: String): String {
    check(response.statusCode() == status) { "expected $status, got ${response.statusCode()}: ${response.body()}" }
    fragments.forEach { fragment -> check(response.body().contains(fragment)) { "missing $fragment: ${response.body()}" } }
    return response.body()
}

fun field(json: String, name: String): String =
    requireNotNull(Regex("\\\"$name\\\":\\\"([^\\\"]+)\\\"").find(json)?.groupValues?.get(1)) {
        "missing $name: $json"
    }

val expoId = "0190abcd-0000-7000-8000-000000000001"
val applicationId = "0190abcd-0000-7000-8000-000000000011"
val fingerprint = "b".repeat(64)
val requestId = UUID.randomUUID()

expect(request("GET", "/internal/admin-group-messages/config"), 200, "\"SMS\":{\"enabled\":true", "\"smsFallbackDefault\":false")

val preview =
    expect(
        request(
            "POST",
            "/internal/admin-group-messages/previews",
            """{"requesterUserId":7,"expoId":"$expoId","requestId":"$requestId","targetFingerprint":"$fingerprint","applicationCount":1,"channel":"SMS","fallbackToSms":false,"targets":[{"targetId":"$applicationId","applicationId":"$applicationId","participantId":1,"programType":"STANDARD","recipientPhone":"01012345678","content":"계약 검증"}]}""",
        ),
        201,
        "\"applicationCount\":1",
        "\"recipientCount\":1",
        "\"messageCount\":1",
        "\"missingCount\":0",
    )
val previewId = field(preview, "previewId")
val idempotencyKey = UUID.randomUUID()
val executeBody =
    """{"requesterUserId":7,"expoId":"$expoId","targetFingerprint":"$fingerprint","idempotencyKey":"$idempotencyKey"}"""
val created = expect(request("POST", "/internal/admin-group-messages/previews/$previewId/jobs", executeBody), 202, "\"previewId\":\"$previewId\"")
val jobId = field(created, "jobId")
check(jobId.matches(Regex("[a-f0-9]{64}"))) { "invalid jobId: $jobId" }
val duplicate = expect(request("POST", "/internal/admin-group-messages/previews/$previewId/jobs", executeBody), 202)
check(field(duplicate, "jobId") == jobId) { "idempotent execute returned a different job" }

var status = ""
for (attempt in 1..600) {
    status = expect(request("GET", "/internal/admin-group-messages/jobs/$jobId?requesterUserId=7&expoId=$expoId"), 200)
    if (status.contains("\"status\":\"COMPLETED\"") || status.contains("\"status\":\"COMPLETED_WITH_FAILURES\"") || status.contains("\"status\":\"ATTENTION\"")) break
    Thread.sleep(100)
}
check(status.contains("\"status\":\"COMPLETED\"")) { "fake successful provider did not complete: $status" }
check(status.contains("\"delivered\":1")) { "delivery result missing: $status" }

val retryBody =
    """{"requesterUserId":7,"expoId":"$expoId","targetFingerprint":"$fingerprint","idempotencyKey":"${UUID.randomUUID()}"}"""
expect(request("POST", "/internal/admin-group-messages/jobs/$jobId/retries", retryBody), 202, "\"jobId\":\"$jobId\"", "\"delivered\":1")

// 문자 대체는 KAKAO에서만 받는다. 카카오 템플릿이 없는 환경에서도 스키마 통과 여부는 400과 422로 구분된다.
fun fallbackPreview(channel: String): HttpResponse<String> {
    val template = if (channel == "KAKAO") ""","templateId":"contract-unconfigured"""" else ""
    return request(
        "POST",
        "/internal/admin-group-messages/previews",
        """{"requesterUserId":7,"expoId":"$expoId","requestId":"${UUID.randomUUID()}","targetFingerprint":"$fingerprint","applicationCount":1,"channel":"$channel"$template,"fallbackToSms":true,"targets":[{"targetId":"$applicationId","applicationId":"$applicationId","participantId":1,"programType":"STANDARD","recipientPhone":"01012345678","content":"계약 검증","templateVariables":{"expoName":"계약 박람회","status":"확정"}}]}""",
    )
}
expect(fallbackPreview("SMS"), 400, "fallbackToSms")
expect(fallbackPreview("KAKAO"), 422, "Kakao template is not configured")

println("PASS config preview execute duplicate status retry kakao-fallback jobId=$jobId previewId=$previewId")
