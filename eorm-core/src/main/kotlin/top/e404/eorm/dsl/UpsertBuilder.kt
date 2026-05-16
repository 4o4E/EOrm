package top.e404.eorm.dsl

import top.e404.eorm.EOrm
import top.e404.eorm.generator.IdStrategy
import top.e404.eorm.meta.ColumnMeta
import top.e404.eorm.meta.MetaCache
import kotlin.reflect.KProperty1

/**
 * UPSERT 构建器，用于插入实体或在唯一冲突时更新指定列。
 */
class UpsertBuilder<T : Any>(
    private val eOrm: EOrm,
    private val entities: List<T>
) {
    private val conflictColumns = ArrayList<String>()
    private val updateColumns = ArrayList<String>()

    /**
     * 指定冲突列。可传实体字段名或数据库列名。
     */
    fun on(vararg columns: String): UpsertBuilder<T> {
        conflictColumns.addAll(columns)
        return this
    }

    /**
     * 指定冲突列。
     */
    fun on(vararg props: KProperty1<T, *>): UpsertBuilder<T> {
        props.forEach { conflictColumns.add(MetaCache.resolveColumn(it, eOrm.nameConverter)) }
        return this
    }

    /**
     * 指定冲突后需要更新的列。可传实体字段名或数据库列名。
     */
    fun update(vararg columns: String): UpsertBuilder<T> {
        updateColumns.addAll(columns)
        return this
    }

    /**
     * 指定冲突后需要更新的列。
     */
    fun update(vararg props: KProperty1<T, *>): UpsertBuilder<T> {
        props.forEach { updateColumns.add(MetaCache.resolveColumn(it, eOrm.nameConverter)) }
        return this
    }

    /**
     * 执行 UPSERT，返回受影响行数。不同数据库对更新/插入的计数规则可能不同。
     */
    fun exec(): Int {
        if (entities.isEmpty()) return 0
        val clazz = entities[0].javaClass
        val meta = MetaCache.get(clazz, eOrm.nameConverter)
        val resolvedConflictColumns = resolveColumns(conflictColumns, meta.columns)
        require(resolvedConflictColumns.isNotEmpty()) { "Upsert conflict columns cannot be empty" }

        eOrm.fillGeneratedIds(entities, meta)

        val insertCols = meta.columnMetas.filter { col ->
            !(col.isId && col.idStrategy == IdStrategy.AUTO)
        }
        val resolvedUpdateColumns = if (updateColumns.isEmpty()) {
            insertCols
                .filter { !it.isId && it.columnName !in resolvedConflictColumns }
                .map { it.columnName }
        } else {
            resolveColumns(updateColumns, meta.columns)
        }

        val tableName = eOrm.dialect.wrapName(meta.tableName)
        val sql = eOrm.dialect.buildUpsertSql(
            tableName = tableName,
            insertColumns = insertCols.map { eOrm.dialect.wrapName(it.columnName) },
            conflictColumns = resolvedConflictColumns.map { eOrm.dialect.wrapName(it) },
            updateColumns = resolvedUpdateColumns.map { eOrm.dialect.wrapName(it) }
        )
        val paramsList = entities.map { entity ->
            insertCols.map { col: ColumnMeta -> eOrm.getColumnValue(entity, col) }
        }
        eOrm.logger.info(
            "Upsert execute batch size: ${paramsList.size}, conflict columns: ${resolvedConflictColumns.joinToString(",")}, update columns: ${resolvedUpdateColumns.joinToString(",")}"
        )
        val affected = eOrm.executor.executeBatchUpdate(sql, paramsList)
        eOrm.logger.info("Upsert affected rows: $affected, batch size: ${paramsList.size}")
        return affected
    }

    private fun resolveColumns(columns: List<String>, propToColumn: Map<String, String>): List<String> {
        return columns.map { propToColumn[it] ?: it }
    }
}
