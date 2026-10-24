package com.kigtts.engines.cloudtts

import com.kigtts.plugin.api.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class CloudTtsProtocolsTest {
    private val time = Instant.ofEpochSecond(1551113065)
    private val pcm = byteArrayOf(1, 0, 2, 0)
    private fun config(provider: CloudTtsProvider) = CloudTtsConfig("account", provider, provider.label, provider.defaultEndpoint, "test-key", "test-secret", "test-app")
    private fun voice(remoteId: String = "1", kind: CloudVoiceKind = CloudVoiceKind.PRESET) = CloudVoice("local", "pack", "account", "声音", "zh-CN", kind, remoteId = remoteId)
    @Test fun tencentSignsOfficialPayloadAndUsesRemoteVoiceId() = runTest {
        val transport = FakeTransport { request ->
            assertEquals("TextToVoice", request.headers["X-TC-Action"])
            assertEquals("2019-08-23", request.headers["X-TC-Version"])
            val body = JSONObject(String(request.body!!))
            assertEquals("pcm", body.getString("Codec")); assertEquals(16000, body.getInt("SampleRate")); assertEquals(1001, body.getInt("VoiceType"))
            assertEquals(0.0, body.getDouble("Speed"), 0.001); assertEquals("你好", body.getString("Text"))
            json("""{"Response":{"Audio":"AQACAA=="}}""")
        }
        assertArrayEquals(pcm, CloudTtsProtocols(transport) { time }.synthesize(config(CloudTtsProvider.TENCENT), voice("1001"), TtsRequest("你好")).bytes)
    }
    @Test fun tencentCloneUsesFastVoiceTypeRatherThanNumericVoiceCoercion() = runTest {
        val transport = FakeTransport { request ->
            val body = JSONObject(String(request.body!!)); assertEquals(200000000, body.getInt("VoiceType")); assertEquals("WCHN-demo", body.getString("FastVoiceType"))
            json("""{"Response":{"Audio":"AQACAA=="}}""")
        }
        CloudTtsProtocols(transport).synthesize(config(CloudTtsProvider.TENCENT), voice("WCHN-demo", CloudVoiceKind.CLONE), TtsRequest("文字"))
    }
    @Test fun aliyunPostsJsonWithNlsTokenAndPcmAndReusesToken() = runTest {
        var tokenRequests = 0
        val transport = FakeTransport { request ->
            if (request.url.contains("nls-meta")) { tokenRequests++; json("""{"Token":{"Id":"nls-token","ExpireTime":9999999999}}""") }
            else { val body = JSONObject(String(request.body!!)); assertEquals("nls-token", request.headers["X-NLS-Token"]); assertEquals("xiaoyun", body.getString("voice")); assertEquals("pcm", body.getString("format")); assertEquals(500, body.getInt("speech_rate")); binary() }
        }
        val protocols = CloudTtsProtocols(transport) { time }
        repeat(2) { protocols.synthesize(config(CloudTtsProvider.ALIYUN), voice("xiaoyun"), TtsRequest("文本", rate = 2f)) }
        assertEquals(1, tokenRequests)
    }
    @Test fun azureEscapesTextAndAttributesAndRequestsRawPcm() = runTest {
        val transport = FakeTransport { request ->
            assertEquals("test-key", request.headers["Ocp-Apim-Subscription-Key"])
            assertEquals("raw-16khz-16bit-mono-pcm", request.headers["X-Microsoft-OutputFormat"])
            assertEquals("application/ssml+xml", request.contentType)
            val ssml = String(request.body!!); assertTrue(ssml.contains("a&lt;b&amp;c")); assertTrue(ssml.contains("name=\"voice&amp;&quot;\"")); binary()
        }
        CloudTtsProtocols(transport).synthesize(config(CloudTtsProvider.AZURE), voice("voice&\""), TtsRequest("a<b&c"))
    }
    @Test fun azureCloneRequiresDeploymentAndPreservesCustomEndpoint() = runTest {
        val transport = FakeTransport { request -> assertTrue(request.url.contains("deploymentId=deploy%201")); binary() }
        val c = config(CloudTtsProvider.AZURE)
        try { CloudTtsProtocols(transport).synthesize(c, voice("custom", CloudVoiceKind.CLONE), TtsRequest("text")); fail() } catch (error: EngineException) { assertEquals(EngineError.NOT_CONFIGURED, error.code) }
        CloudTtsProtocols(transport).synthesize(c.copy(model = "deploy 1"), voice("custom", CloudVoiceKind.CLONE), TtsRequest("text"))
    }
    @Test fun baiduUsesOauthAndUrlEncodedTextWithPcmOutput() = runTest {
        val transport = FakeTransport { request ->
            if (request.url.contains("oauth")) json("""{"access_token":"baidu-token","expires_in":3600}""")
            else { val form = query(String(request.body!!)); assertEquals("你好 + &", form["tex"]); assertEquals("4", form["aue"]); assertEquals("baidu-token", form["tok"]); assertEquals("1", form["per"]); binary() }
        }
        CloudTtsProtocols(transport).synthesize(config(CloudTtsProvider.BAIDU), voice(), TtsRequest("你好 + &"))
    }
    @Test fun xfyunAccumulatesAudioFramesAndRequestsUtf8RawPcm() = runTest {
        val transport = object : SpeechTransport {
            override suspend fun http(request: SpeechHttpRequest) = error("not HTTP")
            override suspend fun socket(url: String, frames: List<SpeechFrame>, onMessage: (String) -> Boolean) {
                assertEquals("tts-api.xfyun.cn", URI(url).host)
                assertTrue(query(URI(url).rawQuery).containsKey("authorization"))
                val request = JSONObject(frames.single().text); val business = request.getJSONObject("business")
                assertEquals("raw", business.getString("aue")); assertEquals("UTF8", business.getString("tte")); assertEquals("xiaoyan", business.getString("vcn"))
                assertEquals("你好", String(Base64.getDecoder().decode(request.getJSONObject("data").getString("text"))))
                assertFalse(onMessage("""{"code":0,"data":{"status":1,"audio":"AQA="}}"""))
                assertTrue(onMessage("""{"code":0,"data":{"status":2,"audio":"AgA="}}"""))
            }
        }
        assertArrayEquals(pcm, CloudTtsProtocols(transport).synthesize(config(CloudTtsProvider.IFlytek), voice("xiaoyan"), TtsRequest("你好")).bytes)
    }
    @Test fun wavIsParsedInsteadOfEmittingHeaderAsPcmAndBadFormatsAreRejected() {
        val wav = SpeechPcm.wav(PcmAudio(pcm)); assertArrayEquals(pcm, SpeechPcm.output(wav).bytes)
        for (data in listOf(byteArrayOf(), "ID3audio".toByteArray(), byteArrayOf(1), "RIFFbad header".toByteArray())) {
            try { SpeechPcm.output(data); fail() } catch (_: EngineException) { }
        }
    }
    @Test fun cloneUnsupportedByStandardInterfacesFailsWithoutMakingNetworkCalls() = runTest {
        val transport = FakeTransport { error("no network") }
        for (provider in listOf(CloudTtsProvider.ALIYUN, CloudTtsProvider.BAIDU, CloudTtsProvider.IFlytek)) {
            try { CloudTtsProtocols(transport).synthesize(config(provider), voice("clone", CloudVoiceKind.CLONE), TtsRequest("text")); fail() }
            catch (error: EngineException) { assertEquals(EngineError.UNAVAILABLE, error.code); assertTrue(error.message!!.contains("克隆")) }
        }
    }
    @Test fun cloudJsonErrorsAreNotTreatedAsRawAudio() = runTest {
        val transport = FakeTransport { json("""{"err_no":502,"err_msg":"test-key bad credentials"}""") }
        try { CloudTtsProtocols(transport).synthesize(config(CloudTtsProvider.AZURE), voice(), TtsRequest("text")); fail() }
        catch (error: EngineException) { assertTrue(error.message!!.contains("502")); assertFalse(error.message!!.contains("test-key")) }
    }
    @Test fun textChunkingKeepsUnicodeAndProviderByteBoundsAndRateMapping() {
        val text = "你好😀世界".repeat(60)
        val parts = SpeechText.segments(text, 150, 450)
        assertEquals(text, parts.joinToString("")); assertTrue(parts.all { it.toByteArray().size <= 450 && it.codePointCount(0, it.length) <= 150 })
        assertFalse(parts.any { it.first().isLowSurrogate() || it.last().isHighSurrogate() })
        assertEquals(-2.0, SpeechText.tencentSpeed(0.6f), 0.0001); assertEquals(0.0, SpeechText.tencentSpeed(1f), 0.0001); assertEquals(6.0, SpeechText.tencentSpeed(2.5f), 0.0001)
    }
    private class FakeTransport(val handler: (SpeechHttpRequest) -> SpeechHttpResponse) : SpeechTransport {
        override suspend fun http(request: SpeechHttpRequest) = handler(request)
        override suspend fun socket(url: String, frames: List<SpeechFrame>, onMessage: (String) -> Boolean) = error("unexpected websocket")
    }
    private fun json(text: String) = SpeechHttpResponse(200, text.toByteArray(), "application/json")
    private fun binary() = SpeechHttpResponse(200, pcm, "audio/basic;codec=pcm;rate=16000;channel=1")
    private fun query(text: String) = text.split('&').associate { val pair = it.split('=', limit = 2); URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8") }
}
