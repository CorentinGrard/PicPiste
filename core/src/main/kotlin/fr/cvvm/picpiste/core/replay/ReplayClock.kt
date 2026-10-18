package fr.cvvm.picpiste.core.replay

/**
 * Plays a recording against a virtual clock: [speed]× real time, jumping over quiet
 * gaps longer than [maxGapMs] so a whole flying day fits in a few minutes.
 *
 * Call [advance] with the real milliseconds elapsed; it returns the lines due by then.
 */
class ReplayClock(
    private val entries: List<Pair<Long, String>>,
    var speed: Int = 4,
    private val maxGapMs: Long = 60_000,
) {
    private var index = 0
    var nowMs: Long = entries.firstOrNull()?.first ?: 0L
        private set

    val isEmpty get() = entries.isEmpty()
    val isFinished get() = index >= entries.size
    val startMs get() = entries.firstOrNull()?.first ?: 0L
    val endMs get() = entries.lastOrNull()?.first ?: 0L

    fun advance(realElapsedMs: Long): List<Pair<Long, String>> {
        if (isFinished) return emptyList()
        val next = entries[index].first
        if (next - nowMs > maxGapMs) nowMs = next - 2_000 // skip the quiet part
        nowMs += realElapsedMs * speed
        val due = ArrayList<Pair<Long, String>>()
        while (index < entries.size && entries[index].first <= nowMs) due += entries[index++]
        return due
    }

    fun restart() {
        index = 0
        nowMs = startMs
    }
}
