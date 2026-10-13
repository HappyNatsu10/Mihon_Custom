package tachiyomi.domain.updates.model

enum class UpdatesDateFilter(val days: Int?) {
    ALL(null),
    TODAY(1),
    LAST_3_DAYS(3),
    LAST_7_DAYS(7),
    LAST_30_DAYS(30),
    LAST_3_MONTHS(90);

    companion object {
        fun fromDays(days: Int?): UpdatesDateFilter {
            return entries.firstOrNull { it.days == days } ?: ALL
        }
    }
}
