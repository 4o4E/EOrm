package top.e404.eorm.dsl

import top.e404.eorm.EOrm
import top.e404.eorm.dialect.SqlDialect
import top.e404.eorm.mapping.NameConverter
import top.e404.eorm.meta.MetaCache
import top.e404.eorm.Page
import kotlin.reflect.KProperty1

/**
 * SQL 条件构建器，用于构建 WHERE/ON 子句中的条件表达式。
 *
 * 支持基础比较（eq/gt/lt 等）、嵌套条件（nest/and/or）、子查询条件（IN/EXISTS 等），
 * 并自动处理条件之间的 AND/OR 连接符。
 *
 * @param dialect SQL 方言
 * @param aliasResolver 类到表别名的解析器
 * @param useLiterals 是否将参数直接拼接到 SQL 中
 * @param nameConverter 属性名到列名的转换器
 * @param outerAliasMap 外层查询的别名映射，用于子查询引用外层表列
 */
class ConditionBuilder(
    private val dialect: SqlDialect,
    private val aliasResolver: (Class<*>) -> String?,
    private val useLiterals: Boolean,
    private val nameConverter: NameConverter,
    internal val outerAliasMap: Map<Class<*>, String>? = null
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

    // --- 子查询条件 ---

    /**
     * column IN (子查询)
     */
    fun inSub(column: String, subQuery: SubQuery<*>) = addSubCond(column, "IN", subQuery)
    fun inSub(prop: KProperty1<*, *>, subQuery: SubQuery<*>) = inSub(resolveAndWrap(prop), subQuery)

    /**
     * column NOT IN (子查询)
     */
    fun notInSub(column: String, subQuery: SubQuery<*>) = addSubCond(column, "NOT IN", subQuery)
    fun notInSub(prop: KProperty1<*, *>, subQuery: SubQuery<*>) = notInSub(resolveAndWrap(prop), subQuery)

    /**
     * column = (子查询)
     */
    fun eqSub(column: String, subQuery: SubQuery<*>) = addSubCond(column, "=", subQuery)
    fun eqSub(prop: KProperty1<*, *>, subQuery: SubQuery<*>) = eqSub(resolveAndWrap(prop), subQuery)

    /**
     * column > (子查询)
     */
    fun gtSub(column: String, subQuery: SubQuery<*>) = addSubCond(column, ">", subQuery)
    fun gtSub(prop: KProperty1<*, *>, subQuery: SubQuery<*>) = gtSub(resolveAndWrap(prop), subQuery)

    /**
     * column >= (子查询)
     */
    fun geSub(column: String, subQuery: SubQuery<*>) = addSubCond(column, ">=", subQuery)
    fun geSub(prop: KProperty1<*, *>, subQuery: SubQuery<*>) = geSub(resolveAndWrap(prop), subQuery)

    /**
     * column < (子查询)
     */
    fun ltSub(column: String, subQuery: SubQuery<*>) = addSubCond(column, "<", subQuery)
    fun ltSub(prop: KProperty1<*, *>, subQuery: SubQuery<*>) = ltSub(resolveAndWrap(prop), subQuery)

    /**
     * column <= (子查询)
     */
    fun leSub(column: String, subQuery: SubQuery<*>) = addSubCond(column, "<=", subQuery)
    fun leSub(prop: KProperty1<*, *>, subQuery: SubQuery<*>) = leSub(resolveAndWrap(prop), subQuery)

    /**
     * EXISTS (子查询)
     * 接受已构建好的子查询对象。如需在子查询中引用外层表列，请使用 lambda 重载。
     */
    fun exists(subQuery: SubQuery<*>) {
        if (shouldAppendConnector()) and()
        if (outerAliasMap != null) subQuery.withOuterAliases(outerAliasMap)
        val (sql, subParams) = subQuery.build()
        sqlParts.append("EXISTS ($sql)")
        params.addAll(subParams)
    }

    /**
     * EXISTS (子查询) - lambda 重载
     * 在 lambda 执行前自动注入外层别名，使子查询能引用外层表的列。
     *
     * 示例:
     * ```
     * .where {
     *     exists(db.subQuery<UserRole>("ur")) { sub ->
     *         sub.select("1").where { eqCol(UserRole::userId, User::id) }
     *     }
     * }
     * ```
     */
    fun <T> exists(subQuery: SubQuery<T>, block: (SubQuery<T>) -> Unit) {
        if (shouldAppendConnector()) and()
        if (outerAliasMap != null) subQuery.withOuterAliases(outerAliasMap)
        block(subQuery)
        val (sql, subParams) = subQuery.build()
        sqlParts.append("EXISTS ($sql)")
        params.addAll(subParams)
    }

    /**
     * NOT EXISTS (子查询)
     * 接受已构建好的子查询对象。如需在子查询中引用外层表列，请使用 lambda 重载。
     */
    fun notExists(subQuery: SubQuery<*>) {
        if (shouldAppendConnector()) and()
        if (outerAliasMap != null) subQuery.withOuterAliases(outerAliasMap)
        val (sql, subParams) = subQuery.build()
        sqlParts.append("NOT EXISTS ($sql)")
        params.addAll(subParams)
    }

    /**
     * NOT EXISTS (子查询) - lambda 重载
     * 在 lambda 执行前自动注入外层别名，使子查询能引用外层表的列。
     */
    fun <T> notExists(subQuery: SubQuery<T>, block: (SubQuery<T>) -> Unit) {
        if (shouldAppendConnector()) and()
        if (outerAliasMap != null) subQuery.withOuterAliases(outerAliasMap)
        block(subQuery)
        val (sql, subParams) = subQuery.build()
        sqlParts.append("NOT EXISTS ($sql)")
        params.addAll(subParams)
    }

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

    private fun addSubCond(col: String, op: String, subQuery: SubQuery<*>) {
        if (shouldAppendConnector()) and()
        if (outerAliasMap != null) subQuery.withOuterAliases(outerAliasMap)
        val (sql, subParams) = subQuery.build()
        sqlParts.append("$col $op ($sql)")
        params.addAll(subParams)
    }

    private fun resolveAndWrap(prop: KProperty1<*, *>): String {
        val colName = MetaCache.resolveColumn(prop, nameConverter)
        val wrappedCol = dialect.wrapName(colName)
        val alias = aliasResolver(MetaCache.resolveClass(prop))
        return if (alias != null) "$alias.$wrappedCol" else wrappedCol
    }
}

    fun interface ClauseConsumer { fun accept(builder: ConditionBuilder) }

    private sealed interface Selection {
        fun toSql(eOrm: EOrm, aliasResolver: (Class<*>) -> String?): String
    }

    private class StringSelection(val col: String) : Selection {
        override fun toSql(eOrm: EOrm, aliasResolver: (Class<*>) -> String?) = col
    }

    private class PropertySelection(val prop: KProperty1<*, *>) : Selection {
        override fun toSql(eOrm: EOrm, aliasResolver: (Class<*>) -> String?): String {
            val colName = MetaCache.resolveColumn(prop, eOrm.nameConverter)
            val wrappedCol = eOrm.dialect.wrapName(colName)
            val ownerAlias = aliasResolver(MetaCache.resolveClass(prop))
            // Explicitly use AS to guarantee column label matches property name, avoiding "u.name" or UPPERCASE issues from drivers
            return if (ownerAlias != null) "$ownerAlias.$wrappedCol AS $wrappedCol" else wrappedCol
        }
    }

/**
 * DSL 查询构建器，用于构建 SELECT 查询语句。
 *
 * 支持列选择、JOIN、WHERE 条件、LIMIT 和分页查询，查询结果可映射为实体对象、其他类型或 Map。
 *
 * @param T 主查询实体类型
 * @param eOrm EOrm 实例
 * @param clazz 实体类的 Class 对象
 * @param alias 主表别名
 */
    class Query<T>(
        private val eOrm: EOrm,
        private val clazz: Class<T>,
        private val alias: String
    ) {
        private val selections = ArrayList<Selection>()
        private val joins = ArrayList<String>()
        private val joinParams = ArrayList<Any?>()
        private val aliasMap = mutableMapOf<Class<*>, String>()
        private val aliasResolver: (Class<*>) -> String? = { cls -> aliasMap[cls] }
        private val whereBuilder = ConditionBuilder(eOrm.dialect, aliasResolver, eOrm.useLiterals, eOrm.nameConverter, aliasMap)
        private var limit: Int? = null

        init { aliasMap[clazz] = alias }

        /**
         * 指定查询的列（字符串形式）。
         *
         * @param cols 列名或 SQL 表达式
         * @return 当前查询构建器
         */
        fun select(vararg cols: String): Query<T> {
            cols.forEach { selections.add(StringSelection(it)) }
            return this
        }
        
        /**
         * 指定查询的列（属性引用形式），自动解析列名和表别名。
         *
         * @param props 实体属性引用
         * @return 当前查询构建器
         */
        fun select(vararg props: KProperty1<*, *>): Query<T> {
            props.forEach { selections.add(PropertySelection(it)) }
            return this
        }

        /**
         * 添加 JOIN 子句。
         *
         * @param target 要连接的实体类
         * @param alias 连接表的别名
         * @param type 连接类型（INNER/LEFT/RIGHT）
         * @param onClause ON 条件构建回调
         * @return 当前查询构建器
         */
        fun join(target: Class<*>, alias: String, type: String = "INNER", onClause: ClauseConsumer): Query<T> {
            aliasMap[target] = alias
            val meta = MetaCache.get(target, eOrm.nameConverter)
            val tableName = eOrm.dialect.wrapName(meta.tableName)
            val builder = ConditionBuilder(eOrm.dialect, aliasResolver, eOrm.useLiterals, eOrm.nameConverter)
            onClause.accept(builder)
            joins.add("$type JOIN $tableName $alias ON ${builder.sqlParts}")
            
            this.joinParams.addAll(builder.params)
            
            return this
        }

        /** 添加 LEFT JOIN 子句。 */
        inline fun <reified J> leftJoin(alias: String, noinline onClause: ConditionBuilder.() -> Unit): Query<T> = join(J::class.java, alias, "LEFT") { b -> b.onClause() }

        /** 添加 INNER JOIN 子句。 */
        inline fun <reified J> innerJoin(alias: String, noinline onClause: ConditionBuilder.() -> Unit): Query<T> = join(J::class.java, alias, "INNER") { b -> b.onClause() }

        /** 添加 RIGHT JOIN 子句。 */
        inline fun <reified J> rightJoin(alias: String, noinline onClause: ConditionBuilder.() -> Unit): Query<T> = join(J::class.java, alias, "RIGHT") { b -> b.onClause() }

        /**
         * 设置 WHERE 条件（Java 友好的函数式接口重载）。
         *
         * @param clause 条件构建回调
         * @return 当前查询构建器
         */
        fun where(clause: ClauseConsumer): Query<T> { clause.accept(whereBuilder); return this }

        /**
         * 设置 WHERE 条件（Kotlin DSL 风格）。
         *
         * @param clause 条件构建 lambda
         * @return 当前查询构建器
         */
        fun where(clause: ConditionBuilder.() -> Unit): Query<T> = where(ClauseConsumer { b -> b.clause() })

        /**
         * 限制查询返回的最大行数。
         *
         * @param n 最大行数
         * @return 当前查询构建器
         */
        fun limit(n: Int): Query<T> {
            this.limit = n
            return this
        }

        /**
         * 分页查询，返回包含总记录数和当前页数据的 [Page] 对象。
         *
         * @param pageNumber 页码（从 1 开始）
         * @param pageSize 每页记录数
         * @param searchCount 是否查询总记录数，为 false 时总数返回 -1
         * @return 分页结果
         */
        fun page(pageNumber: Long, pageSize: Long, searchCount: Boolean = true): Page<T> {
            val offset = (pageNumber - 1) * pageSize
            var total: Long = 0

            if (searchCount) {
                // 1. Build Count SQL
                val meta = MetaCache.get(clazz, eOrm.nameConverter)
                val tableName = eOrm.dialect.wrapName(meta.tableName)
                val tableStr = "$tableName $alias"

                val countSqlSb = StringBuilder("SELECT COUNT(*) FROM $tableStr")
                joins.forEach { countSqlSb.append(" ").append(it) }
                if (whereBuilder.sqlParts.isNotEmpty()) countSqlSb.append(" WHERE ").append(whereBuilder.sqlParts)

                val countParams = ArrayList<Any?>()
                countParams.addAll(joinParams)
                countParams.addAll(whereBuilder.params)

                val countResult = eOrm.executor.queryMap(countSqlSb.toString(), countParams)
                val firstVal = countResult.firstOrNull()?.values?.firstOrNull()
                total = when (firstVal) {
                    is Number -> firstVal.toLong()
                    else -> firstVal?.toString()?.toLongOrNull() ?: 0L
                }

                if (total == 0L) {
                    return Page(pageNumber, pageSize, 0, emptyList())
                }
            } else {
                 // If skipping count, we don't know the total.
                 // We could set it to -1 to indicate unknown, or 0.
                 // For now, let's leave it as 0 or maybe -1 if Page allows it.
                 // Conventionally, 0 might be confusing if there ARE records.
                 // Let's use -1 to signify "unknown", but check if it breaks anything.
                 total = -1
            }

            // 2. Build Page SQL
            val (originalSql, originalParams) = buildSelectSql(applyLimit = false)
            val pageSql = eOrm.dialect.buildPaginationSql(originalSql, offset, pageSize)

            val records = eOrm.executor.query(pageSql, originalParams, clazz, eOrm.nameConverter)

            return Page(pageNumber, pageSize, total, records)
        }

        private fun buildSelectSql(applyLimit: Boolean): Pair<String, List<Any?>> {
            val meta = MetaCache.get(clazz, eOrm.nameConverter)
            val tableName = eOrm.dialect.wrapName(meta.tableName)
            val tableStr = "$tableName $alias"
            
            val cols = if (selections.isEmpty()) "*" else selections.joinToString(", ") { it.toSql(eOrm, aliasResolver) }
            
            val sb = StringBuilder("SELECT $cols FROM $tableStr")
            joins.forEach { sb.append(" ").append(it) }
            if (whereBuilder.sqlParts.isNotEmpty()) sb.append(" WHERE ").append(whereBuilder.sqlParts)
            
            if (applyLimit && limit != null) {
                sb.append(" LIMIT $limit")
            }
            
            val finalParams = ArrayList<Any?>()
            finalParams.addAll(joinParams)
            finalParams.addAll(whereBuilder.params)
            
            return Pair(sb.toString(), finalParams)
        }

        private fun buildSql(): Pair<String, List<Any?>> = buildSelectSql(true)

        /**
         * 执行查询并将结果映射为实体列表。
         *
         * @return 实体对象列表
         */
        fun list(): List<T> {
            val (sql, params) = buildSql()
            return eOrm.executor.query(sql, params, clazz, eOrm.nameConverter)
        }

        /**
         * 执行查询并将结果映射为指定类型的列表。
         *
         * @param R 目标类型
         * @param resultType 目标类型的 Class 对象
         * @return 指定类型的对象列表
         */
        fun <R> list(resultType: Class<R>): List<R> {
            val (sql, params) = buildSql()
            return eOrm.executor.query(sql, params, resultType, eOrm.nameConverter)
        }

        /** 执行查询并将结果映射为指定类型的列表（reified 泛型重载）。 */
        inline fun <reified R> listAs(): List<R> = list(R::class.java)

        /**
         * 执行查询并返回第一条结果，若无结果则返回 null。
         * 内部自动添加 LIMIT 1 优化。
         *
         * @return 第一条实体对象，或 null
         */
        fun firstOrNull(): T? {
            limit(1)
            return list().firstOrNull()
        }

        /**
         * 执行查询并将结果以 Map 列表形式返回，每个 Map 的 key 为列名，value 为列值。
         *
         * @return Map 列表
         */
        fun listMaps(): List<Map<String, Any?>> {
            val (sql, params) = buildSql()
            return eOrm.executor.queryMap(sql, params)
        }
    }

/**
 * 子查询构建器，用于构建可嵌套的子查询 SQL 片段。
 * 不直接执行 SQL，而是生成 SQL 字符串和参数列表供外层查询使用。
 *
 * 支持 select/join/where，可用于 IN/EXISTS/标量比较等场景。
 */
class SubQuery<T>(
    private val eOrm: EOrm,
    private val clazz: Class<T>,
    private val alias: String
) {
    private val selections = ArrayList<Selection>()
    private val joins = ArrayList<String>()
    private val joinParams = ArrayList<Any?>()
    internal val aliasMap = mutableMapOf<Class<*>, String>()
    private val aliasResolver: (Class<*>) -> String? = { cls -> aliasMap[cls] }
    private val whereBuilder = ConditionBuilder(eOrm.dialect, aliasResolver, eOrm.useLiterals, eOrm.nameConverter, aliasMap)

    init { aliasMap[clazz] = alias }

    /**
     * 注入外层查询的别名映射，使子查询的 WHERE 条件能引用外层表的列。
     * 外层别名不会覆盖子查询自身已有的别名。
     */
    fun withOuterAliases(outerAliasMap: Map<Class<*>, String>): SubQuery<T> {
        for ((cls, al) in outerAliasMap) {
            aliasMap.putIfAbsent(cls, al)
        }
        return this
    }

    /** 指定子查询的列（字符串形式）。 */
    fun select(vararg cols: String): SubQuery<T> {
        cols.forEach { selections.add(StringSelection(it)) }
        return this
    }

    /** 指定子查询的列（属性引用形式）。 */
    fun select(vararg props: KProperty1<*, *>): SubQuery<T> {
        props.forEach { selections.add(PropertySelection(it)) }
        return this
    }

    /** 添加 JOIN 子句到子查询。 */
    fun join(target: Class<*>, alias: String, type: String = "INNER", onClause: ClauseConsumer): SubQuery<T> {
        aliasMap[target] = alias
        val meta = MetaCache.get(target, eOrm.nameConverter)
        val tableName = eOrm.dialect.wrapName(meta.tableName)
        val builder = ConditionBuilder(eOrm.dialect, aliasResolver, eOrm.useLiterals, eOrm.nameConverter)
        onClause.accept(builder)
        joins.add("$type JOIN $tableName $alias ON ${builder.sqlParts}")
        this.joinParams.addAll(builder.params)
        return this
    }

    /** 添加 LEFT JOIN 子句到子查询。 */
    inline fun <reified J> leftJoin(alias: String, noinline onClause: ConditionBuilder.() -> Unit): SubQuery<T> = join(J::class.java, alias, "LEFT") { b -> b.onClause() }

    /** 添加 INNER JOIN 子句到子查询。 */
    inline fun <reified J> innerJoin(alias: String, noinline onClause: ConditionBuilder.() -> Unit): SubQuery<T> = join(J::class.java, alias, "INNER") { b -> b.onClause() }

    /** 添加 RIGHT JOIN 子句到子查询。 */
    inline fun <reified J> rightJoin(alias: String, noinline onClause: ConditionBuilder.() -> Unit): SubQuery<T> = join(J::class.java, alias, "RIGHT") { b -> b.onClause() }

    /** 设置子查询的 WHERE 条件。 */
    fun where(clause: ClauseConsumer): SubQuery<T> { clause.accept(whereBuilder); return this }

    /** 设置子查询的 WHERE 条件（Kotlin DSL 风格）。 */
    fun where(clause: ConditionBuilder.() -> Unit): SubQuery<T> = where(ClauseConsumer { b -> b.clause() })

    /**
     * 构建子查询 SQL 和参数列表。
     * @return Pair(sql, params)
     */
    fun build(): Pair<String, List<Any?>> {
        val meta = MetaCache.get(clazz, eOrm.nameConverter)
        val tableName = eOrm.dialect.wrapName(meta.tableName)
        val tableStr = "$tableName $alias"

        val cols = if (selections.isEmpty()) "*" else selections.joinToString(", ") { it.toSql(eOrm, aliasResolver) }

        val sb = StringBuilder("SELECT $cols FROM $tableStr")
        joins.forEach { sb.append(" ").append(it) }
        if (whereBuilder.sqlParts.isNotEmpty()) sb.append(" WHERE ").append(whereBuilder.sqlParts)

        val finalParams = ArrayList<Any?>()
        finalParams.addAll(joinParams)
        finalParams.addAll(whereBuilder.params)

        return Pair(sb.toString(), finalParams)
    }
}

/**
 * DSL 更新构建器，用于构建 UPDATE 语句。
 *
 * 通过 [set] 设置要更新的列和值，通过 [where] 设置条件，最后调用 [exec] 执行。
 *
 * @param T 实体类型
 */
class UpdateBuilder<T>(
    private val eOrm: EOrm,
    private val clazz: Class<T>
) {
    private val setClauses = ArrayList<String>()
    private val params = ArrayList<Any?>()
    private val whereBuilder = ConditionBuilder(eOrm.dialect, { null }, eOrm.useLiterals, eOrm.nameConverter)

    /**
     * 设置要更新的列和值（字符串列名形式）。
     *
     * @param column 列名
     * @param value 新值
     * @return 当前更新构建器
     */
    fun set(column: String, value: Any?): UpdateBuilder<T> {
        val wrappedCol = eOrm.dialect.wrapName(column)
        if (eOrm.useLiterals) {
            setClauses.add("$wrappedCol = ${eOrm.dialect.valueToSql(value)}")
        } else {
            setClauses.add("$wrappedCol = ?")
            params.add(value)
        }
        return this
    }

    /**
     * 设置要更新的列和值（属性引用形式），自动解析列名。
     *
     * @param prop 实体属性引用
     * @param value 新值
     * @return 当前更新构建器
     */
    fun set(prop: KProperty1<T, *>, value: Any?): UpdateBuilder<T> {
        val colName = MetaCache.resolveColumn(prop, eOrm.nameConverter)
        return set(colName, value)
    }

    /** 设置 WHERE 条件（Java 友好的函数式接口重载）。 */
    fun where(clause: ClauseConsumer): UpdateBuilder<T> { clause.accept(whereBuilder); return this }

    /** 设置 WHERE 条件（Kotlin DSL 风格）。 */
    fun where(clause: ConditionBuilder.() -> Unit): UpdateBuilder<T> = where(ClauseConsumer { b -> b.clause() })

    /**
     * 执行 UPDATE 语句。
     *
     * @return 受影响的行数，若未设置任何 SET 子句则返回 0
     */
    fun exec(): Int {
        if (setClauses.isEmpty()) return 0
        val meta = MetaCache.get(clazz, eOrm.nameConverter)
        val tableName = eOrm.dialect.wrapName(meta.tableName)

        val sb = StringBuilder("UPDATE $tableName SET ")
        sb.append(setClauses.joinToString(", "))

        if (whereBuilder.sqlParts.isNotEmpty()) {
            sb.append(" WHERE ").append(whereBuilder.sqlParts)
            params.addAll(whereBuilder.params)
        }

        return eOrm.executor.executeUpdate(sb.toString(), params)
    }
}

/**
 * DSL 删除构建器，用于构建 DELETE 语句。
 *
 * 通过 [where] 设置条件，调用 [exec] 执行删除。
 *
 * @param T 实体类型
 */
class DeleteBuilder<T>(
    private val eOrm: EOrm,
    private val clazz: Class<T>
) {
    private val whereBuilder = ConditionBuilder(eOrm.dialect, { null }, eOrm.useLiterals, eOrm.nameConverter)

    /** 设置 WHERE 条件（Java 友好的函数式接口重载）。 */
    fun where(clause: ClauseConsumer): DeleteBuilder<T> { clause.accept(whereBuilder); return this }

    /** 设置 WHERE 条件（Kotlin DSL 风格）。 */
    fun where(clause: ConditionBuilder.() -> Unit): DeleteBuilder<T> = where(ClauseConsumer { b -> b.clause() })

    /**
     * 执行 DELETE 语句。
     *
     * @return 受影响的行数
     */
    fun exec(): Int {
        val meta = MetaCache.get(clazz, eOrm.nameConverter)
        val tableName = eOrm.dialect.wrapName(meta.tableName)

        val sb = StringBuilder("DELETE FROM $tableName")

        if (whereBuilder.sqlParts.isNotEmpty()) {
            sb.append(" WHERE ").append(whereBuilder.sqlParts)
        }

        return eOrm.executor.executeUpdate(sb.toString(), whereBuilder.params)
    }
}
