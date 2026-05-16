package top.e404.eorm.transaction

/**
 * 同步事务传播策略。
 */
enum class TransactionPropagation {
    /**
     * 当前线程已有事务时复用当前事务；没有事务时开启新事务。
     */
    REQUIRED,

    /**
     * 总是开启一个新事务。当前线程已有事务时会先挂起当前事务连接。
     */
    REQUIRES_NEW,

    /**
     * 要求当前线程不在事务中；如果已有事务则抛出异常。
     */
    NEVER
}
