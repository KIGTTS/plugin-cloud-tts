# 云端语音合成

在 KIGTTS 中使用腾讯云、阿里云、Azure、百度云或讯飞朗读文字。先添加账户，再创建语音包并加入需要的声音；语音包列表显示对应服务商。

预设声音与克隆声音分开管理。当前支持腾讯云已有克隆声音 ID 和 Azure 已部署的自定义声音；阿里云、百度云、讯飞暂提供预设声音。声音由你选择添加，不会自动把全部声音加入列表。

具体接口、凭据及限制见 [协议说明](PROTOCOLS.md)。本轮已通过协议请求与页面回归，真实合成与声音可用性仍需使用有效账户验证。

## 构建

需要 JDK17、Android SDK36、PowerShell7。克隆时获取固定版本的 plugin-sdk 子模块。

```powershell
./gradlew.bat testDebugUnitTest assembleDebug collectPluginDependencies
./Package.ps1 -SigningKey /path/to/publisher-private.der -AndroidSdk /path/to/android-sdk
```

许可：GPL-3.0-only，见 [LICENSE](LICENSE)。第三方许可见 [NOTICE.md](NOTICE.md)。