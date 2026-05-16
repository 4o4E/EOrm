package top.e404.eorm.filler

import top.e404.eorm.mapping.NameConverter
import top.e404.eorm.meta.MetaCache

/**
 * 数据填充器接口，用于在插入操作前自动填充实体字段（如创建时间、创建人等）。
 */
interface DataFiller {
    /**
     * 在插入前对实体进行字段填充。
     * @param entity 待填充的实体对象
     * @param converter 命名转换器
     */
    fun insertFill(entity: Any, converter: NameConverter)

    /**
     * 通过字段名设置实体的属性值。
     * @param entity 目标实体对象
     * @param fieldName Java 字段名
     * @param value 要设置的值，可为 null
     * @param converter 命名转换器
     */
    fun setFieldValByName(entity: Any, fieldName: String, value: Any?, converter: NameConverter) {
        val meta = MetaCache.get(entity.javaClass, converter)
        val field = meta.propMap[fieldName]
        if (field != null) {
            field.set(entity, value)
        }
    }
}

/**
 * 空操作数据填充器，不执行任何填充逻辑。
 */
class NoOpDataFiller : DataFiller {
    override fun insertFill(entity: Any, converter: NameConverter) {}
}
