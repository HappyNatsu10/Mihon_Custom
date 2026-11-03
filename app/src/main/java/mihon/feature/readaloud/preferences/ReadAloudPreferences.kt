package mihon.feature.readaloud.preferences

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@Inject
@SingleIn(AppScope::class)
class ReadAloudPreferences(
    preferenceStore: PreferenceStore,
) {
    val enableReadAloud: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_read_aloud_enabled",
        false,
    )

    val elevenLabsApiKey: Preference<String> = preferenceStore.getString(
        "pref_read_aloud_elevenlabs_api_key",
        "",
    )

    val voiceEngine: Preference<String> = preferenceStore.getString(
        "pref_read_aloud_voice_engine",
        "elevenlabs",
    )

    val elevenLabsModel: Preference<String> = preferenceStore.getString(
        "pref_read_aloud_elevenlabs_model",
        "eleven_turbo_v2_5",
    )

    val fallbackToAndroidTts: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_read_aloud_fallback_tts",
        true,
    )

    val narratorVoiceId: Preference<String> = preferenceStore.getString(
        "pref_read_aloud_narrator_voice_id",
        "21m00Tcm4TlvDq8ikWAM",
    )

    val secondaryVoiceId: Preference<String> = preferenceStore.getString(
        "pref_read_aloud_secondary_voice_id",
        "AZnzlk1XvdvUeBnXmlld",
    )

    val textLanguage: Preference<String> = preferenceStore.getString(
        "pref_read_aloud_text_language",
        "en",
    )

    val textSide: Preference<String> = preferenceStore.getString(
        "pref_read_aloud_text_side",
        "auto",
    )

    val emotions: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_read_aloud_emotions",
        true,
    )

    val multipleVoices: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_read_aloud_multiple_voices",
        true,
    )

    val autoAdvance: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_read_aloud_auto_advance",
        false,
    )

    val backgroundAudio: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_read_aloud_background_audio",
        false,
    )

    val prefetchNextPage: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_read_aloud_prefetch_next",
        true,
    )

    val audioCacheLimitMb: Preference<Int> = preferenceStore.getInt(
        "pref_read_aloud_cache_limit_mb",
        200,
    )

    val characterWarning: Preference<Int> = preferenceStore.getInt(
        "pref_read_aloud_character_warning",
        0,
    )

    val characterCount: Preference<Long> = preferenceStore.getLong(
        "pref_read_aloud_character_count",
        0L,
    )

    val directorEngine: Preference<String> = preferenceStore.getString(
        "pref_read_aloud_director_engine",
        "rule_based",
    )

    val anthropicApiKey: Preference<String> = preferenceStore.getString(
        "pref_read_aloud_anthropic_api_key",
        "",
    )
}
