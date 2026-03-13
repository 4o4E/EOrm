package top.e404.eorm.transaction

import kotlinx.coroutines.ThreadContextElement
import java.sql.Connection
import kotlin.coroutines.CoroutineContext

/**
 * 协程上下文元素，在协程恢复到线程时将事务连接写入 [TransactionManager] 的 ThreadLocal，
 * 挂起离开线程时恢复旧值。
 *
 * 这样 SqlExecutor 通过 ThreadLocal 获取连接的逻辑无需任何修改，对协程完全透明。
 */
class CoroutineTransaction(
    private val transactionManager: TransactionManager,
    internal val connection: Connection
) : ThreadContextElement<Connection?> {

    companion object Key : CoroutineContext.Key<CoroutineTransaction>

    override val key: CoroutineContext.Key<CoroutineTransaction> = Key

    /**
     * 协程恢复到线程时：保存旧值，写入事务连接。
     */
    override fun updateThreadContext(context: CoroutineContext): Connection? {
        val old = transactionManager.threadLocalConnection.get()
        transactionManager.threadLocalConnection.set(connection)
        return old
    }

    /**
     * 协程挂起离开线程时：恢复旧值。
     */
    override fun restoreThreadContext(context: CoroutineContext, oldState: Connection?) {
        transactionManager.threadLocalConnection.set(oldState)
    }
}
