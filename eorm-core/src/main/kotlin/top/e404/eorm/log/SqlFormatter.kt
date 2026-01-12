package top.e404.eorm.log

import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Date

interface SqlFormatter {
    fun format(sql: String, args: List<Any?>): String
}

object DefaultSqlFormatter : SqlFormatter {
    override fun format(sql: String, args: List<Any?>): String {
        if (args.isEmpty()) return sql
        val sb = StringBuilder()
        var argIndex = 0
        var i = 0
        val len = sql.length
        while (i < len) {
            val c = sql[i]
            if (c == '?') {
                // Check if ? is matched with an argument
                if (argIndex < args.size) {
                    sb.append(valueToSql(args[argIndex++]))
                } else {
                    sb.append("?")
                }
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    fun valueToSql(value: Any?): String {
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
}
