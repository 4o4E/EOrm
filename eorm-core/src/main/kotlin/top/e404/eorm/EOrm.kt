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

/**
 * EOrm 核心类，提供 CRUD、DDL 生成、事务管理及 DSL 查询入口。
 *
 * @param dataSource 数据源
 * @param dialect SQL 方言，用于处理不同数据库的语法差异
 * @param nameConverter 实体属性名与数据库列名的转换器
 * @param logger 日志记录器
 * @param useLiterals 是否将参数直接拼接到 SQL 中（调试用，生产环境慎用）
 * @param dataFiller 数据自动填充器，用于插入时自动填充字段
 */
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

    /**
     * 插入单个实体到数据库。
     *
     * @param entity 待插入的实体对象
     */
    fun <T : Any> insert(entity: T) = insert(listOf(entity))

    /**
     * 批量插入实体到数据库，支持自动 ID 生成和数据自动填充。
     *
     * @param entities 待插入的实体列表
     * @param batchSize 每批次插入的数量，默认 1000
     */
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

    /**
     * 根据实体主键更新所有非主键字段。
     *
     * @param entity 待更新的实体对象，必须包含 @Id 标注的主键字段
     * @return 受影响的行数
     */
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

    /**
     * 根据实体主键删除对应的数据库记录。
     *
     * @param entity 待删除的实体对象，必须包含 @Id 标注的主键字段
     * @return 受影响的行数
     */
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

    /**
     * 根据主键 ID 删除指定类型的数据库记录。
     *
     * @param T 实体类型
     * @param id 主键值
     * @return 受影响的行数
     */
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

    /**
     * 根据实体类的元数据生成建表 DDL 语句。
     *
     * @param clazz 实体类的 Class 对象
     * @return CREATE TABLE SQL 语句
     */
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

    /**
     * 根据实体类的元数据在数据库中创建表。
     *
     * @param clazz 实体类的 Class 对象
     */
    fun createTable(clazz: Class<*>) = executor.execute(generateDdl(clazz))

    /** 根据实体类的元数据在数据库中创建表（reified 泛型重载）。 */
    inline fun <reified T> createTable() = createTable(T::class.java)

    // ==================== DSL 入口 ====================

    /**
     * DSL 查询入口，创建一个针对指定实体类的查询构建器。
     *
     * @param clazz 实体类的 Class 对象
     * @param alias 表别名
     * @return 查询构建器
     */
    fun <T> from(clazz: Class<T>, alias: String): Query<T> = Query(this, clazz, alias)

    /** DSL 查询入口（reified 泛型重载）。 */
    inline fun <reified T> from(alias: String): Query<T> = Query(this, T::class.java, alias)

    /**
     * DSL 更新入口，创建一个针对指定实体类的更新构建器。
     *
     * @param clazz 实体类的 Class 对象
     * @return 更新构建器
     */
    fun <T> update(clazz: Class<T>): UpdateBuilder<T> = UpdateBuilder(this, clazz)

    /** DSL 更新入口（reified 泛型重载）。 */
    inline fun <reified T> update(): UpdateBuilder<T> = UpdateBuilder(this, T::class.java)

    /**
     * DSL 删除入口，创建一个针对指定实体类的删除构建器。
     *
     * @param clazz 实体类的 Class 对象
     * @return 删除构建器
     */
    fun <T> delete(clazz: Class<T>): DeleteBuilder<T> = DeleteBuilder(this, clazz)

    /** DSL 删除入口（reified 泛型重载）。 */
    inline fun <reified T> delete(): DeleteBuilder<T> = DeleteBuilder(this, T::class.java)

    /**
     * 创建子查询构建器，用于构建可嵌套的子查询 SQL 片段。
     *
     * @param clazz 子查询实体类的 Class 对象
     * @param alias 子查询表别名
     * @return 子查询构建器
     */
    fun <T> subQuery(clazz: Class<T>, alias: String): SubQuery<T> = SubQuery(this, clazz, alias)

    /** 创建子查询构建器（reified 泛型重载）。 */
    inline fun <reified T> subQuery(alias: String): SubQuery<T> = SubQuery(this, T::class.java, alias)
}
