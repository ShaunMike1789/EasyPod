package com.smgray.easypod.backup

import java.io.InputStream
import java.io.OutputStream

internal object EasyPodBackupArchiveSafety {
    fun isSafeEntryName(name: String): Boolean =
        name.isNotBlank() &&
            name.length <= 64 &&
            '/' !in name &&
            '\\' !in name &&
            name != "." &&
            name != ".."

    fun copyLimited(
        input: InputStream,
        output: OutputStream,
        limit: Long,
    ): Long {
        require(limit >= 0) { "Backup size limit is invalid" }
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "Backup is larger than the supported size" }
            output.write(buffer, 0, read)
        }
        return total
    }
}
