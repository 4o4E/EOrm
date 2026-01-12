package top.e404.eorm.filler

import top.e404.eorm.mapping.NameConverter
import top.e404.eorm.meta.MetaCache

interface DataFiller {
    fun insertFill(entity: Any, converter: NameConverter)
    fun setFieldValByName(entity: Any, fieldName: String, value: Any?, converter: NameConverter) {
        val meta = MetaCache.get(entity.javaClass, converter)
        val field = meta.propMap[fieldName]
        if (field != null) {
            try { field.set(entity, value) } catch (e: Exception) { e.printStackTrace() }
        }
    }
}
class NoOpDataFiller : DataFiller {
    override fun insertFill(entity: Any, converter: NameConverter) {}
}
