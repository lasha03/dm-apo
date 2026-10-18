package app.dms

/**
 * Remembers where you came from, so a reel player is only treated as "a reel
 * someone sent you" when it was opened from a chat. Reels opened from the feed,
 * Explore or a profile are feed time. Pure logic, unit tested.
 */
class ScreenContext {

    sealed class Kind {
        /** Messages, chats, profiles, settings: never limited. */
        object Free : Kind()

        /** The single reel someone sent you: free until you swipe on. */
        object SharedReel : Kind()

        /** Feed-type content; [screen] is the switch in settings that governs it. */
        data class Limited(val screen: Screen) : Kind()
    }

    private var inViewer = false
    private var viewerFromChat = false
    private var swipedOn = false
    private var lastWasChat = false

    fun observe(screen: Screen, reelViewer: Boolean, hasTextInput: Boolean): Kind {
        if (screen == Screen.HOME || screen == Screen.SEARCH || screen == Screen.REELS) {
            inViewer = reelViewer
            viewerFromChat = false
            lastWasChat = false
            return Kind.Limited(screen)
        }
        if (reelViewer) {
            if (!inViewer) {
                inViewer = true
                viewerFromChat = lastWasChat
                swipedOn = false
            }
            return if (viewerFromChat && !swipedOn) Kind.SharedReel else Kind.Limited(Screen.REELS)
        }
        inViewer = false
        lastWasChat = hasTextInput && (screen == Screen.OTHER || screen == Screen.MESSAGES)
        return Kind.Free
    }

    /** You swiped past the reel you were sent: from here on it's feed time. */
    fun markSwipedOn() {
        swipedOn = true
    }
}
