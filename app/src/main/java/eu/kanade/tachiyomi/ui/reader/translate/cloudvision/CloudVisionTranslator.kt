package eu.kanade.tachiyomi.ui.reader.translate.cloudvision

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Base64
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
import kotlin.math.max
import kotlin.math.min

class CloudVisionTranslator(
    private val okHttpClient: OkHttpClient,
    private val apiKeyProvider: () -> String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun recognize(page: Bitmap): List<TextBlock> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank()) {
            throw IllegalArgumentException("Google Cloud Vision API key is required. Please set your API key in Reader Settings → Translation.")
        }

        // Downscale image if too large (max 2048px on longest side)
        val maxDim = 2048
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
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        }

        if (scaledBitmap != page) {
            scaledBitmap.recycle()
        }

        val endpoint = "https://vision.googleapis.com/v1/images:annotate?key=$apiKey"
        val requestJson = """
            {
              "requests": [
                {
                  "image": {
                    "content": "$base64Image"
                  },
                  "features": [
                    {
                      "type": "DOCUMENT_TEXT_DETECTION"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val request = Request.Builder()
            .url(endpoint)
            .post(requestJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val responseBody = okHttpClient.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                logcat(LogPriority.ERROR) { "Cloud Vision request failed: HTTP ${response.code} - $body" }
                throw RuntimeException("Cloud Vision request failed with HTTP ${response.code}")
            }
            body
        }

        val parsed = json.parseToJsonElement(responseBody).jsonObject
        val responses = parsed["responses"]?.jsonArray
        val firstResponse = responses?.firstOrNull()?.jsonObject ?: return@withContext emptyList()

        val blocks = mutableListOf<TextBlock>()
        val fullText = firstResponse["fullTextAnnotation"]?.jsonObject
        val pages = fullText?.get("pages")?.jsonArray

        val scaleX = 1.0f / scale
        val scaleY = 1.0f / scale

        pages?.forEach { pageElem ->
            val pageObj = pageElem.jsonObject
            val pageBlocks = pageObj["blocks"]?.jsonArray
            pageBlocks?.forEach { blockElem ->
                val blockObj = blockElem.jsonObject
                val paragraphs = blockObj["paragraphs"]?.jsonArray
                paragraphs?.forEach { paraElem ->
                    val paraObj = paraElem.jsonObject
                    val words = paraObj["words"]?.jsonArray

                    val textBuilder = StringBuilder()
                    words?.forEach { wordElem ->
                        val wordObj = wordElem.jsonObject
                        val symbols = wordObj["symbols"]?.jsonArray
                        symbols?.forEach { symElem ->
                            val symObj = symElem.jsonObject
                            val text = symObj["text"]?.jsonPrimitive?.content.orEmpty()
                            textBuilder.append(text)

                            val detectedBreak = symObj["property"]?.jsonObject?.get("detectedBreak")?.jsonObject
                            val breakType = detectedBreak?.get("type")?.jsonPrimitive?.content
                            if (breakType == "SPACE" || breakType == "SURE_SPACE") {
                                textBuilder.append(" ")
                            } else if (breakType == "EOL_SURE_SPACE" || breakType == "LINE_BREAK") {
                                textBuilder.append("\n")
                            }
                        }
                    }

                    val paragraphText = textBuilder.toString().trim()
                    if (paragraphText.isNotBlank()) {
                        val vertices = paraObj["boundingBox"]?.jsonObject?.get("vertices")?.jsonArray
                        if (vertices != null && vertices.isNotEmpty()) {
                            var minX = Float.MAX_VALUE
                            var minY = Float.MAX_VALUE
                            var maxX = Float.MIN_VALUE
                            var maxY = Float.MIN_VALUE

                            vertices.forEach { vertexElem ->
                                val vObj = vertexElem.jsonObject
                                val vx = vObj["x"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
                                val vy = vObj["y"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
                                minX = min(minX, vx)
                                minY = min(minY, vy)
                                maxX = max(maxX, vx)
                                maxY = max(maxY, vy)
                            }

                            if (minX < maxX && minY < maxY) {
                                val rect = RectF(
                                    minX * scaleX,
                                    minY * scaleY,
                                    maxX * scaleX,
                                    maxY * scaleY,
                                )
                                blocks.add(TextBlock(paragraphText, rect))
                            }
                        }
                    }
                }
            }
        }

        // Fallback: If fullTextAnnotation.pages.blocks was empty, try textAnnotations array
        if (blocks.isEmpty()) {
            val textAnnotations = firstResponse["textAnnotations"]?.jsonArray
            if (textAnnotations != null && textAnnotations.size > 1) {
                // Index 0 is the full text, indices 1..n are individual text blocks
                for (i in 1 until textAnnotations.size) {
                    val annotation = textAnnotations[i].jsonObject
                    val desc = annotation["description"]?.jsonPrimitive?.content.orEmpty().trim()
                    if (desc.isBlank()) continue

                    val vertices = annotation["boundingPoly"]?.jsonObject?.get("vertices")?.jsonArray
                    if (vertices != null && vertices.isNotEmpty()) {
                        var minX = Float.MAX_VALUE
                        var minY = Float.MAX_VALUE
                        var maxX = Float.MIN_VALUE
                        var maxY = Float.MIN_VALUE

                        vertices.forEach { vertexElem ->
                            val vObj = vertexElem.jsonObject
                            val vx = vObj["x"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
                            val vy = vObj["y"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
                            minX = min(minX, vx)
                            minY = min(minY, vy)
                            maxX = max(maxX, vx)
                            maxY = max(maxY, vy)
                        }

                        if (minX < maxX && minY < maxY) {
                            val rect = RectF(
                                minX * scaleX,
                                minY * scaleY,
                                maxX * scaleX,
                                maxY * scaleY,
                            )
                            blocks.add(TextBlock(desc, rect))
                        }
                    }
                }
            }
        }

        blocks
    }
}
