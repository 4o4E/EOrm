package top.e404.eorm.meta

import top.e404.eorm.annotations.Column
import top.e404.eorm.annotations.Id
import top.e404.eorm.annotations.Index
import top.e404.eorm.annotations.Indexes
import top.e404.eorm.annotations.Table
import top.e404.eorm.annotations.Transient
import top.e404.eorm.generator.IdStrategy
import top.e404.eorm.mapping.NameConverter
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KProperty1
import kotlin.reflect.jvm.javaField

/**
 * 数据库列的元数据信息。
 * @param columnName 数据库列名
 * @param field 对应的 Java 反射字段
 * @param isId 是否为主键列
 * @param idStrategy 主键生成策略
 * @param length 列长度限制
 * @param nullable 是否允许为空
 * @param unique 是否创建单列唯一索引
 * @param sqlType 显式 SQL 类型
 * @param isJson 是否按 JSON 字段处理
 */
data class ColumnMeta(
    val columnName: String,
    val field: Field,
    val isId: Boolean,
    val idStrategy: IdStrategy,
    val length: Int,
    val nullable: Boolean,
    val unique: Boolean,
    val sqlType: String,
    val isJson: Boolean
)

/**
 * 数据库索引元数据。
 * @param name 索引名
 * @param columns 数据库列名列表
 * @param unique 是否为唯一索引
 */
data class IndexMeta(
    val name: String,
    val columns: List<String>,
    val unique: Boolean
)

/**
 * 数据库表的元数据信息。
 * @param tableName 数据库表名
 * @param columns 字段名到列名的映射
 * @param fieldMap 列名（小写）到 Field 的映射
 * @param propMap 字段名到 Field 的映射
 * @param columnMetas 所有列的元数据列表
 * @param columnMetaMap 列名（小写）到 ColumnMeta 的映射
 * @param indexMetas 索引元数据列表
 * @param idColumn 主键列名，无主键时为 null
 */
data class TableMeta(
    val tableName: String,
    val columns: Map<String, String>,
    val fieldMap: Map<String, Field>,
    val propMap: Map<String, Field>,
    val columnMetas: List<ColumnMeta>,
    val columnMetaMap: Map<String, ColumnMeta>,
    val indexMetas: List<IndexMeta>,
    val idColumn: String?
)

/**
 * 元数据缓存键，由实体类和命名转换器共同确定。
 * @param clazz 实体类
 * @param converter 命名转换器
 */
data class MetaKey(
    val clazz: Class<*>,
    val converter: NameConverter
)

/**
 * 表元数据缓存，通过反射解析实体类注解并缓存结果，避免重复解析。
 */
object MetaCache {
    private val cache = ConcurrentHashMap<MetaKey, TableMeta>()

    /**
     * 获取指定实体类的表元数据，不存在时自动解析并缓存。
     * @param clazz 实体类
     * @param converter 命名转换器
     * @return 表元数据
     */
    fun get(clazz: Class<*>, converter: NameConverter): TableMeta {
        return cache.computeIfAbsent(MetaKey(clazz, converter)) { key ->
            val cls = key.clazz
            val conv = key.converter
            val tableAnn = cls.getAnnotation(Table::class.java)
            val tableName = tableAnn?.name ?: conv.convert(cls.simpleName)
            val columns = mutableMapOf<String, String>()
            val fieldMap = mutableMapOf<String, Field>()
            val propMap = mutableMapOf<String, Field>()
            val columnMetas = ArrayList<ColumnMeta>()
            val columnMetaMap = mutableMapOf<String, ColumnMeta>()
            var idCol: String? = null
            var currentCls: Class<*>? = cls
            while (currentCls != null && currentCls != Any::class.java) {
                for (field in currentCls.declaredFields) {
                    if (field.isSynthetic || Modifier.isStatic(field.modifiers)) continue
                    if (field.isAnnotationPresent(Transient::class.java)) continue
                    field.isAccessible = true
                    val colAnn = field.getAnnotation(Column::class.java)
                    val colName = if (colAnn != null && colAnn.name.isNotEmpty()) colAnn.name else conv.convert(field.name)
                    val length = colAnn?.length ?: 255
                    val nullable = colAnn?.nullable ?: true
                    val unique = colAnn?.unique ?: false
                    val sqlType = colAnn?.sqlType ?: ""
                    val isJson = colAnn?.json ?: false
                    val idAnn = field.getAnnotation(Id::class.java)
                    val isId = idAnn != null
                    val strategy = idAnn?.strategy ?: IdStrategy.AUTO
                    columns[field.name] = colName
                    fieldMap[colName.lowercase()] = field
                    propMap[field.name] = field
                    val columnMeta = ColumnMeta(colName, field, isId, strategy, length, nullable, unique, sqlType, isJson)
                    columnMetas.add(columnMeta)
                    columnMetaMap[colName.lowercase()] = columnMeta
                    if (isId) idCol = colName
                }
                currentCls = currentCls.superclass
            }
            val indexMetas = buildIndexMetas(cls, tableName, columns, columnMetas)
            TableMeta(tableName, columns, fieldMap, propMap, columnMetas, columnMetaMap, indexMetas, idCol)
        }
    }

    private fun buildIndexMetas(
        cls: Class<*>,
        tableName: String,
        columns: Map<String, String>,
        columnMetas: List<ColumnMeta>
    ): List<IndexMeta> {
        val result = ArrayList<IndexMeta>()
        for (columnMeta in columnMetas) {
            if (columnMeta.unique) {
                val name = "uk_${tableName}_${columnMeta.columnName}"
                result.add(IndexMeta(name, listOf(columnMeta.columnName), true))
            }
        }

        val declaredIndexes = ArrayList<Index>()
        cls.getAnnotation(Index::class.java)?.let { declaredIndexes.add(it) }
        cls.getAnnotation(Indexes::class.java)?.value?.let { declaredIndexes.addAll(it) }
        declaredIndexes.forEach { index ->
            val resolvedColumns = index.columns.map { columns[it] ?: it }
            val prefix = if (index.unique) "uk" else "idx"
            val name = if (index.name.isNotEmpty()) index.name else "${prefix}_${tableName}_${resolvedColumns.joinToString("_")}"
            result.add(IndexMeta(name, resolvedColumns, index.unique))
        }

        return result.distinctBy { it.name.lowercase() }
    }

    /**
     * 解析 Kotlin 属性对应的数据库列名。
     * @param prop Kotlin 属性引用
     * @param converter 命名转换器
     * @return 数据库列名
     */
    fun resolveColumn(prop: KProperty1<*, *>, converter: NameConverter): String {
        val clazz = prop.javaField!!.declaringClass
        return get(clazz, converter).columns[prop.name] ?: prop.name
    }

    /**
     * 获取 Kotlin 属性所属的声明类。
     * @param prop Kotlin 属性引用
     * @return 声明该属性的类
     */
    fun resolveClass(prop: KProperty1<*, *>): Class<*> = prop.javaField!!.declaringClass
}
