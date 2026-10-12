package eu.kanade.tachiyomi.ui.updates

import eu.kanade.presentation.updates.UpdatesUiModel
import eu.kanade.tachiyomi.data.download.model.Download
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import eu.kanade.tachiyomi.util.lang.toLocalDate
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.model.UpdatesWithRelations
import kotlin.time.Instant

class UpdatesGroupingTest {

    private val fetchInstant = Instant.fromEpochMilliseconds(1791849600000L)
    private val baseDate: LocalDate get() = fetchInstant.toLocalDate()

    private fun createUpdateItem(
        mangaId: Long,
        mangaTitle: String,
        chapterId: Long,
        chapterName: String,
        read: Boolean = false,
        selected: Boolean = false,
        downloadState: Download.State = Download.State.NOT_DOWNLOADED,
    ): UpdatesItem {
        return UpdatesItem(
            update = UpdatesWithRelations(
                mangaId = mangaId,
                mangaTitle = mangaTitle,
                chapterId = chapterId,
                chapterName = chapterName,
                scanlator = "ScanGroup",
                chapterUrl = "/chapter/$chapterId",
                read = read,
                bookmark = false,
                lastPageRead = 0,
                sourceId = 1L,
                dateFetch = fetchInstant,
                coverData = MangaCover(
                    mangaId = mangaId,
                    sourceId = 1L,
                    isMangaFavorite = true,
                    url = "https://example.com/cover.jpg",
                    lastModified = null,
                ),
            ),
            downloadStateProvider = { downloadState },
            downloadProgressProvider = { 0 },
            selected = selected,
        )
    }

    @Test
    fun `no updates produces empty list`() {
        val result = UpdatesGrouping.groupUpdates(
            items = emptyList(),
            groupByManga = true,
            expandedGroupKeys = emptySet(),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single update stays a normal single item`() {
        val item = createUpdateItem(1L, "One Piece", 101L, "Chapter 1100")
        val result = UpdatesGrouping.groupUpdates(
            items = listOf(item),
            groupByManga = true,
            expandedGroupKeys = emptySet(),
        )

        assertEquals(2, result.size)
        assertTrue(result[0] is UpdatesUiModel.Header)
        val uiItem = result[1] as UpdatesUiModel.Item
        assertEquals(101L, uiItem.item.update.chapterId)
        assertFalse(uiItem.isChildOfGroup)
    }

    @Test
    fun `exactly two updates for same manga collapses into single group row`() {
        val item1 = createUpdateItem(1L, "One Piece", 102L, "Chapter 1101")
        val item2 = createUpdateItem(1L, "One Piece", 101L, "Chapter 1100")
        val result = UpdatesGrouping.groupUpdates(
            items = listOf(item1, item2),
            groupByManga = true,
            expandedGroupKeys = emptySet(),
        )

        assertEquals(2, result.size)
        assertTrue(result[0] is UpdatesUiModel.Header)
        val group = result[1] as UpdatesUiModel.Group
        assertEquals(1L, group.mangaId)
        assertEquals(2, group.count)
        assertFalse(group.expanded)
    }

    @Test
    fun `expanded group emits group header followed by child items`() {
        val item1 = createUpdateItem(1L, "One Piece", 102L, "Chapter 1101")
        val item2 = createUpdateItem(1L, "One Piece", 101L, "Chapter 1100")
        val groupKey = "group:1:$baseDate"

        val result = UpdatesGrouping.groupUpdates(
            items = listOf(item1, item2),
            groupByManga = true,
            expandedGroupKeys = setOf(groupKey),
        )

        // Header + Group + 2 child Items = 4 models
        assertEquals(4, result.size)
        assertTrue(result[0] is UpdatesUiModel.Header)
        val group = result[1] as UpdatesUiModel.Group
        assertTrue(group.expanded)

        val child1 = result[2] as UpdatesUiModel.Item
        val child2 = result[3] as UpdatesUiModel.Item
        assertTrue(child1.isChildOfGroup)
        assertTrue(child2.isChildOfGroup)
        assertEquals(102L, child1.item.update.chapterId)
        assertEquals(101L, child2.item.update.chapterId)
    }

    @Test
    fun `many manga mixed creates separate groups and single items`() {
        val op1 = createUpdateItem(1L, "One Piece", 102L, "Chapter 1101")
        val b1 = createUpdateItem(2L, "Bleach", 201L, "Chapter 1")
        val op2 = createUpdateItem(1L, "One Piece", 101L, "Chapter 1100")
        val c1 = createUpdateItem(3L, "Chainsaw Man", 302L, "Chapter 50")
        val c2 = createUpdateItem(3L, "Chainsaw Man", 301L, "Chapter 49")

        val result = UpdatesGrouping.groupUpdates(
            items = listOf(op1, b1, op2, c1, c2),
            groupByManga = true,
            expandedGroupKeys = emptySet(),
        )

        // 1 Header + 1 Group (One Piece) + 1 Single (Bleach) + 1 Group (Chainsaw Man) = 4 models
        assertEquals(4, result.size)
        assertTrue(result[0] is UpdatesUiModel.Header)
        val groupOp = result[1] as UpdatesUiModel.Group
        assertEquals(1L, groupOp.mangaId)
        assertEquals(2, groupOp.count)

        val singleBleach = result[2] as UpdatesUiModel.Item
        assertEquals(2L, singleBleach.item.update.mangaId)

        val groupCs = result[3] as UpdatesUiModel.Group
        assertEquals(3L, groupCs.mangaId)
        assertEquals(2, groupCs.count)
    }

    @Test
    fun `filter reducing a group to one chapter renders as a normal single row`() {
        // Suppose a filter (e.g. unread only) left only 1 chapter of One Piece
        val item = createUpdateItem(1L, "One Piece", 102L, "Chapter 1101")
        val result = UpdatesGrouping.groupUpdates(
            items = listOf(item),
            groupByManga = true,
            expandedGroupKeys = emptySet(),
        )

        assertEquals(2, result.size)
        assertTrue(result[0] is UpdatesUiModel.Header)
        assertTrue(result[1] is UpdatesUiModel.Item)
    }

    @Test
    fun `grouping disabled renders flat list`() {
        val item1 = createUpdateItem(1L, "One Piece", 102L, "Chapter 1101")
        val item2 = createUpdateItem(1L, "One Piece", 101L, "Chapter 1100")
        val result = UpdatesGrouping.groupUpdates(
            items = listOf(item1, item2),
            groupByManga = false,
            expandedGroupKeys = emptySet(),
        )

        assertEquals(3, result.size)
        assertTrue(result[0] is UpdatesUiModel.Header)
        assertTrue(result[1] is UpdatesUiModel.Item)
        assertTrue(result[2] is UpdatesUiModel.Item)
    }

    @Test
    fun `formatGroupSubtitle formats contiguous chapters`() {
        val item1 = createUpdateItem(1L, "Manga", 3L, "Ch. 47")
        val item2 = createUpdateItem(1L, "Manga", 2L, "Ch. 46")
        val item3 = createUpdateItem(1L, "Manga", 1L, "Ch. 45")

        val subtitle = UpdatesGrouping.formatGroupSubtitle(listOf(item1, item2, item3))
        assertEquals("3 new chapters · Ch. 45–47", subtitle)
    }

    @Test
    fun `formatGroupSubtitle falls back to newest chapter name for non-contiguous chapters`() {
        val item1 = createUpdateItem(1L, "Manga", 2L, "Ch. 50")
        val item2 = createUpdateItem(1L, "Manga", 1L, "Ch. 45")

        val subtitle = UpdatesGrouping.formatGroupSubtitle(listOf(item1, item2))
        assertEquals("2 new chapters · Ch. 50", subtitle)
    }

    @Test
    fun `formatGroupSubtitle handles unparseable chapter names gracefully`() {
        val item1 = createUpdateItem(1L, "Manga", 2L, "Special Extra Epilogue")
        val item2 = createUpdateItem(1L, "Manga", 1L, "Prologue Part A")

        val subtitle = UpdatesGrouping.formatGroupSubtitle(listOf(item1, item2))
        assertEquals("2 new chapters · Special Extra Epilogue", subtitle)
    }
}
