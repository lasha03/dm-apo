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

    /**
     * Makes the page feel like an app rather than a website: no tap flash, no text
     * selection outside text fields, no scrollbars or overscroll, and no "Open app"
     * / "Get the app" banners. Safe to run at document start and repeatedly.
     * When [window.__dmsHideNav] is set it also removes the (emptied) nav bar itself.
     */
    val APP_FEEL = """
        (function(){try{
          if(window.__dmsApp)return;window.__dmsApp=1;
          var css='*{-webkit-tap-highlight-color:transparent!important}'+
            'html,body{overscroll-behavior:none}'+
            'body{-webkit-user-select:none;user-select:none;-webkit-touch-callout:none}'+
            'input,textarea,[contenteditable],[contenteditable] *{-webkit-user-select:text!important;user-select:text!important;-webkit-touch-callout:default}'+
            '::-webkit-scrollbar{display:none}';
          var appLinks='a[href*="play.google.com"],a[href*="app.link"],a[href^="intent:"],a[href^="instagram:"]';
          var bannerText=/^(open app|open instagram|use the app|get the app|open in app|switch to the app)$/i;
          function style(){
            if(document.getElementById('dms-app'))return;
            var s=document.createElement('style');s.id='dms-app';s.textContent=css;
            (document.head||document.documentElement).appendChild(s);
          }
          function hideBox(el,maxH){
            for(var n=el,i=0;n&&n!==document.body&&i<8;n=n.parentElement,i++){
              var cs=getComputedStyle(n);
              if((cs.position==='fixed'||cs.position==='sticky')&&n.getBoundingClientRect().height<=maxH){
                n.setAttribute('data-dms-hidden','1');n.style.setProperty('display','none','important');return;
              }
            }
            el.style.setProperty('display','none','important');
          }
          // The bar is the largest small (<=120px) ancestor of the Explore/Reels link that holds
          // no chat content. Anything bigger, or holding chats/text fields, is never touched.
          function navBar(){
            var ex=document.querySelector('a[href="/explore/"],a[href="/reels/"]');if(!ex)return null;
            var best=null;
            for(var n=ex.parentElement,i=0;n&&n!==document.body&&i<10;n=n.parentElement,i++){
              if(n.getBoundingClientRect().height>120)break;
              if(n.querySelector('textarea,input,[contenteditable],[role="textbox"],a[href^="/direct/t/"],main,[role="main"]'))break;
              best=n;
            }
            return best;
          }
          function tidy(){try{
            style();
            document.querySelectorAll(appLinks).forEach(function(a){hideBox(a,160);});
            document.querySelectorAll('a,button,[role="button"]').forEach(function(b){
              if(bannerText.test((b.textContent||'').trim()))hideBox(b,160);
            });
            var bar=navBar();
            if(bar){
              if(window.__dmsHideNav){bar.setAttribute('data-dms-nav','1');bar.style.setProperty('display','none','important');}
              else if(bar.getAttribute('data-dms-nav')){bar.removeAttribute('data-dms-nav');bar.style.removeProperty('display');}
            }
          }catch(e){}}
          window.__dmsTidy=tidy;
          style();
          var t=0;
          new MutationObserver(function(){if(t)return;t=setTimeout(function(){t=0;tidy();},300);})
            .observe(document.documentElement,{childList:true,subtree:true});
          if(document.readyState!=='loading')tidy();else document.addEventListener('DOMContentLoaded',tidy);
        }catch(e){}})();
    """.trimIndent()

    fun hideNav(enabled: Boolean): String {
        val rules = JSONArray(HIDE_NAV_SELECTORS).toString()
        return """
            (function(hide){try{
              window.__dmsHideNav=hide;
              if(window.__dmsTidy)window.__dmsTidy();
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
