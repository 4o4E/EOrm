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
 */
@Target(AnnotationTarget.FIELD) annotation class Column(val name: String = "", val length: Int = 255, val nullable: Boolean = true)

/**
 * 标注字段为主键。
 * @param strategy 主键生成策略，默认 [IdStrategy.AUTO]
 */
@Target(AnnotationTarget.FIELD) annotation class Id(val strategy: IdStrategy = IdStrategy.AUTO)

/**
 * 标注字段不参与数据库映射，将被 ORM 忽略。
 */
@Target(AnnotationTarget.FIELD) annotation class Transient
