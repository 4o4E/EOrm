package top.e404.eorm.dialect

import top.e404.eorm.generator.IdStrategy
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Date

class MySqlDialect : BaseDialect() {
    override fun wrapName(name: String): String = "`$name`"
    override fun getSqlType(type: Class<*>, length: Int): String {
        return when (type) {
            Boolean::class.java, java.lang.Boolean::class.java -> "TINYINT"
            String::class.java -> "VARCHAR($length)"
            Int::class.java, Integer::class.java -> "INT"
            Long::class.java, java.lang.Long::class.java -> "BIGINT"
            Double::class.java, java.lang.Double::class.java -> "DOUBLE"
            Date::class.java, java.util.Date::class.java -> "DATETIME"
            LocalDateTime::class.java -> "DATETIME"
            LocalDate::class.java -> "DATE"
            LocalTime::class.java -> "TIME"
            else -> "VARCHAR(255)"
        }
    }
    override fun getPrimaryKeyDefinition(strategy: IdStrategy): String = if (strategy == IdStrategy.AUTO) "PRIMARY KEY AUTO_INCREMENT" else "PRIMARY KEY"

    override fun getJsonSqlType(): String = "JSON"

    override fun buildUpsertSql(
        tableName: String,
        insertColumns: List<String>,
        conflictColumns: List<String>,
        updateColumns: List<String>
    ): String {
        val insertSql = buildInsertSql(tableName, insertColumns)
        if (updateColumns.isEmpty()) return "$insertSql ON DUPLICATE KEY UPDATE ${insertColumns.first()} = ${insertColumns.first()}"
        val updates = updateColumns.joinToString(", ") { "$it = VALUES($it)" }
        return "$insertSql ON DUPLICATE KEY UPDATE $updates"
    }

    override fun buildCreateIndexSql(indexName: String, tableName: String, columnNames: List<String>, unique: Boolean): String {
        val uniqueSql = if (unique) "UNIQUE " else ""
        val cols = columnNames.joinToString(", ")
        return "CREATE ${uniqueSql}INDEX $indexName ON $tableName ($cols)"
    }
}
