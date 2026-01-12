package top.e404.eorm.meta

import top.e404.eorm.annotations.Column
import top.e404.eorm.annotations.Id
import top.e404.eorm.annotations.Table
import top.e404.eorm.annotations.Transient
import top.e404.eorm.generator.IdStrategy
import top.e404.eorm.mapping.NameConverter
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KProperty1
import kotlin.reflect.jvm.javaField

data class ColumnMeta(val columnName: String, val field: Field, val isId: Boolean, val idStrategy: IdStrategy, val length: Int, val nullable: Boolean)
data class TableMeta(val tableName: String, val columns: Map<String, String>, val fieldMap: Map<String, Field>, val propMap: Map<String, Field>, val columnMetas: List<ColumnMeta>, val idColumn: String?)
data class MetaKey(val clazz: Class<*>, val converter: NameConverter)

object MetaCache {
    private val cache = ConcurrentHashMap<MetaKey, TableMeta>()
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
            var idCol: String? = null
            var currentCls: Class<*>? = cls
            while (currentCls != null && currentCls != Any::class.java) {
                for (field in currentCls.declaredFields) {
                    if (field.isAnnotationPresent(Transient::class.java)) continue
                    field.isAccessible = true
                    val colAnn = field.getAnnotation(Column::class.java)
                    val colName = if (colAnn != null && colAnn.name.isNotEmpty()) colAnn.name else conv.convert(field.name)
                    val length = colAnn?.length ?: 255
                    val nullable = colAnn?.nullable ?: true
                    val idAnn = field.getAnnotation(Id::class.java)
                    val isId = idAnn != null
                    val strategy = idAnn?.strategy ?: IdStrategy.AUTO
                    columns[field.name] = colName
                    fieldMap[colName.lowercase()] = field
                    propMap[field.name] = field
                    columnMetas.add(ColumnMeta(colName, field, isId, strategy, length, nullable))
                    if (isId) idCol = colName
                }
                currentCls = currentCls.superclass
            }
            TableMeta(tableName, columns, fieldMap, propMap, columnMetas, idCol)
        }
    }
    fun resolveColumn(prop: KProperty1<*, *>, converter: NameConverter): String {
        val clazz = prop.javaField!!.declaringClass
        return get(clazz, converter).columns[prop.name] ?: prop.name
    }
    fun resolveClass(prop: KProperty1<*, *>): Class<*> = prop.javaField!!.declaringClass
}
