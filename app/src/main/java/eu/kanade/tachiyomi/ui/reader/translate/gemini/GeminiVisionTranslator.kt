package eu.kanade.tachiyomi.ui.reader.translate.gemini

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Base64
import eu.kanade.tachiyomi.ui.reader.translate.model.SourceLanguage
import eu.kanade.tachiyomi.ui.reader.translate.model.TextBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import logcat.logcat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import kotlin.math.min

class GeminiVisionTranslator(
    private val okHttpClient: OkHttpClient,
    private val apiKeyProvider: () -> String,
    private val modelProvider: () -> String = { "gemini-1.5-flash" },
    private val endpointProvider: () -> String = { "" },
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun translatePage(
        page: Bitmap,
        sourceLang: SourceLanguage,
        targetLang: String,
    ): Pair<List<TextBlock>, List<String>> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank()) {
            throw IllegalArgumentException("Gemini API key is required. Please set your API key in Reader Settings → Translation.")
        }

        val configuredModel = modelProvider().trim()
            .removePrefix("models/")
            .removePrefix("/models/")
            .ifBlank { "gemini-1.5-flash" }

        val cleanModel = when (configuredModel) {
            "gemini-pro", "gemini-pro-vision", "gemini-1.0-pro" -> "gemini-1.5-flash"
            else -> configuredModel
        }
        val customEndpoint = endpointProvider().trim()

        fun buildUrl(modelName: String, apiVersion: String = "v1beta"): String {
            if (customEndpoint.isNotBlank()) {
                val endpoint = customEndpoint.trim()
                return when {
                    endpoint.contains(":generateContent") -> {
                        if (endpoint.contains("?")) "$endpoint&key=$apiKey" else "$endpoint?key=$apiKey"
                    }
                    endpoint.endsWith("/v1beta") || endpoint.endsWith("/v1") -> {
                        "$endpoint/models/$modelName:generateContent?key=$apiKey"
                    }
                    endpoint.contains("/models/") -> {
                        "$endpoint:generateContent?key=$apiKey"
                    }
                    else -> {
                        val base = endpoint.trimEnd('/')
                        "$base/$apiVersion/models/$modelName:generateContent?key=$apiKey"
                    }
                }
            }
            return "https://generativelanguage.googleapis.com/$apiVersion/models/$modelName:generateContent?key=$apiKey"
        }

        // Downscale image if large to optimize network payload
        val maxDim = 1800
        val scale = if (page.width > maxDim || page.height > maxDim) {
            min(maxDim.toFloat() / page.width, maxDim.toFloat() / page.height)
        } else {
            1.0f
        }

        val scaledBitmap = if (scale < 1.0f) {
            Bitmap.createScaledBitmap(page, (page.width * scale).toInt(), (page.height * scale).toInt(), true)
        } else {
            page
        }

        val base64Image = ByteArrayOutputStream().use { stream ->
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        }

        if (scaledBitmap != page) {
            scaledBitmap.recycle()
        }

        val targetLangName = when (targetLang.lowercase()) {
            "en" -> "English"
            "es" -> "Spanish"
            "fr" -> "French"
            "de" -> "German"
            "pt" -> "Portuguese"
            "id" -> "Indonesian"
            else -> targetLang
        }

        val prompt = """
            You are a professional manga localization expert and translator.
            1. Detect all speech bubbles, dialogue captions, and text on this manga page.
            2. Translate all dialogue into natural, fluent $targetLangName dialogue:
               - Infer missing subjects and pronouns (I, you, he, she, we) from the visual context and speaker.
               - Use natural conversational English and contractions (e.g. "I'm", "don't", "let's") instead of stiff literal machine translation.
               - Connect split speech bubbles across panels so thoughts flow as coherent, grammatical sentences.
               - Adapt Japanese/Korean idioms and slang to natural equivalent expressions.
            3. Return a JSON array where each object has:
               - "box_2d": [ymin, xmin, ymax, xmax] as normalized integers from 0 to 1000 representing the bounding box of the speech bubble
               - "original_text": string
               - "translated_text": string
            Respond ONLY with the JSON array, no explanation, no markdown backticks.
        """.trimIndent()

        val requestBodyJson = """
            {
              "contents": [
                {
                  "parts": [
                    { "text": ${kotlinx.serialization.json.JsonPrimitive(prompt)} },
                    {
                      "inline_data": {
                        "mime_type": "image/jpeg",
                        "data": "$base64Image"
                      }
                    }
                  ]
                }
              ],
              "generationConfig": {
                "temperature": 0.2
              }
            }
        """.trimIndent()

        val candidatesToTry = listOf(
            Pair("v1beta", cleanModel),
            Pair("v1beta", "gemini-1.5-flash-latest"),
            Pair("v1beta", "gemini-1.5-flash"),
            Pair("v1", cleanModel),
            Pair("v1", "gemini-1.5-flash"),
            Pair("v1beta", "gemini-2.0-flash-exp"),
            Pair("v1beta", "gemini-1.5-pro-latest"),
            Pair("v1", "gemini-1.5-pro"),
        ).distinct()

        var lastResponseCode = 0
        var lastResponseBody = ""

        for ((version, tryModel) in candidatesToTry) {
            val url = buildUrl(tryModel, version)
            val request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey)
                .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            val response = try {
                okHttpClient.newCall(request).execute()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Network error calling Gemini API with model $tryModel ($version): ${e.message}" }
                throw IllegalStateException("Network connection failed. Please check your internet connection.", e)
            }

            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                return@withContext parseGeminiResponse(body, page.width, page.height)
            }

            lastResponseCode = response.code
            lastResponseBody = body

            if (response.code == 404) {
                logcat(LogPriority.WARN) { "Model $tryModel on $version returned 404, attempting fallback..." }
                continue
            } else {
                // If 400/403/429, don't keep trying all models needlessly
                break
            }
        }

        val serverErrorSnippet = if (lastResponseBody.isNotBlank()) {
            val jsonMsg = try {
                val element = Json.parseToJsonElement(lastResponseBody)
                element.jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
            } catch (_: Exception) { null }
            jsonMsg ?: lastResponseBody.take(300)
        } else {
            "No error details returned by Google"
        }

        val errorMsg = when (lastResponseCode) {
            400, 403 -> "Invalid Gemini API key or unauthorized request: $serverErrorSnippet"
            404 -> "Gemini API error (HTTP 404 Not Found): $serverErrorSnippet (Tried: ${candidatesToTry.map { "${it.second}@${it.first}" }.joinToString()})"
            429 -> "Gemini API rate limit exceeded: $serverErrorSnippet"
            else -> "Gemini API error (HTTP $lastResponseCode): $serverErrorSnippet"
        }
        throw IllegalStateException(errorMsg)
    }

    private fun parseGeminiResponse(
        responseBody: String,
        originalWidth: Int,
        originalHeight: Int,
    ): Pair<List<TextBlock>, List<String>> {
        val root = json.parseToJsonElement(responseBody).jsonObject
        val candidates = root["candidates"]?.jsonArray
        val textPart = candidates?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("text")?.jsonPrimitive?.content ?: ""

        val cleanedJson = textPart.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        if (cleanedJson.isBlank() || cleanedJson == "[]") {
            return Pair(emptyList(), emptyList())
        }

        val jsonArray = json.parseToJsonElement(cleanedJson).jsonArray
        val blocks = mutableListOf<TextBlock>()
        val translations = mutableListOf<String>()

        for (item in jsonArray) {
            val obj = item.jsonObject
            val boxArr = obj["box_2d"]?.jsonArray ?: continue
            val translatedText = obj["translated_text"]?.jsonPrimitive?.content ?: ""
            val originalText = obj["original_text"]?.jsonPrimitive?.content ?: ""

            if (boxArr.size == 4) {
                val ymin = boxArr[0].jsonPrimitive.content.toFloatOrNull() ?: 0f
                val xmin = boxArr[1].jsonPrimitive.content.toFloatOrNull() ?: 0f
                val ymax = boxArr[2].jsonPrimitive.content.toFloatOrNull() ?: 0f
                val xmax = boxArr[3].jsonPrimitive.content.toFloatOrNull() ?: 0f

                val rect = RectF(
                    (xmin / 1000f) * originalWidth,
                    (ymin / 1000f) * originalHeight,
                    (xmax / 1000f) * originalWidth,
                    (ymax / 1000f) * originalHeight,
                )

                blocks.add(TextBlock(originalText, rect))
                translations.add(translatedText)
            }
        }

        return Pair(blocks, translations)
    }
}
