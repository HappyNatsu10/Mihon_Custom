package eu.kanade.tachiyomi.ui.updates

import eu.kanade.presentation.updates.UpdatesUiModel
import kotlinx.datetime.LocalDate
import eu.kanade.tachiyomi.util.lang.toLocalDate

// CUSTOM: Feature B - Pure grouping logic for Updates tab
object UpdatesGrouping {

    private val chapterPrefixRegex = Regex("""(?i)(?:ch(?:apter)?|ep(?:isode)?)\.?\s*([0-9]+(?:\.[0-9]+)?)""")
    private val numberRegex = Regex("""\b([0-9]+(?:\.[0-9]+)?)\b""")

    fun parseChapterNumber(name: String): Double? {
        val match = chapterPrefixRegex.find(name) ?: numberRegex.find(name)
        return match?.groupValues?.get(1)?.toDoubleOrNull()
    }

    /**
     * Formats group subtitle:
     * - "3 new chapters · Ch. 45–47" if numbers are parseable and contiguous integers
     * - "3 new chapters · <newest chapter name>" otherwise
     */
    fun formatGroupSubtitle(items: List<UpdatesItem>): String {
        val count = items.size
        if (count == 0) return ""

        val numbers = items.map { parseChapterNumber(it.update.chapterName) }
        val allParsed = numbers.all { it != null }

        if (allParsed && numbers.size >= 2) {
            val nonNullNums = numbers.filterNotNull()
            val min = nonNullNums.minOrNull()!!
            val max = nonNullNums.maxOrNull()!!
            val isAllIntegers = nonNullNums.all { it % 1.0 == 0.0 }
            val countUnique = nonNullNums.distinct().size

            if (isAllIntegers && countUnique == count && (max - min + 1.0).toInt() == count) {
                return "$count new chapters · Ch. ${min.toInt()}–${max.toInt()}"
            }
        }

        val newestName = items.first().update.chapterName
        return "$count new chapters · $newestName"
    }

    /**
     * Pure function that transforms a flat list of update items into UI models:
     * - Partitioned by date section (preserving newest-first date order)
     * - If [groupByManga] is true and a manga has >= 2 chapters in that date section,
     *   collapses them into a single [UpdatesUiModel.Group].
     * - If the group key is in [expandedGroupKeys], the group header is followed
     *   by its individual chapter items.
     */
    fun groupUpdates(
        items: List<UpdatesItem>,
        groupByManga: Boolean,
        expandedGroupKeys: Set<String>,
    ): List<UpdatesUiModel> {
        if (items.isEmpty()) return emptyList()

        // 1. Partition into date sections while preserving date ordering
        val dateSections = LinkedHashMap<LocalDate, MutableList<UpdatesItem>>()
        for (item in items) {
            val date = item.update.dateFetch.toLocalDate()
            dateSections.getOrPut(date) { mutableListOf() }.add(item)
        }

        val result = mutableListOf<UpdatesUiModel>()

        for ((date, dateItems) in dateSections) {
            result.add(UpdatesUiModel.Header(date))

            if (!groupByManga) {
                for (item in dateItems) {
                    result.add(UpdatesUiModel.Item(item))
                }
                continue
            }

            // Group by mangaId within this date section, keeping order of newest chapter
            val mangaGroups = LinkedHashMap<Long, MutableList<UpdatesItem>>()
            for (item in dateItems) {
                mangaGroups.getOrPut(item.update.mangaId) { mutableListOf() }.add(item)
            }

            for ((mangaId, mangaItems) in mangaGroups) {
                if (mangaItems.size == 1) {
                    result.add(UpdatesUiModel.Item(mangaItems.first()))
                } else {
                    // Order inside group: newest dateFetch first, then chapterId descending
                    val sortedItems = mangaItems.sortedWith(
                        compareByDescending<UpdatesItem> { it.update.dateFetch }
                            .thenByDescending { it.update.chapterId },
                    )
                    val groupKey = "group:$mangaId:$date"
                    val isExpanded = expandedGroupKeys.contains(groupKey)
                    result.add(
                        UpdatesUiModel.Group(
                            mangaId = mangaId,
                            date = date,
                            items = sortedItems,
                            expanded = isExpanded,
                        ),
                    )
                    if (isExpanded) {
                        for (chapterItem in sortedItems) {
                            result.add(UpdatesUiModel.Item(chapterItem, isChildOfGroup = true))
                        }
                    }
                }
            }
        }

        return result
    }
}
