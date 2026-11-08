package tachiyomi.domain.manga.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.manga.model.MangaTagFilter

interface TagFilterRepository {
    suspend fun getTagFilter(mangaId: Long): MangaTagFilter?
    fun getTagFilterAsFlow(mangaId: Long): Flow<MangaTagFilter?>
    suspend fun setTagFilter(filter: MangaTagFilter)
    suspend fun deleteTagFilter(mangaId: Long)
}
