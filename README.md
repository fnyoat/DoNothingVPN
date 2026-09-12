<p align="center">
  <img src="assets/icon.png" alt="DoNothingVPN icon" width="128" />
</p>

<h1 align="center">DoNothingVPN</h1>

<p align="center">
  <a href="README.zh-CN.md"><img src="https://img.shields.io/badge/简体中文-文档-1a8bff" alt="简体中文" /></a>
  <img src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Target%20SDK-35-3DDC84?logo=android&logoColor=white" alt="Target SDK 35" />
  <img src="https://img.shields.io/badge/Min%20SDK-26-99aabb?logo=android&logoColor=white" alt="Min SDK 26" />
</p>

<p align="center"><strong>Minimal by design. Its only job: change the VPN connection tip. Nothing else.</strong></p>

## What it does

Stays out of your way. The one and only thing this app does is change the
**VPN connection tip** — the "connected" name that shows in your VPN list and
status bar. No traffic is routed, no extra features, no bloat.

It works by repackaging itself with your chosen name: `Repackager.kt` rewrites
the app label, re-signs the APK with your own key, and reinstalls the new copy.

Want a VPN that actually <em>does</em> something? Build any other "DoSomethingVPN" —
just start from a real VPN implementation and change one thing: **the app name**.

## Build

```bash
gradle assembleDebug assembleRelease --no-daemon
```

The debug APK is signed with `keystore/repack.p12`. If `keystore-local.properties` is missing,
the build falls back to the defaults (`android` / `repack`). Keep both out of version control.

CI rebuilds both variants on every push and uploads them as workflow artifacts.

---

<p align="center">
  <a href="README.zh-CN.md">简体中文版</a>
</p>