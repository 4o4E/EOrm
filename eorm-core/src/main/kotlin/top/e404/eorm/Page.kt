package top.e404.eorm

import java.io.Serializable

data class Page<T>(
    val current: Long,
    val size: Long,
    val total: Long,
    val records: List<T>
) : Serializable {
    val pages: Long get() = if (size == 0L) 0L else (total + size - 1) / size
    
    fun <R> map(transform: (T) -> R): Page<R> {
        return Page(current, size, total, records.map(transform))
    }
}
