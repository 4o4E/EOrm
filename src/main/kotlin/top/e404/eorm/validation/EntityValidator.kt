package top.e404.eorm.validation

import top.e404.eorm.exception.ValidationException
import top.e404.eorm.generator.IdStrategy
import top.e404.eorm.meta.TableMeta

object EntityValidator {
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
