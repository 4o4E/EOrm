package top.e404.eorm

import kotlinx.coroutines.withContext
import top.e404.eorm.dialect.SqlDialect
import top.e404.eorm.dsl.DeleteBuilder
import top.e404.eorm.dsl.Query
import top.e404.eorm.dsl.SubQuery
import top.e404.eorm.dsl.UpdateBuilder
import top.e404.eorm.executor.SqlExecutor
import top.e404.eorm.filler.DataFiller
import top.e404.eorm.filler.NoOpDataFiller
import top.e404.eorm.generator.IdGenerator
import top.e404.eorm.generator.IdStrategy
import top.e404.eorm.log.ConsoleLogger
import top.e404.eorm.log.EOrmLogger
import top.e404.eorm.mapping.CamelToSnakeConverter
import top.e404.eorm.mapping.NameConverter
import top.e404.eorm.meta.MetaCache
import top.e404.eorm.transaction.CoroutineTransaction
import top.e404.eorm.transaction.TransactionManager
import top.e404.eorm.validation.EntityValidator
import java.lang.reflect.Field
import javax.sql.DataSource

class EOrm(
    val dataSource: DataSource,
    val dialect: SqlDialect,
    val nameConverter: NameConverter = CamelToSnakeConverter,
    val logger: EOrmLogger = ConsoleLogger,
    val useLiterals: Boolean = false,
    val dataFiller: DataFiller = NoOpDataFiller()
) {
    val transactionManager = TransactionManager(dataSource)
    val executor = SqlExecutor(dataSource, logger, useLiterals, transactionManager)

    // ==================== 事务 API ====================

    /**
     * 编程式事务（普通线程），自动 commit/rollback。
     *
     * ```kotlin
     * db.transaction {
     *     insert(user)
     *     update<User>().set(User::age, 21).where { eq(User::id, user.id) }.exec()
     * }
     * ```
     */
    fun <T> transaction(block: EOrm.() -> T): T {
        transactionManager.begin()
        return try {
            val result = this.block()
            transactionManager.commit()
            result
        } catch (e: Exception) {
            transactionManager.rollback()
            throw e
        }
    }

    /**
     * 协程事务，在 CoroutineContext 中绑定事务连接。
     * 协程切换线程时通过 [CoroutineTransaction] 自动维护 ThreadLocal。
     *
     * ```kotlin
     * db.suspendTransaction {
     *     insert(user)
     *     // 即使协程切换线程，事务连接也会正确跟随
     * }
     * ```
     */
    suspend fun <T> suspendTransaction(block: suspend EOrm.() -> T): T {
        val conn = dataSource.connection
        conn.autoCommit = false
        val element = CoroutineTransaction(transactionManager, conn)
        return try {
            val result = withContext(element) {
                this@EOrm.block()
            }
            conn.commit()
            result
        } catch (e: Exception) {
            try { conn.rollback() } catch (_: Exception) {}
            throw e
        } finally {
            try { conn.autoCommit = true } catch (_: Exception) {}
            conn.close()
        }
    }

    /**
     * 手动开启事务（仅普通线程）。
     * 推荐使用 [transaction] 自动管理。
     */
    fun beginTransaction() = transactionManager.begin()

    /**
     * 手动提交事务（仅普通线程）。
     */
    fun commitTransaction() = transactionManager.commit()

    /**
     * 手动回滚事务（仅普通线程）。
     */
    fun rollbackTransaction() = transactionManager.rollback()

    // ==================== CRUD ====================

    fun <T : Any> insert(entity: T) = insert(listOf(entity))

    fun <T : Any> insert(entities: List<T>, batchSize: Int = 1000) {
        if (entities.isEmpty()) return
        val clazz = entities[0].javaClass
        val meta = MetaCache.get(clazz, nameConverter)

        var idField: Field? = null
        var idStrategy = IdStrategy.AUTO
        if (meta.idColumn != null) {
            idField = meta.fieldMap[meta.idColumn.lowercase()]
            val colMeta = meta.columnMetas.find { it.isId }
            if (colMeta != null) idStrategy = colMeta.idStrategy
        }

        if (idField != null && idStrategy != IdStrategy.AUTO && idStrategy != IdStrategy.MANUAL) {
            for (entity in entities) {
                val currentId = idField.get(entity)
                if (isIdEmpty(currentId)) {
                    when (idStrategy) {
                        IdStrategy.SNOWFLAKE -> idField.set(entity, IdGenerator.nextSnowflakeId())
                        IdStrategy.UUID -> idField.set(entity, IdGenerator.nextUuid())
                        else -> {}
                    }
                }
            }
        }

        for (entity in entities) dataFiller.insertFill(entity, nameConverter)
        for (entity in entities) EntityValidator.validate(entity, meta)

        val insertCols = meta.columnMetas.filter { col ->
            if (col.isId && col.idStrategy == IdStrategy.AUTO) false else true
        }

        val tableName = dialect.wrapName(meta.tableName)
        val colNames = insertCols.joinToString(", ") { dialect.wrapName(it.columnName) }
        val placeholders = insertCols.joinToString(", ") { "?" }
        val sql = "INSERT INTO $tableName ($colNames) VALUES ($placeholders)"

        for (batch in entities.chunked(batchSize)) {
            val paramsList = batch.map { entity -> insertCols.map { it.field.get(entity) } }
            val fieldToFill = if (idStrategy == IdStrategy.AUTO) idField else null
            executor.executeBatchInsert(sql, paramsList, batch, fieldToFill)
        }
    }

    fun <T : Any> update(entity: T): Int {
        val clazz = entity.javaClass
        val meta = MetaCache.get(clazz, nameConverter)
        val idColumn = meta.idColumn ?: throw IllegalStateException("Cannot update entity without @Id")
        val idField = meta.fieldMap[idColumn.lowercase()] ?: throw IllegalStateException("Id field not found")
        val idValue = idField.get(entity)

        val updateCols = meta.columnMetas.filter { !it.isId }
        val tableName = dialect.wrapName(meta.tableName)
        val setClause = updateCols.joinToString(", ") { "${dialect.wrapName(it.columnName)} = ?" }
        val wrappedIdColumn = dialect.wrapName(idColumn)

        val sql = "UPDATE $tableName SET $setClause WHERE $wrappedIdColumn = ?"

        val params = updateCols.map { it.field.get(entity) } + idValue

        return executor.executeUpdate(sql, params)
    }

    fun <T : Any> delete(entity: T): Int {
        val clazz = entity.javaClass
        val meta = MetaCache.get(clazz, nameConverter)
        val idColumn = meta.idColumn ?: throw IllegalStateException("Cannot delete entity without @Id")
        val idField = meta.fieldMap[idColumn.lowercase()] ?: throw IllegalStateException("Id field not found")
        val idValue = idField.get(entity)

        val tableName = dialect.wrapName(meta.tableName)
        val wrappedIdColumn = dialect.wrapName(idColumn)
        val sql = "DELETE FROM $tableName WHERE $wrappedIdColumn = ?"

        return executor.executeUpdate(sql, listOf(idValue))
    }

    inline fun <reified T : Any> deleteById(id: Any): Int {
        val clazz = T::class.java
        val meta = MetaCache.get(clazz, nameConverter)
        val idColumn = meta.idColumn ?: throw IllegalStateException("Cannot delete entity without @Id")

        val tableName = dialect.wrapName(meta.tableName)
        val wrappedIdColumn = dialect.wrapName(idColumn)
        val sql = "DELETE FROM $tableName WHERE $wrappedIdColumn = ?"

        return executor.executeUpdate(sql, listOf(id))
    }

    private fun isIdEmpty(idVal: Any?): Boolean = when (idVal) {
        null -> true
        is Number -> idVal.toLong() == 0L
        is String -> idVal.isEmpty()
        else -> false
    }

    // ==================== DDL ====================

    fun generateDdl(clazz: Class<*>): String {
        val meta = MetaCache.get(clazz, nameConverter)
        val sb = StringBuilder()
        val tableName = dialect.wrapName(meta.tableName)
        sb.append("CREATE TABLE IF NOT EXISTS $tableName (\n")
        val definitions = ArrayList<String>()
        for (colMeta in meta.columnMetas) {
            val colName = dialect.wrapName(colMeta.columnName)
            val sqlType = dialect.getSqlType(colMeta.field.type, colMeta.length)
            var line = "$colName $sqlType"
            if (!colMeta.nullable) line += " NOT NULL"
            if (colMeta.isId) line += " " + dialect.getPrimaryKeyDefinition(colMeta.idStrategy)
            definitions.add("    $line")
        }
        sb.append(definitions.joinToString(",\n"))
        sb.append("\n)")
        return sb.toString()
    }

    fun createTable(clazz: Class<*>) = executor.execute(generateDdl(clazz))
    inline fun <reified T> createTable() = createTable(T::class.java)

    // ==================== DSL 入口 ====================

    fun <T> from(clazz: Class<T>, alias: String): Query<T> = Query(this, clazz, alias)
    inline fun <reified T> from(alias: String): Query<T> = Query(this, T::class.java, alias)

    fun <T> update(clazz: Class<T>): UpdateBuilder<T> = UpdateBuilder(this, clazz)
    inline fun <reified T> update(): UpdateBuilder<T> = UpdateBuilder(this, T::class.java)

    fun <T> delete(clazz: Class<T>): DeleteBuilder<T> = DeleteBuilder(this, clazz)
    inline fun <reified T> delete(): DeleteBuilder<T> = DeleteBuilder(this, T::class.java)

    fun <T> subQuery(clazz: Class<T>, alias: String): SubQuery<T> = SubQuery(this, clazz, alias)
    inline fun <reified T> subQuery(alias: String): SubQuery<T> = SubQuery(this, T::class.java, alias)
}
