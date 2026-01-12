package top.e404.eorm.dsl

import top.e404.eorm.EOrm
import top.e404.eorm.dialect.SqlDialect
import top.e404.eorm.mapping.NameConverter
import top.e404.eorm.meta.MetaCache
import kotlin.reflect.KProperty1

class ConditionBuilder(
    private val dialect: SqlDialect,
    private val aliasResolver: (Class<*>) -> String?,
    private val useLiterals: Boolean,
    private val nameConverter: NameConverter
) {
    internal val sqlParts = StringBuilder()
    internal val params = ArrayList<Any?>()

    // 基础连接符
    fun and() { if(shouldAppendConnector()) sqlParts.append(" AND ") }
    fun or() { if(shouldAppendConnector()) sqlParts.append(" OR ") }

    // --- 嵌套支持 (New) ---

    /**
     * 自动处理连接符并生成 (...)
     * 示例: nest { eq(...) or(...) } -> AND ( ... OR ... )
     */
    fun nest(clause: ConditionBuilder.() -> Unit) {
        if (shouldAppendConnector()) sqlParts.append(" AND ")
        sqlParts.append("(")
        this.clause()
        sqlParts.append(")")
    }

    /**
     * 显式 OR 嵌套
     * 示例: or { eq(...) } -> OR ( ... )
     */
    fun or(clause: ConditionBuilder.() -> Unit) {
        if (shouldAppendConnector()) sqlParts.append(" OR ")
        else if (sqlParts.isNotEmpty() && !sqlParts.endsWith("(")) sqlParts.append(" OR ") // 容错处理

        sqlParts.append("(")
        this.clause()
        sqlParts.append(")")
    }

    /**
     * 显式 AND 嵌套
     * 示例: and { eq(...) } -> AND ( ... )
     */
    fun and(clause: ConditionBuilder.() -> Unit) {
        if (shouldAppendConnector()) sqlParts.append(" AND ")

        sqlParts.append("(")
        this.clause()
        sqlParts.append(")")
    }

    fun nest(clause: ClauseConsumer) = nest { clause.accept(this) }
    fun or(clause: ClauseConsumer) = or { clause.accept(this) }
    fun and(clause: ClauseConsumer) = and { clause.accept(this) }

    // --- 基础条件 ---

    fun eq(column: String, value: Any?) = addCond(column, "=", value)
    fun gt(column: String, value: Any?) = addCond(column, ">", value)
    fun ge(column: String, value: Any?) = addCond(column, ">=", value)
    fun lt(column: String, value: Any?) = addCond(column, "<", value)
    fun le(column: String, value: Any?) = addCond(column, "<=", value)
    fun like(column: String, value: Any?) = addCond(column, "LIKE", value)

    fun eq(prop: KProperty1<*, *>, value: Any?) = eq(resolveAndWrap(prop), value)
    fun gt(prop: KProperty1<*, *>, value: Any?) = gt(resolveAndWrap(prop), value)
    fun ge(prop: KProperty1<*, *>, value: Any?) = ge(resolveAndWrap(prop), value)
    fun lt(prop: KProperty1<*, *>, value: Any?) = lt(resolveAndWrap(prop), value)
    fun le(prop: KProperty1<*, *>, value: Any?) = le(resolveAndWrap(prop), value)
    fun like(prop: KProperty1<*, *>, value: Any?) = like(resolveAndWrap(prop), value)

    fun eqCol(col1: String, col2: String) {
        if(shouldAppendConnector()) and()
        sqlParts.append("$col1 = $col2")
    }
    fun eqCol(prop1: KProperty1<*, *>, prop2: KProperty1<*, *>) = eqCol(resolveAndWrap(prop1), resolveAndWrap(prop2))

    // --- 内部辅助 ---

    private fun shouldAppendConnector(): Boolean {
        if (sqlParts.isEmpty()) return false
        // 如果结尾是 AND/OR 或者刚开始一个括号 (，则不需要加连接符
        return !sqlParts.endsWith("AND ") && !sqlParts.endsWith("OR ") && !sqlParts.endsWith("(")
    }

    private fun addCond(col: String, op: String, value: Any?) {
        // 自动追加 AND
        if (shouldAppendConnector()) {
            and()
        }

        if (useLiterals) {
            sqlParts.append("$col $op ${dialect.valueToSql(value)}")
        } else {
            sqlParts.append("$col $op ?")
            params.add(value)
        }
    }

    private fun resolveAndWrap(prop: KProperty1<*, *>): String {
        val colName = MetaCache.resolveColumn(prop, nameConverter)
        val wrappedCol = dialect.wrapName(colName)
        val alias = aliasResolver(MetaCache.resolveClass(prop))
        return if (alias != null) "$alias.$wrappedCol" else wrappedCol
    }
}

fun interface ClauseConsumer { fun accept(builder: ConditionBuilder) }

class Query<T>(
    private val eOrm: EOrm,
    private val clazz: Class<T>,
    private val alias: String
) {
    private val selectColumns = ArrayList<String>()
    private val joins = ArrayList<String>()
    private val aliasMap = mutableMapOf<Class<*>, String>()
    private val aliasResolver = { cls: Class<*> -> aliasMap[cls] }
    private val whereBuilder = ConditionBuilder(eOrm.dbType.dialect, aliasResolver, eOrm.useLiterals, eOrm.nameConverter)
    private var limit: Int? = null

    init { aliasMap[clazz] = alias }

    fun select(vararg cols: String): Query<T> { selectColumns.addAll(cols); return this }
    fun select(vararg props: KProperty1<*, *>): Query<T> {
        props.forEach { prop ->
            val colName = MetaCache.resolveColumn(prop, eOrm.nameConverter)
            val wrappedCol = eOrm.dbType.dialect.wrapName(colName)
            val ownerAlias = aliasMap[MetaCache.resolveClass(prop)]
            selectColumns.add(if (ownerAlias != null) "$ownerAlias.$wrappedCol" else wrappedCol)
        }
        return this
    }

    fun join(target: Class<*>, alias: String, type: String = "INNER", onClause: ClauseConsumer): Query<T> {
        aliasMap[target] = alias
        val meta = MetaCache.get(target, eOrm.nameConverter)
        val tableName = eOrm.dbType.dialect.wrapName(meta.tableName)
        val builder = ConditionBuilder(eOrm.dbType.dialect, aliasResolver, eOrm.useLiterals, eOrm.nameConverter)
        onClause.accept(builder)
        joins.add("$type JOIN $tableName $alias ON ${builder.sqlParts}")
        return this
    }

    inline fun <reified J> leftJoin(alias: String, noinline onClause: ConditionBuilder.() -> Unit): Query<T> = join(J::class.java, alias, "LEFT") { b -> b.onClause() }

    fun where(clause: ClauseConsumer): Query<T> { clause.accept(whereBuilder); return this }
    fun where(clause: ConditionBuilder.() -> Unit): Query<T> = where(ClauseConsumer { b -> b.clause() })

    fun limit(n: Int): Query<T> {
        this.limit = n
        return this
    }

    private fun buildSql(): Pair<String, List<Any?>> {
        val meta = MetaCache.get(clazz, eOrm.nameConverter)
        val tableName = eOrm.dbType.dialect.wrapName(meta.tableName)
        val tableStr = "$tableName $alias"
        val cols = if (selectColumns.isEmpty()) "*" else selectColumns.joinToString(", ")
        val sb = StringBuilder("SELECT $cols FROM $tableStr")
        joins.forEach { sb.append(" ").append(it) }
        if (whereBuilder.sqlParts.isNotEmpty()) sb.append(" WHERE ").append(whereBuilder.sqlParts)
        limit?.let { sb.append(" LIMIT $it") }
        return Pair(sb.toString(), whereBuilder.params)
    }

    fun list(): List<T> {
        val (sql, params) = buildSql()
        return eOrm.executor.query(sql, params, clazz, eOrm.nameConverter)
    }

    fun firstOrNull(): T? {
        limit(1)
        return list().firstOrNull()
    }

    fun listMaps(): List<Map<String, Any?>> {
        val (sql, params) = buildSql()
        return eOrm.executor.queryMap(sql, params)
    }
}

class UpdateBuilder<T>(
    private val eOrm: EOrm,
    private val clazz: Class<T>
) {
    private val setClauses = ArrayList<String>()
    private val params = ArrayList<Any?>()
    private val whereBuilder = ConditionBuilder(eOrm.dbType.dialect, { null }, eOrm.useLiterals, eOrm.nameConverter)

    fun set(column: String, value: Any?): UpdateBuilder<T> {
        val wrappedCol = eOrm.dbType.dialect.wrapName(column)
        if (eOrm.useLiterals) {
            setClauses.add("$wrappedCol = ${eOrm.dbType.dialect.valueToSql(value)}")
        } else {
            setClauses.add("$wrappedCol = ?")
            params.add(value)
        }
        return this
    }

    fun set(prop: KProperty1<T, *>, value: Any?): UpdateBuilder<T> {
        val colName = MetaCache.resolveColumn(prop, eOrm.nameConverter)
        return set(colName, value)
    }

    fun where(clause: ClauseConsumer): UpdateBuilder<T> { clause.accept(whereBuilder); return this }
    fun where(clause: ConditionBuilder.() -> Unit): UpdateBuilder<T> = where(ClauseConsumer { b -> b.clause() })

    fun exec(): Int {
        if (setClauses.isEmpty()) return 0
        val meta = MetaCache.get(clazz, eOrm.nameConverter)
        val tableName = eOrm.dbType.dialect.wrapName(meta.tableName)

        val sb = StringBuilder("UPDATE $tableName SET ")
        sb.append(setClauses.joinToString(", "))

        if (whereBuilder.sqlParts.isNotEmpty()) {
            sb.append(" WHERE ").append(whereBuilder.sqlParts)
            params.addAll(whereBuilder.params)
        }

        return eOrm.executor.executeUpdate(sb.toString(), params)
    }
}

class DeleteBuilder<T>(
    private val eOrm: EOrm,
    private val clazz: Class<T>
) {
    private val whereBuilder = ConditionBuilder(eOrm.dbType.dialect, { null }, eOrm.useLiterals, eOrm.nameConverter)

    fun where(clause: ClauseConsumer): DeleteBuilder<T> { clause.accept(whereBuilder); return this }
    fun where(clause: ConditionBuilder.() -> Unit): DeleteBuilder<T> = where(ClauseConsumer { b -> b.clause() })

    fun exec(): Int {
        val meta = MetaCache.get(clazz, eOrm.nameConverter)
        val tableName = eOrm.dbType.dialect.wrapName(meta.tableName)

        val sb = StringBuilder("DELETE FROM $tableName")

        if (whereBuilder.sqlParts.isNotEmpty()) {
            sb.append(" WHERE ").append(whereBuilder.sqlParts)
        }

        return eOrm.executor.executeUpdate(sb.toString(), whereBuilder.params)
    }
}
