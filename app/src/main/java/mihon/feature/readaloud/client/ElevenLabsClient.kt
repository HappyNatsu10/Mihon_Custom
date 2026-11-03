package mihon.feature.readaloud.client

import kotlinx.coroutines.delay
import logcat.LogPriority
import logcat.logcat
import mihon.feature.readaloud.model.ElevenLabsVoice
import mihon.feature.readaloud.model.Turn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class ElevenLabsException(message: String, val statusCode: Int = 0) : Exception(message)

class ElevenLabsClient(
    private val client: OkHttpClient,
    private val apiKeyProvider: () -> String,
) {
    companion object {
        private const val BASE_URL = "https://api.elevenlabs.io"
        const val DEFAULT_NARRATOR_VOICE_ID = "21m00Tcm4TlvDq8ikWAM" // Rachel
        const val DEFAULT_SECONDARY_VOICE_ID = "AZnzlk1XvdvUeBnXmlld" // Domi
    }

    suspend fun textToDialogue(
        turns: List<Turn>,
        narratorVoiceId: String,
        secondaryVoiceId: String,
        languageCode: String = "en",
        modelId: String = "eleven_v3",
        outputFile: File,
    ): File {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank()) {
            throw ElevenLabsException("ElevenLabs API Key is missing. Please set it in Settings -> Read aloud.")
        }

        val inputsArray = JSONArray()
        for (turn in turns) {
            val voiceId = when {
                turn.speaker.contains("2") -> secondaryVoiceId.ifBlank { DEFAULT_SECONDARY_VOICE_ID }
                turn.speaker.contains("1") -> secondaryVoiceId.ifBlank { DEFAULT_SECONDARY_VOICE_ID }
                else -> narratorVoiceId.ifBlank { DEFAULT_NARRATOR_VOICE_ID }
            }

            // Prepend tags e.g. [excited]
            val tagPrefix = if (turn.tags.isNotEmpty()) {
                turn.tags.joinToString(" ") { "[$it]" } + " "
            } else {
                ""
            }

            inputsArray.put(
                JSONObject().apply {
                    put("text", tagPrefix + turn.text)
                    put("voice_id", voiceId)
                },
            )
        }

        val bodyJson = JSONObject().apply {
            put("inputs", inputsArray)
            put("model_id", modelId)
            put("language_code", languageCode)
            put("apply_text_normalization", "auto")
        }

        val request = Request.Builder()
            .url("$BASE_URL/v1/text-to-dialogue?output_format=mp3_44100_128")
            .header("xi-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        var retries = 0
        val maxRetries = 3

        while (true) {
            try {
                client.newCall(request).execute().use { response ->
                    val code = response.code
                    if (response.isSuccessful) {
                        val body =
                            response.body ?: throw ElevenLabsException("Empty audio response body from ElevenLabs")
                        outputFile.parentFile?.mkdirs()
                        FileOutputStream(outputFile).use { out ->
                            body.byteStream().copyTo(out)
                        }
                        return outputFile
                    }

                    val errString = response.body?.string().orEmpty()
                    logcat(LogPriority.WARN) { "ElevenLabs request failed ($code): $errString" }

                    when (code) {
                        401 -> throw ElevenLabsException(
                            "Invalid ElevenLabs API Key. Please check your credentials.",
                            401,
                        )
                        402 -> throw ElevenLabsException(
                            "ElevenLabs quota exceeded. Please upgrade your tier or wait for renewal.",
                            402,
                        )
                        422 -> throw ElevenLabsException(
                            "Invalid dialogue payload or unsupported voice for this model.",
                            422,
                        )
                        429 -> {
                            if (retries < maxRetries) {
                                retries++
                                val waitMs = retries * 2000L
                                logcat(LogPriority.INFO) {
                                    "ElevenLabs rate limit (429), backing off for ${waitMs}ms (retry $retries/$maxRetries)"
                                }
                                delay(waitMs)
                                return@use // loop again
                            } else {
                                throw ElevenLabsException(
                                    "ElevenLabs rate limit reached. Please try again shortly.",
                                    429,
                                )
                            }
                        }
                        else -> throw ElevenLabsException("ElevenLabs request failed (HTTP $code): $errString", code)
                    }
                }
            } catch (e: IOException) {
                if (retries < maxRetries) {
                    retries++
                    delay(retries * 1500L)
                } else {
                    throw ElevenLabsException("Network connection to ElevenLabs failed: ${e.message}")
                }
            }
        }
    }

    suspend fun fetchVoices(): List<ElevenLabsVoice> {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank()) return emptyList()

        val request = Request.Builder()
            .url("$BASE_URL/v2/voices?page_size=100")
            .header("xi-api-key", apiKey)
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logcat(LogPriority.WARN) { "Failed to fetch ElevenLabs voices (code ${response.code})" }
                    return emptyList()
                }
                val bodyStr = response.body?.string() ?: return emptyList()
                val json = JSONObject(bodyStr)
                val voicesArr = json.getJSONArray("voices")
                val voices = mutableListOf<ElevenLabsVoice>()

                for (i in 0 until voicesArr.length()) {
                    val obj = voicesArr.getJSONObject(i)
                    voices.add(
                        ElevenLabsVoice(
                            id = obj.getString("voice_id"),
                            name = obj.getString("name"),
                            category = obj.optString("category", ""),
                            previewUrl = obj.optString("preview_url", null),
                        ),
                    )
                }
                voices
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "Error fetching ElevenLabs voices: ${e.message}" }
            emptyList()
        }
    }
}
