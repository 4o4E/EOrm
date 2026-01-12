package top.e404.eorm.mapping

interface NameConverter { fun convert(name: String): String }
object CamelToSnakeConverter : NameConverter {
    override fun convert(name: String) = name.replace(Regex("([a-z])([A-Z]+)"), "$1_$2").lowercase()
    override fun equals(other: Any?): Boolean = other is CamelToSnakeConverter
    override fun hashCode(): Int = this::class.java.hashCode()
}
