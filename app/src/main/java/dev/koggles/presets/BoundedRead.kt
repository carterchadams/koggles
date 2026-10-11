package dev.koggles.presets

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** projectM's parser limit (`PresetFileParser::maxFileSize`); larger presets cannot load anyway. */
const val MAX_PRESET_BYTES = 0x100000

/**
 * Reads a preset as UTF-8, consuming at most [limit] + 1 bytes. Throws [IOException] when the stream
 * is larger than [limit], before the text is built, so a huge file can never exhaust the heap.
 * Provider size metadata is not trusted.
 */
fun InputStream.readPresetText(limit: Int = MAX_PRESET_BYTES): String {
    val out = ByteArrayOutputStream(minOf(limit, 64 * 1024))
    val buf = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val want = minOf(buf.size.toLong(), limit + 1L - total).toInt()
        val n = read(buf, 0, want)
        if (n < 0) break
        total += n
        if (total > limit) throw IOException("preset larger than ${limit / 1024} KiB")
        out.write(buf, 0, n)
    }
    return out.toString(Charsets.UTF_8.name())
}
