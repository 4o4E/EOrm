package top.e404.eorm.annotations

import top.e404.eorm.generator.IdStrategy

@Target(AnnotationTarget.CLASS) annotation class Table(val name: String)
@Target(AnnotationTarget.FIELD) annotation class Column(val name: String = "", val length: Int = 255, val nullable: Boolean = true)
@Target(AnnotationTarget.FIELD) annotation class Id(val strategy: IdStrategy = IdStrategy.AUTO)
@Target(AnnotationTarget.FIELD) annotation class Transient
