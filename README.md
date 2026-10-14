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

## Message notifications (optional, recommended)

A WebView can't receive Instagram's push notifications, but the Instagram app
or Chrome can. DMs uses Android's **Notification access** (the official API
smartwatch apps use) to turn their DM notifications into DMs notifications that
open this app. There's no background service, no extra battery use, no private
API, and nothing leaves the phone.

1. Get Instagram notifications on the phone, using **one** of these:
   - Install the Instagram app, log in with the same account, and leave its
     message notifications on. You never need to open it.
   - In Chrome, open instagram.com, log in, and allow notifications.
2. In DMs, tap **⋮ → Message notifications → Turn on** and allow Notification
   access for DMs.
3. If Android says **"Restricted setting"** (Android 13+ does this for apps
   installed outside the Play Store), go to **App info → ⋮ → Allow restricted
   settings** and try step 2 again.

Every Instagram notification (except calls and upload progress) is replaced by
a DMs one (sender + message; hidden on the lock screen), and tapping it opens
your inbox in DMs. So in Instagram's own settings, turn off everything except
**Messages** (profile → ☰ → Notifications).

On Xiaomi/Redmi/POCO, also set **Autostart: on** and **Battery saver: No
restrictions** for both DMs and Instagram, or MIUI/HyperOS may stop them.

**Troubleshooting:** ⋮ → Message notifications shows whether the relay is
running, how many Instagram notifications it has seen and what it did with the
last one. If it says access is on but the relay isn't running (common on Xiaomi
after an update), switch DMs off and on again under Notification access and
set Autostart on for DMs.

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

- **No push notifications on its own.** A WebView can't receive Instagram's
  push notifications. Turn on *Message notifications* (above) to get them
  through the Instagram app or Chrome. Tapping one opens the inbox, not the
  specific chat, because Android doesn't let other apps read where the original
  notification would have led.
- **View-once photos/videos** show "open the Instagram app" because Instagram's
  servers don't send view-once media to the website at all. There is nothing in
  the page for a WebView to display, so this app can't open them.
- **Calling** depends on Instagram's mobile website. It may not offer calls at
  all; if it does, the app grants mic/camera and supports call pop-up windows.
- **Instagram website changes** can break the nav-hiding CSS (the URL filter
  still keeps you in the DMs).
- Some videos are streamed in chunks and can't be saved; you'll get a message
  when that happens.
