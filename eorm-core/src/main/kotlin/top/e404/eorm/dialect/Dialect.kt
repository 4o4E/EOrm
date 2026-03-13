package top.e404.eorm.dialect

import top.e404.eorm.generator.IdStrategy
import java.lang.reflect.Field
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
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

    /**
     * 构建带 LIMIT 的查询 SQL（不含偏移量）。
     * @param sql 原始查询 SQL
     * @param limit 最大返回行数
     * @return 带 LIMIT 的 SQL
     */
    fun buildLimitSql(sql: String, limit: Int): String

    /**
     * 是否支持 `CREATE TABLE IF NOT EXISTS` 语法。
     * @return 支持返回 true，不支持返回 false
     */
    fun supportsIfNotExists(): Boolean

    /**
     * 构建 INSERT SQL 语句。
     * 默认生成标准 `INSERT INTO table (cols) VALUES (?)` 语法。
     * 方言可覆写以追加 `RETURNING id` 等子句。
     *
     * @param tableName 已包装的表名
     * @param columnNames 已包装的列名列表
     * @return INSERT SQL 模板（使用 `?` 占位符）
     */
    fun buildInsertSql(tableName: String, columnNames: List<String>): String

    /**
     * 为批量插入创建 PreparedStatement。
     * 默认使用 [Statement.RETURN_GENERATED_KEYS]。
     * 方言可覆写以使用特定的 generated keys 获取方式（如 Oracle 需指定列名数组）。
     *
     * @param conn 数据库连接
     * @param sql INSERT SQL 模板
     * @param idColumnName 主键列名（已包装），无自增主键时为 null
     * @return PreparedStatement
     */
    fun prepareInsertStatement(conn: Connection, sql: String, idColumnName: String?): PreparedStatement

    /**
     * 从已执行的 PreparedStatement 中提取自增生成的主键值，回填到实体对象。
     * 默认通过 [Statement.getGeneratedKeys] 获取。
     * 方言可覆写以处理特定数据库的差异（如 PostgreSQL 的 RETURNING 结果集）。
     *
     * @param stmt 已执行的 PreparedStatement
     * @param entities 实体列表
     * @param idField 主键字段
     * @param convertType 类型转换函数
     */
    fun <T> extractGeneratedKeys(
        stmt: PreparedStatement,
        entities: List<T>,
        idField: Field,
        convertType: (Any, Class<*>) -> Any
    )

    /**
     * 获取批量插入时每批次刷新的行数。
     * 不同数据库和驱动的最优批量大小不同。
     * @return 批次大小
     */
    fun getInsertBatchSize(): Int
}

/**
 * SQL 方言基类，提供通用默认实现。
 * 默认分页语法使用 SQL 标准的 `LIMIT n OFFSET m`。
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
        return "$sql LIMIT $limit OFFSET $offset"
    }

    override fun buildLimitSql(sql: String, limit: Int): String {
        return "$sql LIMIT $limit"
    }

    override fun supportsIfNotExists(): Boolean = true

    override fun buildInsertSql(tableName: String, columnNames: List<String>): String {
        val cols = columnNames.joinToString(", ")
        val placeholders = columnNames.joinToString(", ") { "?" }
        return "INSERT INTO $tableName ($cols) VALUES ($placeholders)"
    }

    override fun prepareInsertStatement(conn: Connection, sql: String, idColumnName: String?): PreparedStatement {
        return conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
    }

    override fun <T> extractGeneratedKeys(
        stmt: PreparedStatement,
        entities: List<T>,
        idField: Field,
        convertType: (Any, Class<*>) -> Any
    ) {
        val rs = stmt.generatedKeys
        var index = 0
        while (rs.next() && index < entities.size) {
            val generatedKey = rs.getObject(1)
            val entity = entities[index]
            val currentVal = idField.get(entity)
            if (currentVal == null || (currentVal is Number && currentVal.toLong() == 0L)) {
                idField.set(entity, convertType(generatedKey, idField.type))
            }
            index++
        }
    }

    override fun getInsertBatchSize(): Int = 100
}
