package eu.kanade.domain.manga.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.manga.model.MangaTagFilter
import tachiyomi.domain.manga.repository.TagFilterRepository

@Inject
class SetMangaTagFilter(
    private val tagFilterRepository: TagFilterRepository,
) {
    suspend fun await(filter: MangaTagFilter) {
        tagFilterRepository.setTagFilter(filter)
    }

    suspend fun delete(mangaId: Long) {
        tagFilterRepository.deleteTagFilter(mangaId)
    }
}
