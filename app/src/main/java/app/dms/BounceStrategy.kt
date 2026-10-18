package app.dms

/**
 * How to get out of a screen that's over today's limit. Pure logic, unit tested.
 *
 * - In a full-screen reel player: Back. It always closes the player, and there's
 *   no Messages button there anyway (asking Instagram to open the inbox from a
 *   playing reel doesn't work).
 * - On a tab: tap Instagram's own Messages button. If an earlier attempt didn't
 *   get us out, alternate with Back and with reopening the inbox, so one method
 *   that stopped working can't leave you stuck on the feed.
 */
object BounceStrategy {

    enum class Method { BACK, TAP_MESSAGES, OPEN_INBOX }

    fun choose(inReelPlayer: Boolean, failedAttempts: Int, hasMessagesButton: Boolean): Method = when {
        inReelPlayer -> Method.BACK
        failedAttempts % 2 == 1 -> Method.BACK
        hasMessagesButton -> Method.TAP_MESSAGES
        else -> Method.OPEN_INBOX
    }

    /** Never give up; just slow down a little if bouncing keeps being needed. */
    fun cooldownMs(failedAttempts: Int): Long = if (failedAttempts >= 6) 2_000L else 600L
}
