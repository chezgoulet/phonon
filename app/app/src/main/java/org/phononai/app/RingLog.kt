package org.phononai.app

import java.time.OffsetDateTime

/** Bounded in-memory log. Old lines are dropped; nothing is invented. */
class RingLog(private val capacity: Int) {
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun append(line: String) {
        val clipped = if (line.length > 2_000) line.substring(0, 2_000) + "…" else line
        lines.addLast("${OffsetDateTime.now()} $clipped")
        while (lines.size > capacity) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()
}
