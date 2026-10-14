package app.dms

import app.dms.UrlPolicy.Action
import app.dms.UrlPolicy.ViewerAction
import org.junit.Assert.assertEquals
import org.junit.Test

class UrlPolicyTest {

    private fun d(host: String?, path: String?) = UrlPolicy.decide(host, path)

    @Test fun dmsAndLoginStayInApp() {
        assertEquals(Action.ALLOW, d("www.instagram.com", "/direct/inbox/"))
        assertEquals(Action.ALLOW, d("www.instagram.com", "/direct/t/123456/"))
        assertEquals(Action.ALLOW, d("instagram.com", "/accounts/login/"))
        assertEquals(Action.ALLOW, d("www.instagram.com", "/challenge/abc/"))
        assertEquals(Action.ALLOW, d("www.instagram.com", "/two_factor"))
        assertEquals(Action.ALLOW, d("www.facebook.com", "/login.php"))
        assertEquals(Action.ALLOW, d("m.facebook.com", "/v19.0/dialog/oauth"))
        assertEquals(Action.ALLOW, d("ig.me", "/m/someone"))
    }

    @Test fun feedGoesToInbox() {
        assertEquals(Action.INBOX, d("www.instagram.com", "/"))
        assertEquals(Action.INBOX, d("www.instagram.com", ""))
        assertEquals(Action.INBOX, d("www.instagram.com", "/explore/"))
        assertEquals(Action.INBOX, d("www.instagram.com", "/reels/"))
    }

    @Test fun sharedContentOpensViewer() {
        assertEquals(Action.VIEWER, d("www.instagram.com", "/p/AbC123/"))
        assertEquals(Action.VIEWER, d("www.instagram.com", "/reel/AbC123/"))
        assertEquals(Action.VIEWER, d("www.instagram.com", "/reels/AbC123/"))
        assertEquals(Action.VIEWER, d("www.instagram.com", "/stories/someone/123/"))
        assertEquals(Action.VIEWER, d("www.instagram.com", "/someone/"))
        assertEquals(Action.VIEWER, d("www.instagram.com", "/directory/profiles/"))
        assertEquals(Action.VIEWER, d("scontent-xyz.cdninstagram.com", "/v/t51/x.jpg"))
    }

    @Test fun everythingElseIsExternal() {
        assertEquals(Action.EXTERNAL, d("example.com", "/"))
        assertEquals(Action.EXTERNAL, d("notinstagram.com", "/direct/"))
        assertEquals(Action.EXTERNAL, d("instagram.com.evil.net", "/direct/"))
        assertEquals(Action.EXTERNAL, d(null, "/"))
    }

    @Test fun viewerRules() {
        assertEquals(ViewerAction.ALLOW, UrlPolicy.decideInViewer("www.instagram.com", "/p/x/"))
        assertEquals(ViewerAction.OPEN_IN_MAIN, UrlPolicy.decideInViewer("www.instagram.com", "/direct/t/1/"))
        assertEquals(ViewerAction.CLOSE, UrlPolicy.decideInViewer("www.instagram.com", "/"))
        assertEquals(ViewerAction.EXTERNAL, UrlPolicy.decideInViewer("example.com", "/"))
    }

    @Test fun neverHandsOffToMetaApps() {
        assertEquals(true, UrlPolicy.isMetaAppLink("instagram", null))
        assertEquals(true, UrlPolicy.isMetaAppLink("intent", "com.instagram.android"))
        assertEquals(true, UrlPolicy.isMetaAppLink(null, "com.facebook.katana"))
        assertEquals(false, UrlPolicy.isMetaAppLink("mailto", null))
        assertEquals(false, UrlPolicy.isMetaAppLink("intent", "com.google.android.youtube"))
    }

    @Test fun unreadCountFromTitle() {
        assertEquals(3, TitleParser.unreadCount("(3) Instagram"))
        assertEquals(12, TitleParser.unreadCount(" (12) Inbox • Direct"))
        assertEquals(0, TitleParser.unreadCount("Instagram"))
        assertEquals(0, TitleParser.unreadCount(null))
    }
}
