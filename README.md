# 云端语音合成

为 KIGTTS 提供可选的云端语音合成接口。支持腾讯云、阿里云、Arzue、百度云和讯飞；每个服务商可以添加多个接口配置。

预设声音与克隆声音在声音包中分开标记。插件不会自动下载或填充服务商的全部声音，只有用户主动创建声音包并添加的声音才会出现在 KIGTTS 的语音包列表。

API 密钥仅保存在插件自己的本地数据目录中，接口地址必须使用 HTTPS。插件不会在没有配置和选用声音时发起网络请求。

## 构建

需要 JDK17、Android SDK36、PowerShell7。克隆时获取固定版本的 plugin-sdk 和 tts-support 子模块。

```powershell
./gradlew.bat testDebugUnitTest assembleDebug
./Package.ps1 -SigningKey /path/to/publisher-private.der -AndroidSdk /path/to/android-sdk
```

许可：GPL-3.0-only，见 [LICENSE](LICENSE)。第三方许可见 [NOTICE.md](NOTICE.md)。

