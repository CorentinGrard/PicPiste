package fr.cvvm.picpiste.core.replay

import java.io.BufferedReader
import java.io.Writer

/**
 * A recorded day of OGN traffic: one `epochMillis<TAB>raw APRS line` per line.
 * Used to replay real days through the status engine in tests and in the app.
 */
object Recording {
    fun formatLine(receivedAtMs: Long, line: String) = "$receivedAtMs\t$line"

    fun write(out: Writer, receivedAtMs: Long, line: String) {
        out.write(formatLine(receivedAtMs, line))
        out.write("\n")
    }

    fun parseLine(text: String): Pair<Long, String>? {
        val tab = text.indexOf('\t')
        if (tab <= 0) return null
        val t = text.substring(0, tab).toLongOrNull() ?: return null
        return t to text.substring(tab + 1)
    }

    fun read(reader: BufferedReader): Sequence<Pair<Long, String>> =
        reader.lineSequence().mapNotNull(::parseLine)
}
