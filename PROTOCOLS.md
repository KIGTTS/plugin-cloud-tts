# 云端语音合成协议

版本 0.1.1，SDK API 28。账户、语音包、声音分层管理；预设与克隆声音分开。用户创建语音包并填写所需声音 ID 后才加入列表，不自动导入供应商全部声音。同一远端声音可添加到多个账户，各有独立本地 ID；语音包显示实际服务商标签。

| 服务商 | 接口 | 凭据 | 克隆声音 |
| --- | --- | --- | --- |
| 腾讯云 | TextToVoice，2019-08-23；TC3-HMAC-SHA256 | SecretId、SecretKey | 已有数字 VoiceType 或 FastVoiceType；不提供声音训练 |
| 阿里云 | NLS REST TTS；CreateToken RPC 获取并缓存 Token | AccessKey ID、AccessKey Secret、AppKey | 当前接口未接入，添加页仅提供预设声音 |
| Azure | SSML REST TTS；Speech Key | Speech Key；地址包含区域 | 已部署的自定义声音与 deploymentId；不提供声音训练 |
| 百度云 | text2audio；OAuth client_credentials | API Key、Secret Key | 当前接口未接入，添加页仅提供预设声音 |
| 讯飞 | 在线合成 WebSocket；HMAC-SHA256 签名 | API Key、API Secret、App ID | 当前接口未接入，添加页仅提供预设声音 |

输出为 16 kHz、16 位、单声道 PCM。会剥除合法 WAV 文件头，并拒绝 MP3 等压缩音频误作 PCM。长文本按各接口字符和 UTF-8 字节上限分段，不拆开 Unicode 补充字符。自定义接口地址须与对应厂商协议一致。

HTTP/WebSocket 请求支持取消并在结束后关闭资源；响应大小受限；厂商错误中的凭据会脱敏。Token 根据有效期复用。配置完整只代表可发起请求，不代表密钥已经联网验证。

语音合成服务可按包重命名、改备注、头像、置顶、排序和删除。账户关闭后不参与可用声音聚合。`arzue` 旧配置迁移为 `azure`，占位域名替换为真实默认地址。

本轮验证为注入传输的协议与页面回归、Android 编译和依赖收集；未提供真实凭据，尚未进行厂商实云合成与声音可用性验收。

## 官方文档

- [腾讯云语音合成](https://cloud.tencent.com/document/product/1073/37995)
- [阿里云 RESTful 合成](https://help.aliyun.com/en/isi/user-guide/restful-api-3)
- [阿里云获取 Token](https://help.aliyun.com/zh/isi/getting-started/use-http-or-https-to-obtain-an-access-token)
- [Azure REST 合成](https://learn.microsoft.com/en-us/azure/cognitive-services/speech-service/rest-text-to-speech)
- [百度短文本合成](https://ai.baidu.com/ai-doc/SPEECH/mlbxh7xie)
- [讯飞在线合成](https://www.xfyun.cn/doc/tts/online_tts/API.html)