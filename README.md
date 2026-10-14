# DMs

A personal Android app that shows **only Instagram Direct Messages**. It wraps
`https://www.instagram.com/direct/inbox/` in a locked-down WebView. You log in
once on Instagram's normal web login page. No Instagram API, no private APIs,
no scraping, no third-party SDKs, analytics or ads.

## Install from your phone (no computer needed)

Every push to this repo builds the APK on GitHub Actions
(`.github/workflows/build.yml`) and publishes it as a pre-release named
**latest**.

1. On your phone, open the repo on github.com → **Releases** → **DMs (latest build)**.
2. Tap **DMs.apk** to download it.
3. Open the downloaded file. If Android asks, allow your browser/Files app to
   **Install unknown apps** (Settings → Apps → *your browser* → Install unknown apps).
4. Tap **Install**, open **DMs**, and log in.

To update, download the newest `DMs.apk` and install it over the old one. All
builds are signed with the same committed debug key (`keystore/debug.keystore`),
so updates keep your login. (If the Releases step ever fails, the APK is also
attached to each run under **Actions → Build APK → Artifacts**, as a zip.)

**Make DM links open in this app:** Settings → Apps → DMs → Open by default →
*Add link* → enable `ig.me` and `instagram.com`. (Android doesn't auto-verify
these links for an app that isn't Instagram's.)

## Build on a computer

Requirements: JDK 17+ and the Android SDK (platform 35). Set `ANDROID_HOME` or
create `local.properties` with `sdk.dir=/path/to/Android/sdk`.

```sh
./gradlew assembleDebug          # build
./gradlew testDebugUnitTest      # URL-filter unit tests
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`, or
copy the file to the phone and open it (with "Install unknown apps" allowed).

## What it does

- **DMs only.** `/direct`, login, 2FA, challenge and OAuth pages stay in the app
  (Facebook login too). The home feed, Explore and Reels send you back to the
  inbox. Posts, reels, stories and profiles that someone shares in a chat open in
  a separate full-screen viewer with a close button; closing it puts you back in
  the same chat. Everything else opens in your normal browser.
- **Hides feed shortcuts.** CSS is injected to hide the Home, Explore, Reels and
  Create links (toggle it in the ⋮ menu). If Instagram changes its markup the
  rules just stop matching; nothing breaks.
- **Send photos/videos** from the gallery (multi-select when Instagram allows it)
  or straight from the camera.
- **Voice messages** and **calls** (best effort): the mic/camera are only ever
  granted to `instagram.com` pages.
- **Save media:** long-press a photo or video → *Save*. Files go to `Pictures/DMs`.
- **Unread badge:** the count from the page title, e.g. `(3) Instagram`, is
  shown next to the app name and in the recent-apps label.
- Remembers your login, follows system dark mode, survives rotation without
  reloading, back button minimizes instead of closing, offline screen with
  Retry (and auto-retry when the connection comes back).
- Doesn't log cookies, URLs or page content.

## Known limitations

- **No push notifications while the app is closed.** A WebView can't receive
  Instagram's push notifications; you only see new messages while it's open.
- **View-once photos/videos** show "open the Instagram app" because Instagram's
  servers don't send view-once media to the website at all. There is nothing in
  the page for a WebView to display, so this app can't open them.
- **Calling** depends on Instagram's mobile website. It may not offer calls at
  all; if it does, the app grants mic/camera and supports call pop-up windows.
- **Instagram website changes** can break the nav-hiding CSS (the URL filter
  still keeps you in the DMs).
- Some videos are streamed in chunks and can't be saved; you'll get a message
  when that happens.
