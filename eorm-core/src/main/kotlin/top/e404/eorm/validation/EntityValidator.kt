package top.e404.eorm.validation

import top.e404.eorm.exception.ValidationException
import top.e404.eorm.generator.IdStrategy
import top.e404.eorm.meta.TableMeta

/**
 * 实体校验器，在持久化前对实体字段进行约束检查。
 */
object EntityValidator {
    /**
     * 校验实体对象是否满足元数据定义的约束。
     *
     * 检查规则：
     * - 非空字段不能为 null（自增主键除外）
     * - 字符串字段长度不能超过定义的最大长度
     *
     * @param entity 待校验的实体对象
     * @param meta 表元数据
     * @throws ValidationException 当校验不通过时抛出
     */
    fun validate(entity: Any, meta: TableMeta) {
        for (colMeta in meta.columnMetas) {
            val value = colMeta.field.get(entity)
            if (!colMeta.nullable && value == null) {
                if (colMeta.isId && colMeta.idStrategy == IdStrategy.AUTO) { /* Skip */ }
                else { throw ValidationException("Field '${colMeta.field.name}' in ${meta.tableName} cannot be null.") }
            }
            if (value is String && value.length > colMeta.length) {
                throw ValidationException("Field '${colMeta.field.name}' length (${value.length}) exceeds limit (${colMeta.length}).")
            }
        }
    }
}
