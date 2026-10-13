package tachiyomi.domain.updates.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getEnum
import tachiyomi.core.common.preference.getLongArray

@Inject
@SingleIn(AppScope::class)
class UpdatesPreferences(
    preferenceStore: PreferenceStore,
) {

    val filterDownloaded: Preference<TriState> = preferenceStore.getEnum(
        "pref_filter_updates_downloaded",
        TriState.DISABLED,
    )

    val filterUnread: Preference<TriState> = preferenceStore.getEnum(
        "pref_filter_updates_unread",
        TriState.DISABLED,
    )

    val filterStarted: Preference<TriState> = preferenceStore.getEnum(
        "pref_filter_updates_started",
        TriState.DISABLED,
    )

    val filterBookmarked: Preference<TriState> = preferenceStore.getEnum(
        "pref_filter_updates_bookmarked",
        TriState.DISABLED,
    )

    val filterExcludedScanlators: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_filter_updates_hide_excluded_scanlators",
        false,
    )

    val filterIncludedCategories: Preference<List<Long>> = preferenceStore.getLongArray(
        "pref_filter_updates_included_categories",
        emptyList(),
    )

    val filterExcludedCategories: Preference<List<Long>> = preferenceStore.getLongArray(
        "pref_filter_updates_excluded_categories",
        emptyList(),
    )

    // CUSTOM: Feature A - Keep full updates history (no 3-month cutoff, no 500-limit)
    val keepFullUpdatesHistory: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_keep_full_updates_history",
        true,
    )

    // CUSTOM: Feature B - Group updates by manga
    val groupUpdatesByManga: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_group_updates_by_manga",
        true,
    )

    // CUSTOM: Filter updates by date range
    val filterDateRange: Preference<tachiyomi.domain.updates.model.UpdatesDateFilter> = preferenceStore.getEnum(
        "pref_filter_updates_date_range",
        tachiyomi.domain.updates.model.UpdatesDateFilter.ALL,
    )
}
