package top.e404.eorm.executor

import top.e404.eorm.dialect.SqlDialect
import top.e404.eorm.json.JsonCodec
import top.e404.eorm.json.JsonDbValue
import top.e404.eorm.log.EOrmLogger
import top.e404.eorm.log.DefaultSqlFormatter
import top.e404.eorm.meta.MetaCache
import top.e404.eorm.mapping.NameConverter
import top.e404.eorm.transaction.TransactionManager
import java.lang.reflect.Field
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.sql.Types
import javax.sql.DataSource
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.LocalTime

/**
 * SQL 执行器，负责所有 SQL 的执行、结果映射和类型转换。
 */
class SqlExecutor(
    private val dataSource: DataSource,
    private val logger: EOrmLogger,
    private val useLiterals: Boolean,
    private val transactionManager: TransactionManager,
    private val dialect: SqlDialect
) {
    private val formatter = DefaultSqlFormatter(dialect)

    /**
     * 获取连接并执行操作。
     * 事务中复用绑定的连接（不关闭），非事务中获取新连接并自动关闭。
     */
    private inline fun <R> withConnection(block: (Connection) -> R): R {
        val inTx = transactionManager.isInTransaction()
        val conn = transactionManager.getConnection()
        return try {
            block(conn)
        } finally {
            if (!inTx) conn.close()
        }
    }

    fun execute(sql: String) {
        logger.logSql(sql)
        withConnection { conn -> conn.createStatement().use { stmt -> stmt.execute(sql) } }
    }

    /**
     * 批量插入执行。
     * INSERT SQL 构建、PreparedStatement 创建、自增 ID 回填均委托给 [SqlDialect]。
     */
    fun <T> executeBatchInsert(
        sqlTemplate: String,
        paramsList: List<List<Any?>>,
        entities: List<T>,
        idField: Field?,
        idColumnName: String?
    ) {
        try {
            logger.debug("Batch insert size: ${paramsList.size}")
            if (useLiterals) {
                var affected = 0
                withConnection { conn ->
                    conn.createStatement().use { stmt ->
                        paramsList.forEach { params ->
                            val finalSql = formatter.format(sqlTemplate, params)
                            logger.logSql(finalSql)
                            stmt.addBatch(finalSql)
                        }
                        affected += countAffected(stmt.executeBatch())
                    }
                }
                logger.debug("Batch insert affected rows: $affected, batch size: ${paramsList.size}")
            } else {
                if (paramsList.isNotEmpty()) logger.logSql(sqlTemplate, paramsList[0])
                var affected = 0
                withConnection { conn ->
                    dialect.prepareInsertStatement(conn, sqlTemplate, idColumnName).use { stmt ->
                        val batchSize = dialect.getInsertBatchSize()
                        for ((i, params) in paramsList.withIndex()) {
                            params.forEachIndexed { idx, value -> setParam(stmt, idx + 1, value) }
                            stmt.addBatch()
                            if ((i + 1) % batchSize == 0) affected += countAffected(stmt.executeBatch())
                        }
                        if (paramsList.size % batchSize != 0) {
                            affected += countAffected(stmt.executeBatch())
                        }
                        if (idField != null) {
                            dialect.extractGeneratedKeys(stmt, entities, idField, ::convertType)
                        }
                    }
                }
                logger.debug("Batch insert affected rows: $affected, batch size: ${paramsList.size}")
            }
        } catch (e: Exception) {
            logger.error("Failed to execute batch insert", e)
            throw e
        }
    }

    fun <T> query(sql: String, params: List<Any?>, clazz: Class<T>, converter: NameConverter): List<T> {
        logger.logSql(sql, params)
        val list = ArrayList<T>()
        withConnection { conn ->
            if (useLiterals) {
                val finalSql = formatter.format(sql, params)
                conn.createStatement().use { stmt -> stmt.executeQuery(finalSql).use { rs -> mapResult(rs, clazz, list, converter) } }
            } else {
                conn.prepareStatement(sql).use { stmt ->
                    params.forEachIndexed { index, value -> setParam(stmt, index + 1, value) }
                    stmt.executeQuery().use { rs -> mapResult(rs, clazz, list, converter) }
                }
            }
        }
        return list
    }

    fun queryMap(sql: String, params: List<Any?>): List<Map<String, Any?>> {
        logger.logSql(sql, params)
        val list = ArrayList<Map<String, Any?>>()
        withConnection { conn ->
            if (useLiterals) {
                val finalSql = formatter.format(sql, params)
                conn.createStatement().use { stmt -> stmt.executeQuery(finalSql).use { rs -> mapResultMap(rs, list) } }
            } else {
                conn.prepareStatement(sql).use { stmt ->
                    params.forEachIndexed { index, value -> setParam(stmt, index + 1, value) }
                    stmt.executeQuery().use { rs -> mapResultMap(rs, list) }
                }
            }
        }
        return list
    }

    fun executeUpdate(sql: String, params: List<Any?>): Int {
        logger.logSql(sql, params)
        return withConnection { conn ->
            if (useLiterals) {
                val finalSql = formatter.format(sql, params)
                conn.createStatement().use { it.executeUpdate(finalSql) }
            } else {
                conn.prepareStatement(sql).use { stmt ->
                    params.forEachIndexed { index, value -> setParam(stmt, index + 1, value) }
                    stmt.executeUpdate()
                }
            }
        }
    }

    fun executeBatchUpdate(sql: String, paramsList: List<List<Any?>>): Int {
        if (paramsList.isEmpty()) return 0
        logger.logSql(sql, paramsList[0])
        return withConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                var affected = 0
                for (params in paramsList) {
                    params.forEachIndexed { index, value -> setParam(stmt, index + 1, value) }
                    stmt.addBatch()
                }
                stmt.executeBatch().forEach { count ->
                    if (count > 0) affected += count
                }
                logger.debug("Batch update affected rows: $affected, batch size: ${paramsList.size}")
                affected
            }
        }
    }

    private fun countAffected(results: IntArray): Int {
        var affected = 0
        for (count in results) {
            if (count > 0) affected += count
        }
        return affected
    }

    private fun <T> mapResult(rs: java.sql.ResultSet, clazz: Class<T>, list: ArrayList<T>, converter: NameConverter) {
        val metaData = rs.metaData
        val colCount = metaData.columnCount
        val tableMeta = MetaCache.get(clazz, converter)
        while (rs.next()) {
            val instance = clazz.getDeclaredConstructor().newInstance()
            for (i in 1..colCount) {
                val colLabel = metaData.getColumnLabel(i)
                val colMeta = tableMeta.columnMetaMap[colLabel.lowercase()]
                val field = colMeta?.field
                if (field != null) {
                    val value = rs.getObject(i)
                    if (value != null) {
                        val converted = if (colMeta.isJson) JsonCodec.fromJsonValue(value, field.type) else convertType(value, field.type)
                        field.set(instance, converted)
                    }
                }
            }
            list.add(instance)
        }
    }

    private fun mapResultMap(rs: java.sql.ResultSet, list: ArrayList<Map<String, Any?>>) {
        val metaData = rs.metaData
        val colCount = metaData.columnCount
        while (rs.next()) {
            val map = LinkedHashMap<String, Any?>()
            for (i in 1..colCount) map[metaData.getColumnLabel(i)] = rs.getObject(i)
            list.add(map)
        }
    }

    internal fun convertType(value: Any, targetType: Class<*>): Any {
        if (targetType == Int::class.java || targetType == Integer::class.java) return (value as Number).toInt()
        if (targetType == Long::class.java || targetType == java.lang.Long::class.java) return (value as Number).toLong()
        if (targetType == Double::class.java || targetType == java.lang.Double::class.java) return (value as Number).toDouble()
        if (targetType == Boolean::class.java || targetType == java.lang.Boolean::class.java) {
             if (value is Boolean) return value
             if (value is Number) return value.toInt() != 0
             if (value is String) return value.toBoolean()
        }
        
        if (value is java.sql.Timestamp && targetType == LocalDateTime::class.java) return value.toLocalDateTime()
        if (value is java.sql.Date && targetType == LocalDate::class.java) return value.toLocalDate()
        if (value is java.sql.Time && targetType == LocalTime::class.java) return value.toLocalTime()
        
        if (value is LocalDateTime && targetType == LocalDateTime::class.java) return value
        if (value is LocalDate && targetType == LocalDate::class.java) return value
        if (value is LocalTime && targetType == LocalTime::class.java) return value
        
        return value
    }

    private fun convertParam(value: Any?): Any? = when (value) {
        null -> null
        is JsonDbValue -> value
        is LocalDateTime -> java.sql.Timestamp.valueOf(value)
        is LocalDate -> java.sql.Date.valueOf(value)
        is LocalTime -> java.sql.Time.valueOf(value)
        else -> value
    }

    private fun setParam(stmt: PreparedStatement, index: Int, value: Any?) {
        when (val converted = convertParam(value)) {
            is JsonDbValue -> {
                if (dialect.bindJsonAsOther()) stmt.setObject(index, converted.json, Types.OTHER)
                else stmt.setObject(index, converted.json)
            }
            else -> stmt.setObject(index, converted)
        }
    }
}
