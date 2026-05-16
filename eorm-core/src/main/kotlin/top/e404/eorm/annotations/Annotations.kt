package top.e404.eorm.annotations

import top.e404.eorm.generator.IdStrategy

/**
 * 标注实体类对应的数据库表名。
 * @param name 数据库表名
 */
@Target(AnnotationTarget.CLASS) annotation class Table(val name: String)

/**
 * 标注字段对应的数据库列信息。
 * @param name 列名，为空时使用命名转换器自动生成
 * @param length 列长度，默认 255
 * @param nullable 是否允许为空，默认 true
 * @param unique 是否创建单列唯一索引
 * @param sqlType 显式 SQL 类型，为空时由方言按字段类型推断
 * @param json 是否按 JSON 字段处理，启用后会自动序列化/反序列化字段值
 */
@Target(AnnotationTarget.FIELD)
annotation class Column(
    val name: String = "",
    val length: Int = 255,
    val nullable: Boolean = true,
    val unique: Boolean = false,
    val sqlType: String = "",
    val json: Boolean = false
)

/**
 * 标注字段为主键。
 * @param strategy 主键生成策略，默认 [IdStrategy.AUTO]
 */
@Target(AnnotationTarget.FIELD) annotation class Id(val strategy: IdStrategy = IdStrategy.AUTO)

/**
 * 声明表级索引。
 * @param name 索引名，为空时自动生成
 * @param columns 索引列。可填写实体字段名或数据库列名
 * @param unique 是否为唯一索引
 */
@Target(AnnotationTarget.CLASS)
annotation class Index(val name: String = "", val columns: Array<String>, val unique: Boolean = false)

/**
 * 声明多个表级索引。
 */
@Target(AnnotationTarget.CLASS)
annotation class Indexes(vararg val value: Index)

/**
 * 标注字段不参与数据库映射，将被 ORM 忽略。
 */
@Target(AnnotationTarget.FIELD) annotation class Transient
