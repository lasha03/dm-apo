package app.dms

/**
 * Today's full-Instagram allowance. Pure logic: usage is stored with the day it
 * belongs to, and a new day starts from zero.
 */
data class DailyAllowance(val day: String, val usedMs: Long) {

    fun forDay(today: String): DailyAllowance = if (today == day) this else DailyAllowance(today, 0L)

    fun remainingMs(today: String, limitMs: Long = LIMIT_MS): Long =
        (limitMs - forDay(today).usedMs).coerceAtLeast(0L)

    fun plus(today: String, ms: Long): DailyAllowance = forDay(today).let { it.copy(usedMs = it.usedMs + ms) }

    companion object {
        const val LIMIT_MS = 10 * 60_000L
    }
}
