package top.e404.eorm.dialect

import top.e404.eorm.generator.IdStrategy
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Date

class SqliteDialect : BaseDialect() {
    override fun wrapName(name: String): String = "\"$name\""
    override fun getSqlType(type: Class<*>, length: Int): String {
        return when (type) {
            Boolean::class.java, java.lang.Boolean::class.java -> "INTEGER"
            String::class.java -> "TEXT"
            Int::class.java, Integer::class.java -> "INTEGER"
            Long::class.java, java.lang.Long::class.java -> "INTEGER"
            Double::class.java, java.lang.Double::class.java -> "REAL"
            Date::class.java, java.util.Date::class.java -> "TEXT"
            LocalDateTime::class.java -> "TEXT"
            LocalDate::class.java -> "TEXT"
            LocalTime::class.java -> "TEXT"
            else -> "TEXT"
        }
    }

    override fun getPrimaryKeyDefinition(strategy: IdStrategy): String =
        if (strategy == IdStrategy.AUTO) "PRIMARY KEY AUTOINCREMENT" else "PRIMARY KEY"
}
