package mihon.feature.readaloud.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import mihon.feature.readaloud.model.DirectorEngine
import mihon.feature.readaloud.model.ElevenLabsVoice
import mihon.feature.readaloud.model.TextSide
import mihon.feature.readaloud.model.VoiceEngine
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.util.collectAsState

class ReadAloudSettingsScreen : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val pipeline = remember { context.metroGraph<AppGraph>().readAloudPipeline }
        val prefs = pipeline.preferences
        val scope = rememberCoroutineScope()

        val enabled by prefs.enableReadAloud.collectAsState()
        val voiceEngine by prefs.voiceEngine.collectAsState()
        val elevenLabsApiKey by prefs.elevenLabsApiKey.collectAsState()
        val narratorVoiceId by prefs.narratorVoiceId.collectAsState()
        val secondaryVoiceId by prefs.secondaryVoiceId.collectAsState()
        val textSide by prefs.textSide.collectAsState()
        val emotions by prefs.emotions.collectAsState()
        val multipleVoices by prefs.multipleVoices.collectAsState()
        val autoAdvance by prefs.autoAdvance.collectAsState()
        val prefetch by prefs.prefetchNextPage.collectAsState()
        val directorEngine by prefs.directorEngine.collectAsState()
        val anthropicApiKey by prefs.anthropicApiKey.collectAsState()
        val charCount by prefs.characterCount.collectAsState()

        var cacheSizeBytes by remember { mutableLongStateOf(pipeline.cache.getCacheSizeBytes()) }
        var availableVoices by remember { mutableStateOf<List<ElevenLabsVoice>>(emptyList()) }
        var isFetchingVoices by remember { mutableStateOf(false) }

        LaunchedEffect(elevenLabsApiKey) {
            if (elevenLabsApiKey.isNotBlank()) {
                isFetchingVoices = true
                availableVoices = pipeline.elevenLabsClient.fetchVoices()
                isFetchingVoices = false
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Read Aloud Settings") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(MaterialSymbols.AutoMirroredRounded.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(MaterialTheme.padding.medium),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CheckboxItem(
                    label = "Enable Read Aloud (OCR + Voice)",
                    pref = prefs.enableReadAloud,
                )

                if (enabled) {
                    // Voice Engine
                    ChipRow("Voice Engine") {
                        VoiceEngine.entries.map { engine ->
                            FilterChip(
                                selected = voiceEngine == engine.name.lowercase(),
                                onClick = { prefs.voiceEngine.set(engine.name.lowercase()) },
                                label = { Text(engine.title) },
                            )
                        }
                    }

                    if (voiceEngine == "elevenlabs") {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            ),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = "ElevenLabs Configuration",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )

                                OutlinedTextField(
                                    value = elevenLabsApiKey,
                                    onValueChange = { prefs.elevenLabsApiKey.set(it) },
                                    label = { Text("ElevenLabs API Key") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )

                                OutlinedTextField(
                                    value = narratorVoiceId,
                                    onValueChange = { prefs.narratorVoiceId.set(it) },
                                    label = { Text("Narrator Voice ID") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )

                                if (multipleVoices) {
                                    OutlinedTextField(
                                        value = secondaryVoiceId,
                                        onValueChange = { prefs.secondaryVoiceId.set(it) },
                                        label = { Text("Dialogue / Secondary Voice ID") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                    )
                                }

                                if (availableVoices.isNotEmpty()) {
                                    Text(
                                        text = "${availableVoices.size} voices available on your ElevenLabs account",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }

                    // Text Region / Side
                    ChipRow("Text Region (Artwork vs Text)") {
                        TextSide.entries.map { side ->
                            FilterChip(
                                selected = textSide == side.name.lowercase(),
                                onClick = { prefs.textSide.set(side.name.lowercase()) },
                                label = { Text(side.title) },
                            )
                        }
                    }

                    // Director Engine
                    ChipRow("Voice Director (Dialogue & Emotions)") {
                        DirectorEngine.entries.map { dir ->
                            FilterChip(
                                selected = directorEngine == dir.name.lowercase(),
                                onClick = { prefs.directorEngine.set(dir.name.lowercase()) },
                                label = { Text(dir.title) },
                            )
                        }
                    }

                    if (directorEngine == "anthropic") {
                        OutlinedTextField(
                            value = anthropicApiKey,
                            onValueChange = { prefs.anthropicApiKey.set(it) },
                            label = { Text("Anthropic API Key (Claude)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }

                    CheckboxItem(
                        label = "Expressive Emotions ([excited], [hesitates], [whispering], etc.)",
                        pref = prefs.emotions,
                    )

                    CheckboxItem(
                        label = "Multiple Voices for Dialogue (Speaker 1 & 2)",
                        pref = prefs.multipleVoices,
                    )

                    CheckboxItem(
                        label = "Auto-advance to next page when audio finishes",
                        pref = prefs.autoAdvance,
                    )

                    CheckboxItem(
                        label = "Prefetch next page audio in background",
                        pref = prefs.prefetchNextPage,
                    )

                    // Stats & Cache
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Usage & Cache",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )

                            Text(
                                text = "Total Characters Sent: $charCount",
                                style = MaterialTheme.typography.bodyMedium,
                            )

                            val cacheMb = cacheSizeBytes / (1024 * 1024)
                            Text(
                                text = "Audio Cache Size: $cacheMb MB",
                                style = MaterialTheme.typography.bodyMedium,
                            )

                            OutlinedButton(
                                onClick = {
                                    val freed = pipeline.cache.clearCache()
                                    cacheSizeBytes = pipeline.cache.getCacheSizeBytes()
                                    context.toast("Cleared ${freed / (1024 * 1024)} MB audio cache")
                                },
                            ) {
                                Text("Clear Audio Cache")
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ChipRow(title: String, content: @Composable () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                content()
            }
        }
    }
}
