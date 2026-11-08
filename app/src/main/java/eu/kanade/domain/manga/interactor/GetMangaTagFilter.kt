package eu.kanade.domain.manga.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.manga.model.MangaTagFilter
import tachiyomi.domain.manga.repository.TagFilterRepository

@Inject
class GetMangaTagFilter(
    private val tagFilterRepository: TagFilterRepository,
) {
    suspend fun await(mangaId: Long): MangaTagFilter? {
        return tagFilterRepository.getTagFilter(mangaId)
    }

    fun subscribe(mangaId: Long): Flow<MangaTagFilter?> {
        return tagFilterRepository.getTagFilterAsFlow(mangaId)
    }
}
