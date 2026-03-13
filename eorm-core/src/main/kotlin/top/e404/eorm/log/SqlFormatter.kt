package top.e404.eorm.log

import top.e404.eorm.dialect.SqlDialect

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
 * 通过 [SqlDialect.valueToSql] 将参数转为字面量，保证日志输出与实际执行的 SQL 一致。
 *
 * @param dialect SQL 方言，用于值到字面量的转换
 */
class DefaultSqlFormatter(private val dialect: SqlDialect) : SqlFormatter {
    override fun format(sql: String, args: List<Any?>): String {
        if (args.isEmpty()) return sql
        val sb = StringBuilder()
        var argIndex = 0
        for (c in sql) {
            if (c == '?' && argIndex < args.size) {
                sb.append(dialect.valueToSql(args[argIndex++]))
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }
}
