package team.startup.application.domain.application.presentation.dto

import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.ValueDeserializer

class AdmissionCapacityDeserializer : ValueDeserializer<Int>() {
    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext,
    ): Int {
        val value = context.readTree(parser)
        if (!value.isIntegralNumber || !value.canConvertToInt() || value.asInt() < 0) {
            return context.reportInputMismatch(Int::class.java, "Admission capacity must be a nonnegative integer")
        }
        return value.asInt()
    }

    override fun getNullValue(context: DeserializationContext): Int =
        context.reportInputMismatch(Int::class.java, "Admission capacity cannot be null")
}
