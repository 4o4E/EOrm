package top.e404.eorm.json

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * JDBC 参数包装，用于标识已经序列化后的 JSON 值。
 */
data class JsonDbValue(val json: String) {
    override fun toString(): String = json
}

/**
 * 默认 JSON 编解码器。
 */
object JsonCodec {
    private val mapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    fun toJsonValue(value: Any?): JsonDbValue? {
        if (value == null) return null
        val json = if (value is String) value else mapper.writeValueAsString(value)
        return JsonDbValue(json)
    }

    fun fromJsonValue(value: Any, targetType: Class<*>): Any {
        val json = value.toString()
        if (targetType == String::class.java) return json
        return mapper.readValue(json, targetType)
    }
}
