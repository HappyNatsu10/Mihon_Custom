package mihon.feature.readaloud.presentation

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.surfaceColorAtElevation
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import mihon.feature.readaloud.client.ElevenLabsClient
import mihon.feature.readaloud.model.DirectorEngine
import mihon.feature.readaloud.model.ElevenLabsModel
import mihon.feature.readaloud.model.ElevenLabsSubscription
import mihon.feature.readaloud.model.ElevenLabsVoice
import mihon.feature.readaloud.model.TextSide
import mihon.feature.readaloud.model.Turn
import mihon.feature.readaloud.model.VoiceEngine
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.util.collectAsState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        val elevenLabsModel by prefs.elevenLabsModel.collectAsState()
        val fallbackToAndroidTts by prefs.fallbackToAndroidTts.collectAsState()
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
        var subscription by remember { mutableStateOf<ElevenLabsSubscription?>(null) }
        var isFetchingVoices by remember { mutableStateOf(false) }
        var isFetchingSubscription by remember { mutableStateOf(false) }
        var isTestingVoice by remember { mutableStateOf(false) }
        var voicePickerTarget by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(elevenLabsApiKey) {
            val key = elevenLabsApiKey.trim().trim('"', '\'')
            if (key.isNotBlank()) {
                isFetchingVoices = true
                isFetchingSubscription = true
                val voices = withContext(Dispatchers.IO) {
                    pipeline.elevenLabsClient.fetchVoices()
                }
                val sub = withContext(Dispatchers.IO) {
                    pipeline.elevenLabsClient.fetchSubscription()
                }
                availableVoices = voices
                subscription = sub
                isFetchingVoices = false
                isFetchingSubscription = false
            } else {
                availableVoices = emptyList()
                subscription = null
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

                                // Model Selection
                                Text(
                                    text = "Speech Model",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    ElevenLabsModel.entries.forEach { modelItem ->
                                        FilterChip(
                                            selected = elevenLabsModel == modelItem.id,
                                            onClick = { prefs.elevenLabsModel.set(modelItem.id) },
                                            label = { Text(modelItem.title) },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }

                                val selectedModelObj = ElevenLabsModel.fromId(elevenLabsModel)
                                Text(
                                    text = selectedModelObj.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                // Subscription & Credit Status Card
                                if (elevenLabsApiKey.isNotBlank()) {
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme
                                                .surfaceColorAtElevation(1.dp),
                                        ),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    text = "Account & Credits",
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                                if (isFetchingSubscription) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(16.dp),
                                                        strokeWidth = 2.dp,
                                                    )
                                                }
                                            }

                                            if (subscription != null) {
                                                val sub = subscription!!
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Surface(
                                                        shape = RoundedCornerShape(12.dp),
                                                        color = if (sub.isFreeTier) {
                                                            MaterialTheme.colorScheme.secondaryContainer
                                                        } else {
                                                            MaterialTheme.colorScheme.primaryContainer
                                                        },
                                                    ) {
                                                        val tierName = sub.tier.replaceFirstChar { it.uppercase() }
                                                        Text(
                                                            text = "$tierName Tier",
                                                            modifier = Modifier.padding(
                                                                horizontal = 8.dp,
                                                                vertical = 2.dp,
                                                            ),
                                                            style = MaterialTheme.typography.labelMedium,
                                                            fontWeight = FontWeight.SemiBold,
                                                        )
                                                    }

                                                    Text(
                                                        text = "Status: ${sub.status}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }

                                                val limit = sub.characterLimit
                                                val used = sub.characterCount
                                                val remaining = sub.remainingCharacters
                                                val remainingStr = "($remaining remaining)"
                                                Text(
                                                    text = "Quota: $used / $limit used $remainingStr",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Medium,
                                                )

                                                if (limit > 0) {
                                                    val fraction = used.toFloat() / limit.toFloat()
                                                    val progress = fraction.coerceIn(0f, 1f)
                                                    LinearProgressIndicator(
                                                        progress = { progress },
                                                        modifier = Modifier.fillMaxWidth(),
                                                    )
                                                }

                                                if (sub.nextResetUnix != null && sub.nextResetUnix > 0) {
                                                    val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                                                    val dateStr = sdf.format(Date(sub.nextResetUnix * 1000L))
                                                    Text(
                                                        text = "Credit renewal date: $dateStr",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }

                                                if (sub.isFreeTier) {
                                                    Text(
                                                        text = "Tip: Free accounts get 10,000 credits/mo. " +
                                                            "Turbo v2.5 costs 0.5 credits/char. " +
                                                            "Eleven v3 requires a paid subscription.",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.primary,
                                                    )
                                                }

                                                if (sub.isQuotaExhausted) {
                                                    Text(
                                                        text = "Warning: Monthly quota exhausted! " +
                                                            "Built-in TTS will be used as fallback.",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.error,
                                                        fontWeight = FontWeight.Bold,
                                                    )
                                                }
                                            } else if (!isFetchingSubscription) {
                                                Text(
                                                    text = "Could not fetch subscription info. " +
                                                        "Check your API key and connection.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.error,
                                                )
                                            }

                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                OutlinedButton(
                                                    onClick = {
                                                        scope.launch {
                                                            isFetchingSubscription = true
                                                            val sub = withContext(Dispatchers.IO) {
                                                                pipeline.elevenLabsClient.fetchSubscription()
                                                            }
                                                            subscription = sub
                                                            isFetchingSubscription = false
                                                        }
                                                    },
                                                    modifier = Modifier.weight(1f),
                                                ) {
                                                    Text("Refresh")
                                                }

                                                Button(
                                                    onClick = {
                                                        scope.launch {
                                                            isTestingVoice = true
                                                            try {
                                                                val testFile =
                                                                    File(context.cacheDir, "test_voice.mp3")
                                                                val defaultNarrator =
                                                                    ElevenLabsClient.DEFAULT_NARRATOR_VOICE_ID
                                                                val defaultModel =
                                                                    ElevenLabsClient.DEFAULT_MODEL_ID
                                                                val voiceId =
                                                                    narratorVoiceId.ifBlank { defaultNarrator }
                                                                val modelId =
                                                                    elevenLabsModel.ifBlank { defaultModel }
                                                                val testPhrase =
                                                                    "Hello! ElevenLabs voice is working properly."
                                                                withContext(Dispatchers.IO) {
                                                                    pipeline.elevenLabsClient.textToSpeech(
                                                                        text = testPhrase,
                                                                        voiceId = voiceId,
                                                                        modelId = modelId,
                                                                        outputFile = testFile,
                                                                    )
                                                                }
                                                                pipeline.player.playAudioFile(
                                                                    file = testFile,
                                                                    turns = listOf(
                                                                        Turn("Narrator", "Voice test successful."),
                                                                    ),
                                                                    pageNumber = 1,
                                                                    onComplete = {},
                                                                )
                                                                context.toast("Playing ElevenLabs voice preview!")
                                                            } catch (e: Exception) {
                                                                val msg = "Test failed: ${e.message}"
                                                                context.toast(msg, Toast.LENGTH_LONG)
                                                            } finally {
                                                                isTestingVoice = false
                                                            }
                                                        }
                                                    },
                                                    enabled = !isTestingVoice,
                                                    modifier = Modifier.weight(1f),
                                                ) {
                                                    Text(if (isTestingVoice) "Testing..." else "Test Voice")
                                                }
                                            }
                                        }
                                    }
                                }

                                // Guidelines for Free Tier
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            text = "ElevenLabs Free Tier Voice Guidelines",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "Free accounts can use Premade voices (Rachel, Domi, Adam, etc.) " +
                                                "via the API. Community voices from the Voice Library cannot be " +
                                                "called via the API on Free accounts per ElevenLabs policy.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Voice Selection",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    OutlinedButton(
                                        onClick = {
                                            prefs.narratorVoiceId.set(ElevenLabsClient.DEFAULT_NARRATOR_VOICE_ID)
                                            prefs.secondaryVoiceId.set(ElevenLabsClient.DEFAULT_SECONDARY_VOICE_ID)
                                            context.toast("Reset to default free voices (Rachel & Domi)")
                                        },
                                    ) {
                                        Text("Reset Defaults")
                                    }
                                }

                                // Narrator Voice Section
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val narratorName = ElevenLabsClient.PRESET_FREE_VOICES
                                        .find { it.id == narratorVoiceId }?.name
                                        ?: availableVoices.find { it.id == narratorVoiceId }?.name
                                        ?: "Custom"
                                    Text(
                                        text = "Narrator Voice: $narratorName",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        listOf("Rachel", "Adam", "Antoni").forEach { name ->
                                            val preset = ElevenLabsClient.PRESET_FREE_VOICES.first { it.name == name }
                                            FilterChip(
                                                selected = narratorVoiceId == preset.id,
                                                onClick = { prefs.narratorVoiceId.set(preset.id) },
                                                label = { Text(name) },
                                            )
                                        }
                                        FilterChip(
                                            selected = false,
                                            onClick = { voicePickerTarget = "narrator" },
                                            label = { Text("Browse...") },
                                        )
                                    }
                                    OutlinedTextField(
                                        value = narratorVoiceId,
                                        onValueChange = { prefs.narratorVoiceId.set(it) },
                                        label = { Text("Narrator Voice ID") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                    )
                                }

                                // Secondary / Dialogue Voice Section
                                if (multipleVoices) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        val secondaryName = ElevenLabsClient.PRESET_FREE_VOICES
                                            .find { it.id == secondaryVoiceId }?.name
                                            ?: availableVoices.find { it.id == secondaryVoiceId }?.name
                                            ?: "Custom"
                                        Text(
                                            text = "Dialogue / Secondary Voice: $secondaryName",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            listOf("Domi", "Adam", "Josh").forEach { name ->
                                                val preset = ElevenLabsClient.PRESET_FREE_VOICES.first {
                                                    it.name == name
                                                }
                                                FilterChip(
                                                    selected = secondaryVoiceId == preset.id,
                                                    onClick = { prefs.secondaryVoiceId.set(preset.id) },
                                                    label = { Text(name) },
                                                )
                                            }
                                            FilterChip(
                                                selected = false,
                                                onClick = { voicePickerTarget = "secondary" },
                                                label = { Text("Browse...") },
                                            )
                                        }
                                        OutlinedTextField(
                                            value = secondaryVoiceId,
                                            onValueChange = { prefs.secondaryVoiceId.set(it) },
                                            label = { Text("Dialogue / Secondary Voice ID") },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                        )
                                    }
                                }

                                if (availableVoices.isNotEmpty()) {
                                    val count = availableVoices.size
                                    Text(
                                        text = "$count voices fetched from your ElevenLabs account",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }

                    // Fallback to Built-in TTS
                    CheckboxItem(
                        label = "Fallback to Built-in TTS if ElevenLabs errors or quota is exhausted",
                        pref = prefs.fallbackToAndroidTts,
                    )

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

        if (voicePickerTarget != null) {
            val isNarrator = voicePickerTarget == "narrator"
            val titleText = if (isNarrator) "Select Narrator Voice" else "Select Dialogue Voice"
            AlertDialog(
                onDismissRequest = { voicePickerTarget = null },
                title = { Text(titleText, style = MaterialTheme.typography.titleMedium) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "Verified Free-Tier Preset Voices",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        ElevenLabsClient.PRESET_FREE_VOICES.forEach { preset ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isNarrator) {
                                            prefs.narratorVoiceId.set(preset.id)
                                        } else {
                                            prefs.secondaryVoiceId.set(preset.id)
                                        }
                                        voicePickerTarget = null
                                    },
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = preset.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            text = preset.gender,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        text = preset.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        if (availableVoices.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Voices From Your Account (${availableVoices.size})",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            availableVoices.forEach { voice ->
                                val isFree = subscription?.isFreeTier ?: true
                                val isRestrictedOnFree = isFree && voice.isLibrary
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (isRestrictedOnFree) {
                                                context.toast(
                                                    "ElevenLabs blocks Community Library voices on Free Tier API. " +
                                                        "Please select a Premade voice.",
                                                    Toast.LENGTH_LONG,
                                                )
                                            } else {
                                                if (isNarrator) {
                                                    prefs.narratorVoiceId.set(voice.id)
                                                } else {
                                                    prefs.secondaryVoiceId.set(voice.id)
                                                }
                                                voicePickerTarget = null
                                            }
                                        },
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = voice.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            val badgeText = when {
                                                voice.isPremade -> "Premade"
                                                voice.isCloned -> "Cloned"
                                                isRestrictedOnFree -> "Paid Only"
                                                else -> voice.category.ifBlank { "Voice" }
                                            }
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = if (isRestrictedOnFree) {
                                                    MaterialTheme.colorScheme.errorContainer
                                                } else {
                                                    MaterialTheme.colorScheme.primaryContainer
                                                },
                                            ) {
                                                Text(
                                                    text = badgeText,
                                                    modifier = Modifier.padding(
                                                        horizontal = 6.dp,
                                                        vertical = 2.dp,
                                                    ),
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { voicePickerTarget = null }) {
                        Text("Cancel")
                    }
                },
            )
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
