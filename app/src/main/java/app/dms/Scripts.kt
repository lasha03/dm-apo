package app.dms

import org.json.JSONArray
import org.json.JSONObject

/**
 * JavaScript injected into Instagram pages. Every script is wrapped in
 * try/catch so a change in Instagram's markup can never break the page.
 */
object Scripts {

    /** Selectors for nav links to Home, Explore/Search, Reels and Create. */
    private val HIDE_NAV_SELECTORS = listOf(
        "a[href=\"/\"]",
        "a[href=\"/explore/\"]",
        "a[href=\"/reels/\"]",
        "a[href^=\"/create/\"]",
        // Fallbacks keyed on icon labels; each rule is inserted separately so an
        // unsupported selector (e.g. :has on an old WebView) only skips itself.
        "a:has(svg[aria-label=\"Home\"])",
        "a:has(svg[aria-label=\"Explore\"])",
        "a:has(svg[aria-label=\"Reels\"])",
        "a:has(svg[aria-label=\"New post\"])",
        "div[role=\"button\"]:has(> div svg[aria-label=\"New post\"])",
    )

    fun hideNav(enabled: Boolean): String {
        val rules = JSONArray(HIDE_NAV_SELECTORS).toString()
        return """
            (function(hide){try{
              var id='dms-hide-nav',s=document.getElementById(id);
              if(!hide){if(s)s.remove();return;}
              if(s)return;
              s=document.createElement('style');s.id=id;
              (document.head||document.documentElement).appendChild(s);
              var r=$rules;
              for(var i=0;i<r.length;i++){
                try{s.sheet.insertRule(r[i]+'{display:none!important}',s.sheet.cssRules.length);}catch(e){}
              }
            }catch(e){}})($enabled);
        """.trimIndent()
    }

    /**
     * Instagram is a single-page app, so tapping a shared post often never
     * triggers a real navigation. Catch those taps early and route them to the
     * native viewer instead.
     */
    val LINK_INTERCEPTOR = """
        (function(){try{
          if(window.__dmsLinks)return;window.__dmsLinks=1;
          var ok=/^\/(direct|accounts|challenge|auth_platform|two_factor|oauth|login|logout|session|api|ajax|graphql|call|consent|privacy|emails|nux|web)(\/|$)/;
          document.addEventListener('click',function(e){try{
            if(e.defaultPrevented||e.button!==0||!window.DMsBridge)return;
            var a=e.target&&e.target.closest&&e.target.closest('a[href]');if(!a)return;
            var u=new URL(a.getAttribute('href'),location.href);
            if(!/^((www|m)\.)?instagram\.com$/.test(u.hostname))return;
            var p=u.pathname;
            if(p==='/'||ok.test(p)||/^\/(explore|reels|create)\/?$/.test(p))return;
            e.preventDefault();e.stopImmediatePropagation();
            DMsBridge.postMessage(JSON.stringify({t:'open',u:u.href}));
          }catch(x){}},true);
        }catch(e){}})();
    """.trimIndent()

    /** Returns JSON {k:'image'|'video', u:url} for the media under a CSS-pixel point, or ''. */
    fun mediaAtPoint(x: Float, y: Float): String = """
        (function(x,y){try{
          var els=document.elementsFromPoint(x,y);
          for(var i=0;i<els.length;i++){
            var e=els[i],t=e.tagName;
            if(t==='VIDEO'){
              var s=e.currentSrc||e.src;
              if(!s){var so=e.querySelector('source');if(so)s=so.src;}
              if(s)return JSON.stringify({k:'video',u:s});
            }
            if(t==='IMG'){
              var r=e.getBoundingClientRect();
              var src=e.currentSrc||e.src;
              if(src&&r.width>=64&&r.height>=64)return JSON.stringify({k:'image',u:src});
            }
          }
        }catch(err){}return '';})($x,$y);
    """.trimIndent()

    /** Reads a blob: URL inside the page and posts it back as a data: URL. */
    fun fetchBlob(blobUrl: String): String {
        val u = JSONObject.quote(blobUrl)
        return """
            (function(u){
              function fail(){try{DMsBridge.postMessage('{"t":"saveError"}');}catch(e){}}
              try{
                fetch(u).then(function(r){return r.blob();}).then(function(b){
                  var f=new FileReader();
                  f.onloadend=function(){try{DMsBridge.postMessage(JSON.stringify({t:'data',d:f.result}));}catch(e){fail();}};
                  f.onerror=fail;
                  f.readAsDataURL(b);
                }).catch(fail);
              }catch(e){fail();}
            })($u);
        """.trimIndent()
    }
}
