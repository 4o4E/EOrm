package top.e404.eorm.generator

import java.util.UUID

/**
 * 主键生成策略枚举。
 * - [AUTO] 数据库自增
 * - [SNOWFLAKE] 雪花算法生成分布式唯一 ID
 * - [UUID] 生成 UUID 字符串
 * - [MANUAL] 手动指定
 */
enum class IdStrategy { AUTO, SNOWFLAKE, UUID, MANUAL }

/**
 * 主键生成器，提供雪花算法和 UUID 两种 ID 生成方式。
 */
interface EOrmIdGenerator {
    /**
     * 使用雪花算法生成下一个唯一 ID。
     * @return 64 位长整型唯一 ID
     * @throws RuntimeException 当检测到时钟回拨时抛出
     */
    fun nextSnowflakeId(): Long

    /**
     * 生成去除连字符的 UUID 字符串。
     * @return 32 位 UUID 字符串
     */
    fun nextUuid(): String
}

/**
 * 默认主键生成器实现，提供可配置 workerId/datacenterId 的雪花算法和 UUID。
 */
class DefaultIdGenerator(
    private val workerId: Long = 1,
    private val datacenterId: Long = 1
) : EOrmIdGenerator {
    private companion object {
        private const val WORKER_ID_BITS = 5L
        private const val DATACENTER_ID_BITS = 5L
        private const val SEQUENCE_BITS = 12L
        private const val SEQUENCE_MASK = -1L xor (-1L shl SEQUENCE_BITS.toInt())
        private const val WORKER_ID_SHIFT = SEQUENCE_BITS
        private const val DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS
        private const val TIMESTAMP_LEFT_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS
        private const val TWEPOCH = 1288834974657L
    }

    private var sequence: Long = 0L
    private var lastTimestamp: Long = -1L

    @Synchronized
    override fun nextSnowflakeId(): Long {
        var timestamp = System.currentTimeMillis()
        if (timestamp < lastTimestamp) throw RuntimeException("Clock moved backwards")
        if (lastTimestamp == timestamp) {
            sequence = (sequence + 1) and SEQUENCE_MASK
            if (sequence == 0L) timestamp = tilNextMillis(lastTimestamp)
        } else { sequence = 0L }
        lastTimestamp = timestamp
        return ((timestamp - TWEPOCH) shl TIMESTAMP_LEFT_SHIFT.toInt()) or
                (datacenterId shl DATACENTER_ID_SHIFT.toInt()) or
                (workerId shl WORKER_ID_SHIFT.toInt()) or sequence
    }

    /** 自旋等待直到下一毫秒。 */
    private fun tilNextMillis(lastTimestamp: Long): Long {
        var timestamp = System.currentTimeMillis()
        while (timestamp <= lastTimestamp) timestamp = System.currentTimeMillis()
        return timestamp
    }

    override fun nextUuid(): String = UUID.randomUUID().toString().replace("-", "")
}

/**
 * 全局默认主键生成器。保留该入口以兼容既有代码。
 */
object IdGenerator : EOrmIdGenerator by DefaultIdGenerator()
