package mihon.feature.readaloud

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import mihon.feature.readaloud.client.ElevenLabsClient
import mihon.feature.readaloud.client.ElevenLabsException
import mihon.feature.readaloud.model.ElevenLabsModel
import mihon.feature.readaloud.model.Turn
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ElevenLabsClientTest {

    @field:TempDir
    lateinit var tempDir: File

    @Test
    fun `ElevenLabsModel resolves turbo as default fallback`() {
        ElevenLabsModel.fromId(null) shouldBe ElevenLabsModel.TURBO_V2_5
        ElevenLabsModel.fromId("unknown_model") shouldBe ElevenLabsModel.TURBO_V2_5
        ElevenLabsModel.fromId("eleven_multilingual_v2") shouldBe ElevenLabsModel.MULTILINGUAL_V2
        ElevenLabsModel.fromId("eleven_v3") shouldBe ElevenLabsModel.ELEVEN_V3
    }

    @Test
    fun `fetchSubscription parses active free tier subscription`() = runTest {
        val mockJson = """
            {
              "tier": "free",
              "character_count": 2500,
              "character_limit": 10000,
              "can_extend_character_limit": false,
              "status": "active",
              "next_character_count_reset_unix": 1735689600
            }
        """.trimIndent()

        val interceptor = Interceptor { chain ->
            val req = chain.request()
            req.url.encodedPath shouldBe "/v1/user/subscription"
            req.header("xi-api-key") shouldBe "valid-key"
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(mockJson.toResponseBody("application/json".toMediaType()))
                .build()
        }

        val okHttpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val client = ElevenLabsClient(
            client = okHttpClient,
            apiKeyProvider = { "valid-key" },
            baseUrl = "https://mock.elevenlabs.io",
        )

        val sub = client.fetchSubscription()
        sub shouldNotBe null
        sub!!.tier shouldBe "free"
        sub.isFreeTier shouldBe true
        sub.characterCount shouldBe 2500L
        sub.characterLimit shouldBe 10000L
        sub.remainingCharacters shouldBe 7500L
        sub.isQuotaExhausted shouldBe false
        sub.status shouldBe "active"
        sub.nextResetUnix shouldBe 1735689600L
    }

    @Test
    fun `textToSpeech sends correct payload and writes audio stream to file`() = runTest {
        val dummyAudioBytes = byteArrayOf(0x49, 0x44, 0x33, 0x03, 0x00) // ID3 MP3 header

        val interceptor = Interceptor { chain ->
            val req = chain.request()
            req.url.encodedPath shouldBe "/v1/text-to-speech/voice123"
            req.header("xi-api-key") shouldBe "test-key"
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(dummyAudioBytes.toResponseBody("audio/mpeg".toMediaType()))
                .build()
        }

        val okHttpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val client = ElevenLabsClient(
            client = okHttpClient,
            apiKeyProvider = { "test-key" },
            baseUrl = "https://mock.elevenlabs.io",
        )

        val outputFile = File(tempDir, "output.mp3")
        client.textToSpeech(
            text = "Hello world",
            voiceId = "voice123",
            modelId = "eleven_turbo_v2_5",
            outputFile = outputFile,
        )

        outputFile.exists() shouldBe true
        outputFile.readBytes() shouldBe dummyAudioBytes
    }

    @Test
    fun `synthesize with multipleVoices disabled combines turns into single TTS call`() = runTest {
        var callCount = 0
        val dummyAudioBytes = byteArrayOf(0x01, 0x02, 0x03)

        val interceptor = Interceptor { chain ->
            callCount++
            val req = chain.request()
            req.url.encodedPath shouldBe "/v1/text-to-speech/narrator_id"
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(dummyAudioBytes.toResponseBody("audio/mpeg".toMediaType()))
                .build()
        }

        val okHttpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val client = ElevenLabsClient(
            client = okHttpClient,
            apiKeyProvider = { "test-key" },
            baseUrl = "https://mock.elevenlabs.io",
        )

        val turns = listOf(
            Turn("Narrator", "Line 1"),
            Turn("Speaker 1", "Line 2"),
            Turn("Speaker 2", "Line 3"),
        )

        val outputFile = File(tempDir, "combined.mp3")
        client.synthesize(
            turns = turns,
            narratorVoiceId = "narrator_id",
            secondaryVoiceId = "secondary_id",
            modelId = "eleven_turbo_v2_5",
            multipleVoices = false,
            outputFile = outputFile,
        )

        callCount shouldBe 1
        outputFile.exists() shouldBe true
        outputFile.readBytes() shouldBe dummyAudioBytes
    }

    @Test
    fun `synthesize gracefully falls back from dialogue to Turbo v2_5 on HTTP 402`() = runTest {
        val requests = mutableListOf<String>()
        val dummyAudio = byteArrayOf(0xAA.toByte(), 0xBB.toByte())

        val interceptor = Interceptor { chain ->
            val req = chain.request()
            requests.add(req.url.encodedPath)

            if (req.url.encodedPath == "/v1/text-to-dialogue") {
                // Free tier paywall 402 response from ElevenLabs
                val errorJson = """
                    {
                      "detail": {
                        "status": "quota_exceeded",
                        "message": "This request exceeds your quota will not be processed."
                      }
                    }
                """.trimIndent()
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(402)
                    .message("Payment Required")
                    .body(errorJson.toResponseBody("application/json".toMediaType()))
                    .build()
            } else {
                // Fallback standard TTS success
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(dummyAudio.toResponseBody("audio/mpeg".toMediaType()))
                    .build()
            }
        }

        val okHttpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val client = ElevenLabsClient(
            client = okHttpClient,
            apiKeyProvider = { "test-key" },
            baseUrl = "https://mock.elevenlabs.io",
        )

        val turns = listOf(
            Turn("Narrator", "A long time ago..."),
        )

        val outputFile = File(tempDir, "fallback.mp3")
        client.synthesize(
            turns = turns,
            narratorVoiceId = "narrator_id",
            secondaryVoiceId = "secondary_id",
            modelId = "eleven_v3",
            multipleVoices = true,
            outputFile = outputFile,
        )

        // Verifies text-to-dialogue was tried first, then fell back to standard TTS
        requests.size shouldBe 2
        requests[0] shouldBe "/v1/text-to-dialogue"
        requests[1] shouldBe "/v1/text-to-speech/narrator_id"

        outputFile.exists() shouldBe true
        outputFile.readBytes() shouldBe dummyAudio
    }

    @Test
    fun `synthesize rescues secondary voice with narrator voice on library voice restriction`() = runTest {
        val requests = mutableListOf<String>()
        val dummyAudio = byteArrayOf(0x01, 0x02, 0x03)

        val interceptor = Interceptor { chain ->
            val req = chain.request()
            requests.add(req.url.encodedPath)

            if (req.url.encodedPath.contains("library_voice")) {
                val errorJson = """
                    {
                      "detail": {
                        "status": "payment_required",
                        "message": "Free users cannot use library voices via the API."
                      }
                    }
                """.trimIndent()
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(402)
                    .message("Payment Required")
                    .body(errorJson.toResponseBody("application/json".toMediaType()))
                    .build()
            } else {
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(dummyAudio.toResponseBody("audio/mpeg".toMediaType()))
                    .build()
            }
        }

        val okHttpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val client = ElevenLabsClient(
            client = okHttpClient,
            apiKeyProvider = { "test-key" },
            baseUrl = "https://mock.elevenlabs.io",
        )

        val turns = listOf(
            Turn("Speaker 1", "Hey there!"),
        )

        val outputFile = File(tempDir, "rescued.mp3")
        client.synthesize(
            turns = turns,
            narratorVoiceId = "safe_narrator",
            secondaryVoiceId = "library_voice",
            modelId = "eleven_turbo_v2_5",
            multipleVoices = true,
            outputFile = outputFile,
        )

        requests.size shouldBe 2
        requests[0] shouldBe "/v1/text-to-speech/library_voice"
        requests[1] shouldBe "/v1/text-to-speech/safe_narrator"

        outputFile.exists() shouldBe true
        outputFile.readBytes() shouldBe dummyAudio
    }
}
