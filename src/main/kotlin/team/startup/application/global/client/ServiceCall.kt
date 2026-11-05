package team.startup.application.global.client

import feign.FeignException
import feign.codec.DecodeException
import tools.jackson.databind.JsonNode

fun callService(
    invalidResponse: () -> Nothing,
    failedResponse: (Int) -> Nothing,
    call: () -> JsonNode,
): JsonNode =
    try {
        call()
    } catch (_: DecodeException) {
        invalidResponse()
    } catch (exception: FeignException) {
        failedResponse(exception.status())
    } catch (exception: Exception) {
        if (exception is InterruptedException) Thread.currentThread().interrupt()
        failedResponse(-1)
    }
