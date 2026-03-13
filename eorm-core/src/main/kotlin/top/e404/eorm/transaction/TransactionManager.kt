package top.e404.eorm.transaction

import java.sql.Connection
import javax.sql.DataSource

/**
 * 事务管理器，通过 ThreadLocal 绑定当前线程的事务连接。
 * 协程场景下配合 [CoroutineTransaction] 使用，在协程恢复/挂起时自动同步 ThreadLocal。
 */
class TransactionManager(private val dataSource: DataSource) {

    internal val threadLocalConnection = ThreadLocal<Connection?>()

    /**
     * 获取连接。事务中返回绑定的连接，否则从 DataSource 新建。
     */
    fun getConnection(): Connection {
        return threadLocalConnection.get() ?: dataSource.connection
    }

    /**
     * 当前线程是否在事务中。
     */
    fun isInTransaction(): Boolean = threadLocalConnection.get() != null

    /**
     * 开启事务，将连接绑定到当前线程。
     */
    fun begin() {
        check(!isInTransaction()) { "Transaction already active on current thread" }
        val conn = dataSource.connection
        conn.autoCommit = false
        threadLocalConnection.set(conn)
    }

    /**
     * 提交事务。
     */
    fun commit() {
        val conn = threadLocalConnection.get()
            ?: throw IllegalStateException("No active transaction")
        conn.commit()
        cleanup(conn)
    }

    /**
     * 回滚事务。无活跃事务时静默忽略。
     */
    fun rollback() {
        val conn = threadLocalConnection.get() ?: return
        try {
            conn.rollback()
        } finally {
            cleanup(conn)
        }
    }

    private fun cleanup(conn: Connection) {
        try {
            conn.autoCommit = true
            conn.close()
        } finally {
            threadLocalConnection.remove()
        }
    }
}
