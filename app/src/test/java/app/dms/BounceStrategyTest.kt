package app.dms

import app.dms.BounceStrategy.Method
import org.junit.Assert.assertEquals
import org.junit.Test

class BounceStrategyTest {

    @Test fun reelPlayerAlwaysUsesBack() {
        for (failed in 0..10) {
            assertEquals(Method.BACK, BounceStrategy.choose(inReelPlayer = true, failedAttempts = failed, hasMessagesButton = true))
        }
    }

    @Test fun tabTapsMessagesFirst() {
        assertEquals(Method.TAP_MESSAGES, BounceStrategy.choose(false, 0, true))
        assertEquals(Method.OPEN_INBOX, BounceStrategy.choose(false, 0, false))
    }

    @Test fun alternatesWhenAnAttemptDidNotWork() {
        assertEquals(Method.BACK, BounceStrategy.choose(false, 1, true))
        assertEquals(Method.TAP_MESSAGES, BounceStrategy.choose(false, 2, true))
        assertEquals(Method.BACK, BounceStrategy.choose(false, 3, false))
        assertEquals(Method.OPEN_INBOX, BounceStrategy.choose(false, 4, false))
    }

    @Test fun neverStopsJustSlowsDown() {
        assertEquals(600L, BounceStrategy.cooldownMs(0))
        assertEquals(2_000L, BounceStrategy.cooldownMs(50))
    }
}
