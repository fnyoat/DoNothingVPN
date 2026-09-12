<p align="center">
  <img src="assets/feature-graphic.png" alt="DoNothingVPN 封面" width="100%" />
</p>

<h1 align="center">DoNothingVPN</h1>

<p align="center">
  <a href="README.md"><img src="https://img.shields.io/badge/English-Docs-2ea44f" alt="English" /></a>
  <img src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Target%20SDK-35-3DDC84?logo=android&logoColor=white" alt="目标 SDK 35" />
  <img src="https://img.shields.io/badge/Min%20SDK-26-99aabb?logo=android&logoColor=white" alt="最低 SDK 26" />
</p>

<p align="center"><strong>一个什么都不干的假 VPN —— 连上之后,就真的什么都不干。</strong></p>

## 这是什么

一个极小的 Android 应用,只在通知栏显示"已连接 VPN",实际上一个字节都不会转发。
它是**安慰剂 VPN**:很适合那些坚持要求"VPN 已连接"才肯干活的 APP 或系统设置,
而你的流量始终由你自己掌控。

## 特性

- **真·假 VPN** —— 调用 `VpnService.prepare()`,然后建立一个空隧道。
- **常驻状态** —— 状态栏一直挂着"已连接"通知。
- **改名并重打包** —— 随时改应用名(也是 VPN 名),用你自己的密钥重新签名并重装。
- **零依赖权限** —— 只请求成为 VPN,不碰其它任何权限。

## 原理

前台服务建立 `android.net.VpnService`,把一个空的构建结果交回系统。Android 于是显示
VPN 连接图标和通知——仅此而已。`Repackager.kt` 改写 `AndroidManifest.xml` 里的应用名、
重新签名,让重装后的应用保留身份,包括状态栏里显示的 VPN 名称。

## 构建

```bash
gradle assembleDebug assembleRelease --no-daemon
```

debug APK 使用 `keystore/repack.p12` 签名。若缺少 `keystore-local.properties`,
构建会回退到默认值(`android` / `repack`)。两个文件都不要提交进版本库。

CI 会在每次 push 时构建两个变体,并把 APK 作为构建产物上传。

## 为什么叫"Do Nothing"?

有些连接,保持不连接反而更好。这个只负责看起来像连上了。

---

<p align="center">
  <a href="README.md">English version</a>
</p>