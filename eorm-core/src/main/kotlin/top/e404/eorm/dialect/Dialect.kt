package top.e404.eorm.dialect

import top.e404.eorm.generator.IdStrategy
import java.text.SimpleDateFormat
import java.util.Date
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

interface SqlDialect {
    fun wrapName(name: String): String
    fun getSqlType(type: Class<*>, length: Int): String
    fun getPrimaryKeyDefinition(strategy: IdStrategy): String
    fun valueToSql(value: Any?): String
    fun buildPaginationSql(sql: String, offset: Long, limit: Long): String
}

abstract class BaseDialect : SqlDialect {
    override fun valueToSql(value: Any?): String {
        return when (value) {
            null -> "NULL"
            is Number, is Boolean -> value.toString()
            is String -> "'${value.replace("'", "''")}'"
            is Date -> "'${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(value)}'"
            is LocalDateTime -> "'${DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(value)}'"
            is LocalDate -> "'${DateTimeFormatter.ofPattern("yyyy-MM-dd").format(value)}'"
            is LocalTime -> "'${DateTimeFormatter.ofPattern("HH:mm:ss").format(value)}'"
            else -> "'${value.toString().replace("'", "''")}'"
        }
    }

    override fun buildPaginationSql(sql: String, offset: Long, limit: Long): String {
        return "$sql LIMIT $offset, $limit"
    }
}
