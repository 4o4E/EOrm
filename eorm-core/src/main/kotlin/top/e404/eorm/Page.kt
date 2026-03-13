package top.e404.eorm

import java.io.Serializable

/**
 * 分页查询结果封装。
 * @param T 记录类型
 * @param current 当前页码（从 1 开始）
 * @param size 每页记录数
 * @param total 总记录数
 * @param records 当前页的记录列表
 */
data class Page<T>(
    val current: Long,
    val size: Long,
    val total: Long,
    val records: List<T>
) : Serializable {
    /** 总页数，根据 [total] 和 [size] 计算得出。 */
    val pages: Long get() = if (size == 0L) 0L else (total + size - 1) / size
    
    /**
     * 将记录列表映射为另一种类型，返回新的 [Page] 实例。
     * @param R 目标类型
     * @param transform 转换函数
     */
    fun <R> map(transform: (T) -> R): Page<R> {
        return Page(current, size, total, records.map(transform))
    }
}
