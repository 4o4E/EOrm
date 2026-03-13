package top.e404.eorm.dialect

import top.e404.eorm.generator.IdStrategy
import java.text.SimpleDateFormat
import java.util.Date
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * SQL 方言接口，定义不同数据库的 SQL 生成规则。
 */
interface SqlDialect {
    /**
     * 包装标识符名称（如表名、列名），防止与关键字冲突。
     * @param name 原始名称
     * @return 包装后的名称（如加反引号或双引号）
     */
    fun wrapName(name: String): String

    /**
     * 根据 Java 类型和长度获取对应的数据库列类型。
     * @param type Java 类型
     * @param length 列长度
     * @return SQL 类型字符串
     */
    fun getSqlType(type: Class<*>, length: Int): String

    /**
     * 获取主键列的定义语句。
     * @param strategy 主键生成策略
     * @return 主键定义 SQL 片段
     */
    fun getPrimaryKeyDefinition(strategy: IdStrategy): String

    /**
     * 将值转换为 SQL 字面量表示。
     * @param value 待转换的值，可为 null
     * @return SQL 字面量字符串
     */
    fun valueToSql(value: Any?): String

    /**
     * 构建分页查询 SQL。
     * @param sql 原始查询 SQL
     * @param offset 偏移量
     * @param limit 每页记录数
     * @return 带分页的 SQL
     */
    fun buildPaginationSql(sql: String, offset: Long, limit: Long): String
}

/**
 * SQL 方言基类，提供 [valueToSql] 和 [buildPaginationSql] 的通用实现。
 */
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
