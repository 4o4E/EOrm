package top.e404.eorm.generator

import java.util.UUID

enum class IdStrategy { AUTO, SNOWFLAKE, UUID, MANUAL }

object IdGenerator {
    private const val WORKER_ID_BITS = 5L
    private const val DATACENTER_ID_BITS = 5L
    private const val SEQUENCE_BITS = 12L
    private const val SEQUENCE_MASK = -1L xor (-1L shl SEQUENCE_BITS.toInt())
    private const val WORKER_ID_SHIFT = SEQUENCE_BITS
    private const val DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS
    private const val TIMESTAMP_LEFT_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS
    private const val TWEPOCH = 1288834974657L
    private var workerId: Long = 1
    private var datacenterId: Long = 1
    private var sequence: Long = 0L
    private var lastTimestamp: Long = -1L

    @Synchronized
    fun nextSnowflakeId(): Long {
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
    private fun tilNextMillis(lastTimestamp: Long): Long {
        var timestamp = System.currentTimeMillis()
        while (timestamp <= lastTimestamp) timestamp = System.currentTimeMillis()
        return timestamp
    }
    fun nextUuid(): String = UUID.randomUUID().toString().replace("-", "")
}
