package org.kutner.cameragpslink

import java.io.File

/** Two bounded local files; diagnostics must never grow for the lifetime of the install. */
internal class RotatingLogFile(private val file: File, private val maxBytes: Int = 64 * 1024) {
    private val previous = File(file.parentFile, "${file.name}.previous")

    init {
        require(maxBytes >= 16)
    }

    @Synchronized
    fun append(record: String) {
        // Leave space for a newline even with multi-byte UTF-8 characters.
        val line = record.takeLast((maxBytes - 1) / 4) + "\n"
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (file.length() + bytes.size > maxBytes) {
            check(!previous.exists() || previous.delete()) { "Unable to retire old diagnostic log" }
            check(!file.exists() || file.renameTo(previous)) { "Unable to rotate diagnostic log" }
        }
        file.appendBytes(bytes)
    }

    @Synchronized
    fun read(): String = listOf(previous, file).filter(File::exists).joinToString("") { it.readText() }
}
