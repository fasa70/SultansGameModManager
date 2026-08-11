package com.sultansgame.modmanager.storage

import java.io.File

fun interface StorageSpaceProbe {
    fun availableBytes(path: File): Long
}

data class StorageRequirement(
    val phase: String,
    val target: File,
    val requiredBytes: Long,
    val availableBytes: Long,
    val reserveBytes: Long,
)

class InsufficientStorageException(
    val requirement: StorageRequirement,
) : IllegalStateException(
    "${requirement.phase}需要更多存储空间：至少需要 " +
        "${requirement.requiredBytes} 字节（含 ${requirement.reserveBytes} 字节余量），" +
        "当前可用 ${requirement.availableBytes} 字节",
)

class StorageBudget(
    private val probe: StorageSpaceProbe,
    private val reserveBytes: Long = DEFAULT_RESERVE_BYTES,
) {
    init {
        require(reserveBytes >= 0) { "reserveBytes must not be negative" }
    }

    fun requireSpace(target: File, additionalBytes: Long, phase: String) {
        require(additionalBytes >= 0) { "additionalBytes must not be negative" }
        val required = try {
            Math.addExact(additionalBytes, reserveBytes)
        } catch (_: ArithmeticException) {
            throw InsufficientStorageException(
                StorageRequirement(phase, target, Long.MAX_VALUE, 0, reserveBytes),
            )
        }
        val available = probe.availableBytes(target).coerceAtLeast(0)
        if (available < required) {
            throw InsufficientStorageException(
                StorageRequirement(phase, target, required, available, reserveBytes),
            )
        }
    }

    fun checkChunk(target: File, writtenBytes: Long, nextBytes: Long, phase: String) {
        require(writtenBytes >= 0 && nextBytes >= 0) { "byte counts must not be negative" }
        requireSpace(target, nextBytes, phase)
    }

    companion object {
        const val DEFAULT_RESERVE_BYTES: Long = 64L * 1024L * 1024L
        val UNBOUNDED: StorageBudget = StorageBudget(StorageSpaceProbe { Long.MAX_VALUE }, 0)
    }
}
