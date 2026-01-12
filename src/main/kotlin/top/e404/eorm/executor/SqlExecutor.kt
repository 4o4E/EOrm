package top.e404.eorm.executor

import top.e404.eorm.log.EOrmLogger
import top.e404.eorm.meta.MetaCache
import top.e404.eorm.mapping.NameConverter
import java.lang.reflect.Field
import java.sql.Statement
import java.text.SimpleDateFormat
import java.util.Date
import javax.sql.DataSource
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class SqlExecutor(
    private val dataSource: DataSource,
    private val logger: EOrmLogger,
    private val useLiterals: Boolean
) {
    fun execute(sql: String) {
        logger.logSql(sql)
        dataSource.connection.use { conn -> conn.createStatement().use { stmt -> stmt.execute(sql) } }
    }

    fun <T> executeBatchInsert(sqlTemplate: String, paramsList: List<List<Any?>>, entities: List<T>, idField: Field?) {
        try {
            if (useLiterals) {
                logger.info("Batch Insert (Literal Mode) Size: ${paramsList.size}")
                dataSource.connection.use { conn ->
                    conn.createStatement().use { stmt ->
                        paramsList.forEach { params ->
                            var finalSql = sqlTemplate
                            params.forEach { p -> finalSql = finalSql.replaceFirst("?", toSqlVal(p)) }
                            logger.logSql(finalSql)
                            stmt.addBatch(finalSql)
                        }
                        stmt.executeBatch()
                    }
                }
            } else {
                if (paramsList.isNotEmpty()) logger.logSql(sqlTemplate)
                dataSource.connection.use { conn ->
                    conn.prepareStatement(sqlTemplate, Statement.RETURN_GENERATED_KEYS).use { stmt ->
                        val batchSize = 100
                        for ((i, params) in paramsList.withIndex()) {
                            params.forEachIndexed { idx, value -> stmt.setObject(idx + 1, convertParam(value)) }
                            stmt.addBatch()
                            if ((i + 1) % batchSize == 0) stmt.executeBatch()
                        }
                        stmt.executeBatch()
                        if (idField != null) {
                            val rs = stmt.generatedKeys
                            var index = 0
                            while (rs.next() && index < entities.size) {
                                val generatedKey = rs.getObject(1)
                                val entity = entities[index]
                                if (isIdEmpty(idField.get(entity))) {
                                    idField.set(entity, convertType(generatedKey, idField.type))
                                }
                                index++
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    private fun toSqlVal(value: Any?): String = when(value) {
        null -> "NULL"
        is String -> "'${value.replace("'", "''")}'"
        is Date -> "'${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(value)}'"
        is LocalDateTime -> "'${DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(value)}'"
        is LocalDate -> "'${DateTimeFormatter.ofPattern("yyyy-MM-dd").format(value)}'"
        is LocalTime -> "'${DateTimeFormatter.ofPattern("HH:mm:ss").format(value)}'"
        else -> value.toString()
    }

    fun <T> query(sql: String, params: List<Any?>, clazz: Class<T>, converter: NameConverter): List<T> {
        logger.logSql(sql)
        val list = ArrayList<T>()
        dataSource.connection.use { conn ->
            if (useLiterals) {
                conn.createStatement().use { stmt -> stmt.executeQuery(sql).use { rs -> mapResult(rs, clazz, list, converter) } }
            } else {
                conn.prepareStatement(sql).use { stmt ->
                    params.forEachIndexed { index, value -> stmt.setObject(index + 1, convertParam(value)) }
                    stmt.executeQuery().use { rs -> mapResult(rs, clazz, list, converter) }
                }
            }
        }
        return list
    }

    fun queryMap(sql: String, params: List<Any?>): List<Map<String, Any?>> {
        logger.logSql(sql)
        val list = ArrayList<Map<String, Any?>>()
        dataSource.connection.use { conn ->
            if (useLiterals) {
                conn.createStatement().use { stmt -> stmt.executeQuery(sql).use { rs -> mapResultMap(rs, list) } }
            } else {
                conn.prepareStatement(sql).use { stmt ->
                    params.forEachIndexed { index, value -> stmt.setObject(index + 1, convertParam(value)) }
                    stmt.executeQuery().use { rs -> mapResultMap(rs, list) }
                }
            }
        }
        return list
    }

    fun executeUpdate(sql: String, params: List<Any?>): Int {
        logger.logSql(sql)
        return dataSource.connection.use { conn ->
            if (useLiterals) {
                var finalSql = sql
                params.forEach { p -> finalSql = finalSql.replaceFirst("?", toSqlVal(p)) }
                logger.logSql(finalSql)
                conn.createStatement().use { it.executeUpdate(finalSql) }
            } else {
                conn.prepareStatement(sql).use { stmt ->
                    params.forEachIndexed { index, value -> stmt.setObject(index + 1, convertParam(value)) }
                    stmt.executeUpdate()
                }
            }
        }
    }

    private fun <T> mapResult(rs: java.sql.ResultSet, clazz: Class<T>, list: ArrayList<T>, converter: NameConverter) {
        val metaData = rs.metaData
        val colCount = metaData.columnCount
        val tableMeta = MetaCache.get(clazz, converter)
        while (rs.next()) {
            val instance = clazz.getDeclaredConstructor().newInstance()
            for (i in 1..colCount) {
                val colLabel = metaData.getColumnLabel(i)
                val field = tableMeta.fieldMap[colLabel.lowercase()]
                if (field != null) {
                    val value = rs.getObject(i)
                    if (value != null) field.set(instance, convertType(value, field.type))
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

    private fun convertType(value: Any, targetType: Class<*>): Any {
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
        
        // H2/MySQL driver compatibility
        if (value is LocalDateTime && targetType == LocalDateTime::class.java) return value
        if (value is LocalDate && targetType == LocalDate::class.java) return value
        if (value is LocalTime && targetType == LocalTime::class.java) return value
        
        return value
    }
    private fun convertParam(value: Any?): Any? = when (value) {
        null -> null
        is LocalDateTime -> java.sql.Timestamp.valueOf(value)
        is LocalDate -> java.sql.Date.valueOf(value)
        is LocalTime -> java.sql.Time.valueOf(value)
        else -> value
    }

    private fun isIdEmpty(idVal: Any?): Boolean = when (idVal) { null -> true; is Number -> idVal.toLong() == 0L; else -> false }
}
