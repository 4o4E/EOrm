package top.e404.eorm.log

import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Date

/**
 * SQL 格式化器接口，用于将带占位符的 SQL 与参数合并为可读的完整 SQL。
 */
interface SqlFormatter {
    /**
     * 将 SQL 中的 `?` 占位符替换为实际参数值。
     * @param sql 带占位符的 SQL 语句
     * @param args 参数列表
     * @return 格式化后的完整 SQL
     */
    fun format(sql: String, args: List<Any?>): String
}

/**
 * 默认 SQL 格式化器实现。
 */
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

    /**
     * 将值转换为 SQL 字面量表示。
     * @param value 待转换的值，可为 null
     * @return SQL 字面量字符串
     */
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
