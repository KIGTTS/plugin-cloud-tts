# 语音合成服务插件

统一管理与路由实现由本项目编写；内置系统 TTS 代码移植自旧 KIGTTS，插件整体按 GPL-3.0-only 提供（LICENSE）。

服务通过 API9 和跨进程 PCM broker 调用独立引擎，语音包由对应提供者解析/保存。系统 TTS 保底、目录 UI、声音选择与删除确认都在本插件内；主程序仅有通用接口和传输，不包含 Piper、eSpeak 或系统 TTS 实现。

系统引擎候选回退、异步初始化、synthesizeToFile → PCM 的基本流程移植并重写自 LHT02/KIGTTS 的 `android-app/app/src/main/java/com/kgtts/app/audio/Engines.kt` / `SystemTtsEngine`。
系统连接、流式 PCM 回调、WAV 读取与两分钟 idle 生命周期现直接位于本插件的 `com.kigtts.engines.system`；不再构建或分发 `engine.system-tts` 独立插件或单独 system-support 库。
旧独立包的设置页没有进入主程序；服务自己的原生设置、声音目录与语速/常驻选项统一管理保底系统声音。
移植代码及本服务仍按 GPL-3.0-only 发布，未将原 GPL 实现改标为 MIT。

本服务静态复用原创 MIT `tts-support` 的 ResidentResource/SpeechSegments 辅助实现；其许可证随包保存于 `licenses/tts-support-MIT.txt`。
