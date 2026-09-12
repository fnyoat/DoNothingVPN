<p align="center">
  <img src="assets/icon.png" alt="DoNothingVPN 图标" width="128" />
</p>

<h1 align="center">DoNothingVPN</h1>

<p align="center">
  <a href="README.md"><img src="https://img.shields.io/badge/English-Docs-2ea44f" alt="English" /></a>
  <img src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Target%20SDK-35-3DDC84?logo=android&logoColor=white" alt="目标 SDK 35" />
  <img src="https://img.shields.io/badge/Min%20SDK-26-99aabb?logo=android&logoColor=white" alt="最低 SDK 26" />
</p>

<p align="center"><strong>极简为本,只做一件事:修改 VPN 连接提示。不做任何多余的事。</strong></p>

## 它做什么

不会在你面前晃。这个应用唯一做的事,就是修改 **VPN 连接提示** —— 也就是 VPN 列表与
状态栏里显示的那个"已连接"名称。不转发任何流量,不加任何功能,零臃肿。

原理是把它自己用你取的名字重新打包:`Repackager.kt` 改写应用名、用你自己的密钥重新签名,
然后装回新的副本。

想要一个真正<em>能干实事</em>的 VPN?去构建任何"DoSomethingVPN"—— 从真实的 VPN 实现
出发,只改一样东西:**应用名称**。

## 构建

```bash
gradle assembleDebug assembleRelease --no-daemon
```

debug APK 使用 `keystore/repack.p12` 签名。若缺少 `keystore-local.properties`,
构建会回退到默认值(`android` / `repack`)。两个文件都不要提交进版本库。

CI 会在每次 push 时构建两个变体,并把 APK 作为构建产物上传。

---

<p align="center">
  <a href="README.md">English version</a>
</p>