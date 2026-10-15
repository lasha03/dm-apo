package app.dms

import org.junit.Assert.assertEquals
import org.junit.Test

class DailyAllowanceTest {

    @Test fun usageCountsDownWithinADay() {
        val a = DailyAllowance("2026-10-15", 0L).plus("2026-10-15", 4 * 60_000L)
        assertEquals(6 * 60_000L, a.remainingMs("2026-10-15"))
    }

    @Test fun neverGoesBelowZero() {
        val a = DailyAllowance("2026-10-15", 9 * 60_000L).plus("2026-10-15", 5 * 60_000L)
        assertEquals(0L, a.remainingMs("2026-10-15"))
    }

    @Test fun newDayStartsFresh() {
        val yesterday = DailyAllowance("2026-10-14", DailyAllowance.LIMIT_MS)
        assertEquals(DailyAllowance.LIMIT_MS, yesterday.remainingMs("2026-10-15"))
        assertEquals(30_000L, yesterday.plus("2026-10-15", 30_000L).usedMs)
    }
}
