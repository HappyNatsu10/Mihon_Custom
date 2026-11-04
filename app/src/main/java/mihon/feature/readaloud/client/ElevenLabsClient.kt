package mihon.feature.readaloud.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import logcat.LogPriority
import logcat.logcat
import mihon.feature.readaloud.model.ElevenLabsSubscription
import mihon.feature.readaloud.model.ElevenLabsVoice
import mihon.feature.readaloud.model.Turn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class ElevenLabsException(message: String, val statusCode: Int = 0) : Exception(message)

class ElevenLabsClient(
    private val client: OkHttpClient,
    private val apiKeyProvider: () -> String,
    private val baseUrl: String = BASE_URL,
) {
    companion object {
        const val BASE_URL = "https://api.elevenlabs.io"
        const val DEFAULT_NARRATOR_VOICE_ID = "21m00Tcm4TlvDq8ikWAM" // Rachel
        const val DEFAULT_SECONDARY_VOICE_ID = "AZnzlk1XvdvUeBnXmlld" // Domi
        const val DEFAULT_MODEL_ID = "eleven_turbo_v2_5"

        data class PresetVoice(
            val id: String,
            val name: String,
            val gender: String,
            val description: String,
        )

        val PRESET_FREE_VOICES = listOf(
            PresetVoice("21m00Tcm4TlvDq8ikWAM", "Rachel", "Female", "Calm, expressive narration"),
            PresetVoice("pNInz6obpgDQGcFmaJgB", "Adam", "Male", "Deep, smooth narrative"),
            PresetVoice("AZnzlk1XvdvUeBnXmlld", "Domi", "Female", "Energetic, dynamic dialogue"),
            PresetVoice("ErXwobaYiN019PkySvjV", "Antoni", "Male", "Well-rounded, natural narration"),
            PresetVoice("EXAVITQu4vr4xnSDxMaL", "Bella", "Female", "Soft, gentle narration"),
            PresetVoice("TxGEqnHWrfWFTfGW9XjX", "Josh", "Male", "Young, deep casual"),
        )
    }

    private fun getApiKey(): String {
        val apiKey = apiKeyProvider().trim().trim('"', '\'')
        if (apiKey.isBlank()) {
            throw ElevenLabsException("ElevenLabs API Key is missing. Please set it in Settings -> Read aloud.")
        }
        return apiKey
    }

    private fun parseError(code: Int, bodyString: String, isDialogue: Boolean = false): ElevenLabsException {
        val detailMsg = try {
            val root = Json.parseToJsonElement(bodyString).jsonObject
            val detail = root["detail"]
            when {
                detail is JsonObject -> {
                    detail["message"]?.jsonPrimitive?.content
                        ?: detail["status"]?.jsonPrimitive?.content
                        ?: ""
                }
                detail is JsonPrimitive -> detail.content
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }

        val message = when (code) {
            401 -> if (detailMsg.isNotBlank()) {
                "Invalid ElevenLabs API key: $detailMsg"
            } else {
                "Invalid ElevenLabs API Key. Please check your credentials."
            }
            402 -> {
                if (isDialogue) {
                    val fallbackHint = "Requires a paid plan. Switched to standard Turbo v2.5."
                    "ElevenLabs v3/Dialogue is restricted on your tier: ${detailMsg.ifBlank { fallbackHint }}"
                } else if (detailMsg.contains("library voice", ignoreCase = true) ||
                    detailMsg.contains("free users cannot use", ignoreCase = true)
                ) {
                    "ElevenLabs Free Tier restriction: Free users cannot use community Voice Library voices " +
                        "via the API. Please switch to a Premade voice (Rachel, Adam, Domi) in Settings, " +
                        "or upgrade your plan."
                } else if (detailMsg.isNotBlank()) {
                    "ElevenLabs quota/tier restriction: $detailMsg"
                } else {
                    "ElevenLabs monthly character quota exceeded. Please upgrade your tier or wait for renewal."
                }
            }
            403 -> if (detailMsg.isNotBlank()) {
                "ElevenLabs access restricted: $detailMsg"
            } else {
                "Access restricted. ElevenLabs may be blocking your IP or VPN."
            }
            422 -> if (detailMsg.isNotBlank()) {
                "ElevenLabs validation error: $detailMsg"
            } else {
                "Invalid payload or unsupported voice for this model."
            }
            429 -> "ElevenLabs rate limit reached. Please try again shortly."
            else -> if (detailMsg.isNotBlank()) {
                "ElevenLabs error (HTTP $code): $detailMsg"
            } else {
                "ElevenLabs request failed (HTTP $code)"
            }
        }
        return ElevenLabsException(message, code)
    }

    suspend fun fetchSubscription(): ElevenLabsSubscription? = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim().trim('"', '\'')
        if (apiKey.isBlank()) return@withContext null

        val request = Request.Builder()
            .url("$baseUrl/v1/user/subscription")
            .header("xi-api-key", apiKey)
            .header("Accept", "application/json")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logcat(LogPriority.WARN) { "Failed to fetch ElevenLabs subscription (HTTP ${response.code})" }
                    return@withContext null
                }
                val bodyStr = response.body.string()
                val json = Json.parseToJsonElement(bodyStr).jsonObject
                ElevenLabsSubscription(
                    tier = json["tier"]?.jsonPrimitive?.content ?: "unknown",
                    characterCount = json["character_count"]?.jsonPrimitive?.longOrNull ?: 0L,
                    characterLimit = json["character_limit"]?.jsonPrimitive?.longOrNull ?: 0L,
                    status = json["status"]?.jsonPrimitive?.content ?: "active",
                    nextResetUnix = json["next_character_count_reset_unix"]?.jsonPrimitive?.longOrNull,
                )
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "Error fetching ElevenLabs subscription: ${e.message}" }
            null
        }
    }

    suspend fun textToSpeech(
        text: String,
        voiceId: String,
        modelId: String = DEFAULT_MODEL_ID,
        outputFile: File,
        append: Boolean = false,
    ): File = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        val targetVoiceId = voiceId.ifBlank { DEFAULT_NARRATOR_VOICE_ID }

        val bodyJson = buildJsonObject {
            put("text", JsonPrimitive(text))
            put("model_id", JsonPrimitive(modelId))
            put(
                "voice_settings",
                buildJsonObject {
                    put("stability", JsonPrimitive(0.5))
                    put("similarity_boost", JsonPrimitive(0.75))
                },
            )
        }

        val request = Request.Builder()
            .url("$baseUrl/v1/text-to-speech/$targetVoiceId?output_format=mp3_44100_128")
            .header("xi-api-key", apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "audio/mpeg")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        var retries = 0
        val maxRetries = 3

        while (true) {
            try {
                client.newCall(request).execute().use { response ->
                    val code = response.code
                    if (response.isSuccessful) {
                        val body = response.body
                        outputFile.parentFile?.mkdirs()
                        FileOutputStream(outputFile, append).use { out ->
                            body.byteStream().copyTo(out)
                        }
                        return@withContext outputFile
                    }

                    val errString = response.body.string()
                    logcat(LogPriority.WARN) { "ElevenLabs TTS request failed ($code): $errString" }

                    if (code == 429 && retries < maxRetries) {
                        retries++
                        val waitMs = retries * 2000L
                        logcat(LogPriority.INFO) {
                            "ElevenLabs rate limit (429), backing off for ${waitMs}ms (retry $retries/$maxRetries)"
                        }
                        delay(waitMs)
                        return@use
                    }

                    throw parseError(code, errString, isDialogue = false)
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
        throw ElevenLabsException("Synthesis failed unexpectedly.")
    }

    suspend fun textToDialogue(
        turns: List<Turn>,
        narratorVoiceId: String,
        secondaryVoiceId: String,
        languageCode: String = "en",
        modelId: String = "eleven_v3",
        outputFile: File,
    ): File = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()

        val bodyJson = buildJsonObject {
            put(
                "inputs",
                buildJsonArray {
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

                        add(
                            buildJsonObject {
                                put("text", JsonPrimitive(tagPrefix + turn.text))
                                put("voice_id", JsonPrimitive(voiceId))
                            },
                        )
                    }
                },
            )
            put("model_id", JsonPrimitive(modelId))
            put("language_code", JsonPrimitive(languageCode))
            put("apply_text_normalization", JsonPrimitive("auto"))
        }

        val request = Request.Builder()
            .url("$baseUrl/v1/text-to-dialogue?output_format=mp3_44100_128")
            .header("xi-api-key", apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "audio/mpeg")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        var retries = 0
        val maxRetries = 3

        while (true) {
            try {
                client.newCall(request).execute().use { response ->
                    val code = response.code
                    if (response.isSuccessful) {
                        val body = response.body
                        outputFile.parentFile?.mkdirs()
                        FileOutputStream(outputFile, false).use { out ->
                            body.byteStream().copyTo(out)
                        }
                        return@withContext outputFile
                    }

                    val errString = response.body.string()
                    logcat(LogPriority.WARN) { "ElevenLabs dialogue request failed ($code): $errString" }

                    if (code == 429 && retries < maxRetries) {
                        retries++
                        val waitMs = retries * 2000L
                        logcat(LogPriority.INFO) {
                            "ElevenLabs rate limit (429), backing off for ${waitMs}ms (retry $retries/$maxRetries)"
                        }
                        delay(waitMs)
                        return@use
                    }

                    throw parseError(code, errString, isDialogue = true)
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
        throw ElevenLabsException("Dialogue synthesis failed unexpectedly.")
    }

    suspend fun synthesize(
        turns: List<Turn>,
        narratorVoiceId: String,
        secondaryVoiceId: String,
        languageCode: String = "en",
        modelId: String = DEFAULT_MODEL_ID,
        multipleVoices: Boolean = true,
        outputFile: File,
    ): File = withContext(Dispatchers.IO) {
        if (turns.isEmpty()) {
            throw ElevenLabsException("No text to synthesize.")
        }

        // If user specifically requested eleven_v3 AND multipleVoices is on, try text-to-dialogue first
        if (modelId == "eleven_v3" && multipleVoices) {
            try {
                return@withContext textToDialogue(
                    turns = turns,
                    narratorVoiceId = narratorVoiceId,
                    secondaryVoiceId = secondaryVoiceId,
                    languageCode = languageCode,
                    modelId = modelId,
                    outputFile = outputFile,
                )
            } catch (e: ElevenLabsException) {
                if (e.statusCode == 402 || e.statusCode == 422) {
                    val logMsg = "ElevenLabs dialogue failed (${e.statusCode}: ${e.message}). " +
                        "Falling back to Turbo v2.5 TTS..."
                    logcat(LogPriority.WARN) { logMsg }
                    // Fall through to standard TTS using eleven_turbo_v2_5 below
                } else {
                    throw e
                }
            }
        }

        // Effective model for TTS (cannot use eleven_v3 on /v1/text-to-speech)
        val effectiveModel = if (modelId == "eleven_v3") DEFAULT_MODEL_ID else modelId

        // Case 1: Single voice (or multiple voices disabled)
        // Combine all turns into a single request
        if (!multipleVoices) {
            val fullText = turns.joinToString("\n\n") { it.text }
            return@withContext textToSpeech(
                text = fullText,
                voiceId = narratorVoiceId,
                modelId = effectiveModel,
                outputFile = outputFile,
                append = false,
            )
        }

        // Case 2: Multi-voice via standard TTS
        val distinctSpeakers = turns.map { it.speaker }.distinct()
        if (distinctSpeakers.size <= 1) {
            val singleVoiceId = if (turns.first().speaker.contains("2") || turns.first().speaker.contains("1")) {
                secondaryVoiceId.ifBlank { DEFAULT_SECONDARY_VOICE_ID }
            } else {
                narratorVoiceId.ifBlank { DEFAULT_NARRATOR_VOICE_ID }
            }
            val fullText = turns.joinToString("\n\n") { it.text }
            try {
                return@withContext textToSpeech(
                    text = fullText,
                    voiceId = singleVoiceId,
                    modelId = effectiveModel,
                    outputFile = outputFile,
                    append = false,
                )
            } catch (e: ElevenLabsException) {
                val isLibraryRestriction = e.statusCode == 402 && (
                    e.message?.contains("library voice", ignoreCase = true) == true ||
                        e.message?.contains("quota/tier", ignoreCase = true) == true ||
                        e.message?.contains("tier restriction", ignoreCase = true) == true
                    )
                if (isLibraryRestriction) {
                    val safeNarrator = narratorVoiceId.ifBlank { DEFAULT_NARRATOR_VOICE_ID }
                    val fallbackVoice = if (singleVoiceId != safeNarrator) {
                        safeNarrator
                    } else {
                        DEFAULT_NARRATOR_VOICE_ID
                    }
                    if (singleVoiceId != fallbackVoice) {
                        logcat(LogPriority.WARN) {
                            "Voice $singleVoiceId hit ElevenLabs tier/library restriction. Rescuing with $fallbackVoice"
                        }
                        return@withContext textToSpeech(
                            text = fullText,
                            voiceId = fallbackVoice,
                            modelId = effectiveModel,
                            outputFile = outputFile,
                            append = false,
                        )
                    }
                }
                throw e
            }
        }

        // Group consecutive turns by the same voice to minimize API requests
        data class TurnBatch(val voiceId: String, val text: String)
        val batches = mutableListOf<TurnBatch>()
        for (turn in turns) {
            val voiceId = when {
                turn.speaker.contains("2") -> secondaryVoiceId.ifBlank { DEFAULT_SECONDARY_VOICE_ID }
                turn.speaker.contains("1") -> secondaryVoiceId.ifBlank { DEFAULT_SECONDARY_VOICE_ID }
                else -> narratorVoiceId.ifBlank { DEFAULT_NARRATOR_VOICE_ID }
            }
            val last = batches.lastOrNull()
            if (last != null && last.voiceId == voiceId) {
                batches[batches.lastIndex] = TurnBatch(voiceId, last.text + "\n" + turn.text)
            } else {
                batches.add(TurnBatch(voiceId, turn.text))
            }
        }

        if (outputFile.exists()) {
            outputFile.delete()
        }

        for (batch in batches) {
            try {
                textToSpeech(
                    text = batch.text,
                    voiceId = batch.voiceId,
                    modelId = effectiveModel,
                    outputFile = outputFile,
                    append = true,
                )
            } catch (e: ElevenLabsException) {
                val isLibraryRestriction = e.statusCode == 402 && (
                    e.message?.contains("library voice", ignoreCase = true) == true ||
                        e.message?.contains("quota/tier", ignoreCase = true) == true ||
                        e.message?.contains("tier restriction", ignoreCase = true) == true
                    )
                if (isLibraryRestriction) {
                    val safeNarrator = narratorVoiceId.ifBlank { DEFAULT_NARRATOR_VOICE_ID }
                    val fallbackVoice = if (batch.voiceId != safeNarrator) {
                        safeNarrator
                    } else {
                        DEFAULT_NARRATOR_VOICE_ID
                    }
                    if (batch.voiceId != fallbackVoice) {
                        logcat(LogPriority.WARN) {
                            "Voice ${batch.voiceId} hit ElevenLabs tier/library restriction. Rescuing with $fallbackVoice"
                        }
                        textToSpeech(
                            text = batch.text,
                            voiceId = fallbackVoice,
                            modelId = effectiveModel,
                            outputFile = outputFile,
                            append = true,
                        )
                        continue
                    }
                }
                throw e
            }
        }

        return@withContext outputFile
    }

    suspend fun fetchVoices(): List<ElevenLabsVoice> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim().trim('"', '\'')
        if (apiKey.isBlank()) return@withContext emptyList()

        val request = Request.Builder()
            .url("$baseUrl/v1/voices")
            .header("xi-api-key", apiKey)
            .header("Accept", "application/json")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logcat(LogPriority.WARN) { "Failed to fetch ElevenLabs voices (code ${response.code})" }
                    return@withContext emptyList()
                }
                val bodyStr = response.body.string()
                val json = Json.parseToJsonElement(bodyStr).jsonObject
                val voicesArr = json["voices"]?.jsonArray ?: return@withContext emptyList()
                val voices = mutableListOf<ElevenLabsVoice>()

                for (item in voicesArr) {
                    val obj = item.jsonObject
                    val voiceId = obj["voice_id"]?.jsonPrimitive?.content ?: continue
                    val name = obj["name"]?.jsonPrimitive?.content ?: ""
                    val category = obj["category"]?.jsonPrimitive?.content ?: ""
                    val previewUrl = obj["preview_url"]?.jsonPrimitive?.content
                    val availableForTiers = obj["available_for_tiers"]?.jsonArray?.mapNotNull {
                        try {
                            it.jsonPrimitive.content
                        } catch (_: Exception) {
                            null
                        }
                    } ?: emptyList()
                    voices.add(
                        ElevenLabsVoice(
                            id = voiceId,
                            name = name,
                            category = category,
                            previewUrl = previewUrl,
                            availableForTiers = availableForTiers,
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
