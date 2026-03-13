package top.e404.eorm.mapping

/**
 * 名称转换器，用于在 Java/Kotlin 字段名与数据库列名之间进行转换。
 */
interface NameConverter {
    /**
     * 将给定名称转换为目标格式。
     * @param name 原始名称
     * @return 转换后的名称
     */
    fun convert(name: String): String
}

/**
 * 驼峰命名转蛇形命名的转换器（如 `userName` -> `user_name`）。
 */
object CamelToSnakeConverter : NameConverter {
    override fun convert(name: String) = name.replace(Regex("([a-z])([A-Z]+)"), "$1_$2").lowercase()
    override fun equals(other: Any?): Boolean = other is CamelToSnakeConverter
    override fun hashCode(): Int = this::class.java.hashCode()
}
