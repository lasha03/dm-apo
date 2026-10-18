# DMs

**The real Instagram app, but (mostly) only your messages.**

DMs is a small Android app that keeps the official Instagram app on Messages.
After 10 minutes a day on the Home feed, Reels or Explore/Search, opening them
makes it tap Instagram's own Messages button for you, so you land back in your
inbox. You chat in the real
app, with replies, photos, voice messages, GIFs, calls, view-once media and
Instagram's own notifications all working normally.

How it works: DMs uses Android's **Accessibility** API (the same API that
screen-time and "Reels blocker" apps use) to see which Instagram tab is open.
It doesn't modify Instagram, uses no Instagram API (official or private), and
only listens to Instagram. It reads which tab is selected, never your messages,
and nothing leaves the phone. To Instagram it looks like you tapped Messages
yourself.

> The earlier WebView version (instagram.com/direct in a locked-down browser) is
> in the git history before the "Focus guard" commit.

## Install from your phone

Every push builds the APK on GitHub Actions and publishes it as the **latest**
pre-release.

1. On your phone, open
   **https://github.com/lasha03/dm-apo/releases/download/latest/DMs.apk**.
2. If the download sits at 100%, open Chrome **⋮ → Downloads** and tap
   **Keep / Download anyway**.
3. Open the file and allow **Install unknown apps** if asked. If Play Protect
   warns you, choose **More details → Install anyway**.

Updates install over the old version: every build is signed with the same
committed debug key (`keystore/debug.keystore`).

## Setup

1. **Install Instagram** from the Play Store and log in.
2. Open **DMs** → **Turn on focus guard** → find **DMs** (possibly under
   "Downloaded apps" / "Installed services") → switch it on.
3. If Android says **"Restricted setting"** (Android 13+ does this for apps
   installed outside the Play Store): in DMs tap **App info → ⋮ → Allow
   restricted settings**, then repeat step 2.
4. **Xiaomi / Redmi / POCO:** Settings → Apps → Manage apps → DMs →
   **Autostart: on**, **Battery saver: No restrictions**. Otherwise the system
   may switch the guard off.
5. Optional: put the Instagram icon in a folder and use the DMs icon instead.
   It opens Instagram straight into Messages.

Settings (which tabs to block) are in the DMs app; you can also reach them by
long-pressing the DMs icon → **Settings**.

## 10 minutes a day

Every day you get **10 minutes** on the Home feed, Reels and Explore (and
swiping through reels people send you). While time is left, those screens just
work, and a toast shows how much is left when you open one. Only time actually
spent on them counts; chatting never does. You get a heads-up at 1 minute left;
at zero you're sent back to Messages and they stay blocked until midnight.
**Lock the rest of today** in DMs spends what's left if you'd rather have none.

## What's blocked and what isn't

- **Limited to 10 minutes a day (each can be switched off):** Home feed, Reels
  tab, Explore/Search tab. After that, opening any of them sends you back to
  Messages.
- **Shared reels:** a reel someone sends you always plays, but only when you
  open it from the chat. Swiping on to more reels uses the daily 10 minutes;
  after that the first swipe sends you back to the chat (can be switched off).
- **Reels opened anywhere else** (from the feed, Explore or someone's profile)
  count as feed time too, so there's no "open a reel quickly" loophole.
- **Still works:** everything in Messages, plus posts, stories and profiles
  that people send you in a chat, your own profile, and settings.

## Troubleshooting

The **Status** section at the bottom of the DMs screen shows whether the guard
is running, which Instagram screen it last saw, which tab buttons it
recognised, and what it last did. It never shows message content.

- **"Guard running: no"** while the switch is on: switch DMs off and on again in
  Accessibility settings, and on Xiaomi set Autostart on.
- **Not sent back from a tab:** check "Tabs seen". If it's `none`, Instagram has
  changed its layout and the recogniser in `InstagramScreens.kt` needs updating.
  Send those status lines along with a description of the screen.

## Build on a computer

Requirements: JDK 17+ and the Android SDK (platform 35), via `ANDROID_HOME` or
`local.properties` (`sdk.dir=...`).

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # screen-recognition tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Known limitations

- It recognises Instagram's tabs by their internal view ids (with English
  labels as a fallback). A big Instagram redesign can make it miss a tab until
  the recogniser is updated.
- You'll still see Instagram's bottom tab bar. Tapping a blocked tab just
  bounces you back.
- Spotting a swipe in a shared reel relies on Instagram's reel player view
  names ("Reel player seen" in Status shows what it found). If swiping isn't
  caught, send that line.
- Some phones (notably Xiaomi) switch accessibility services off when they
  clean up background apps; see the Xiaomi step in Setup.
