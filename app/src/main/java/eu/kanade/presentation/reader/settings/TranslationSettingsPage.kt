package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.translate.TranslationPipeline
import eu.kanade.tachiyomi.util.system.toast
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

private val providers = listOf(
    "mlkit" to MR.strings.pref_translation_provider_mlkit,
    "cloudvision" to MR.strings.pref_translation_provider_cloud_vision,
    "gemini" to MR.strings.pref_translation_provider_llm,
)

private val sourceLanguages = listOf(
    "auto" to "Auto",
    "ja" to "Japanese",
    "ko" to "Korean",
    "zh" to "Chinese",
    "en" to "English",
)

private val targetLanguages = listOf(
    "en" to "English",
    "es" to "Spanish",
    "fr" to "French",
    "de" to "German",
    "pt" to "Portuguese",
    "id" to "Indonesian",
)

@Composable
internal fun ColumnScope.TranslationSettingsPage(viewModel: ReaderSettingsViewModel) {
    val context = LocalContext.current
    val pipeline = remember { context.metroGraph<AppGraph>().translationPipeline }

    val enabled by viewModel.preferences.readerTranslationEnabled.collectAsState()
    val autoTranslate by viewModel.preferences.readerTranslationAuto.collectAsState()
    val provider by viewModel.preferences.readerTranslationProvider.collectAsState()
    val sourceLang by viewModel.preferences.readerTranslationSource.collectAsState()
    val targetLang by viewModel.preferences.readerTranslationTarget.collectAsState()
    val cloudVisionApiKey by viewModel.preferences.readerTranslationCloudVisionApiKey.collectAsState()
    val geminiApiKey by viewModel.preferences.readerTranslationGeminiApiKey.collectAsState()
    val geminiModel by viewModel.preferences.readerTranslationGeminiModel.collectAsState()
    val opacity by viewModel.preferences.readerTranslationOverlayOpacity.collectAsState()

    var cacheSizeBytes by remember { mutableStateOf(pipeline.getCacheSize()) }

    CheckboxItem(
        label = stringResource(MR.strings.pref_enable_translation),
        pref = viewModel.preferences.readerTranslationEnabled,
    )

    if (enabled) {
        CheckboxItem(
            label = stringResource(MR.strings.pref_auto_translate),
            pref = viewModel.preferences.readerTranslationAuto,
        )

        SettingsChipRow(MR.strings.pref_translation_provider) {
            providers.map { (id, labelRes) ->
                FilterChip(
                    selected = provider == id,
                    onClick = { viewModel.preferences.readerTranslationProvider.set(id) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }

        SettingsChipRow(MR.strings.pref_translation_source_lang) {
            sourceLanguages.map { (code, name) ->
                FilterChip(
                    selected = sourceLang == code,
                    onClick = { viewModel.preferences.readerTranslationSource.set(code) },
                    label = { Text(name) },
                )
            }
        }

        SettingsChipRow(MR.strings.pref_translation_target_lang) {
            targetLanguages.map { (code, name) ->
                FilterChip(
                    selected = targetLang == code,
                    onClick = { viewModel.preferences.readerTranslationTarget.set(code) },
                    label = { Text(name) },
                )
            }
        }

        if (provider == "cloudvision") {
            var cvApiKeyText by remember(cloudVisionApiKey) { mutableStateOf(cloudVisionApiKey) }
            OutlinedTextField(
                value = cvApiKeyText,
                onValueChange = {
                    cvApiKeyText = it
                    viewModel.preferences.readerTranslationCloudVisionApiKey.set(it)
                },
                label = { Text(stringResource(MR.strings.pref_translation_cloud_vision_api_key)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.padding.medium, vertical = 4.dp),
            )
        }

        if (provider == "gemini") {
            Text(
                text = stringResource(MR.strings.translation_privacy_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = 4.dp),
            )

            var apiKeyText by remember(geminiApiKey) { mutableStateOf(geminiApiKey) }
            OutlinedTextField(
                value = apiKeyText,
                onValueChange = {
                    apiKeyText = it
                    viewModel.preferences.readerTranslationGeminiApiKey.set(it)
                },
                label = { Text(stringResource(MR.strings.pref_translation_api_key)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.padding.medium, vertical = 4.dp),
            )

            var modelText by remember(geminiModel) { mutableStateOf(geminiModel) }
            OutlinedTextField(
                value = modelText,
                onValueChange = {
                    modelText = it
                    viewModel.preferences.readerTranslationGeminiModel.set(it)
                },
                label = { Text(stringResource(MR.strings.pref_translation_llm_model)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.padding.medium, vertical = 4.dp),
            )
        }

        SliderItem(
            value = (opacity * 100).toInt(),
            valueRange = 50..100,
            label = stringResource(MR.strings.pref_translation_overlay_opacity),
            valueString = "${(opacity * 100).toInt()}%",
            onChange = { viewModel.preferences.readerTranslationOverlayOpacity.set(it / 100f) },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.padding.medium, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val formattedSize = remember(cacheSizeBytes) {
                val mb = cacheSizeBytes / (1024.0 * 1024.0)
                "%.1f MB".format(mb)
            }
            Button(
                onClick = {
                    pipeline.clearCache()
                    cacheSizeBytes = 0L
                    context.toast(context.stringResource(MR.strings.translation_cache_cleared))
                },
            ) {
                Text(stringResource(MR.strings.pref_clear_translation_cache) + " ($formattedSize)")
            }
        }
    }
}
