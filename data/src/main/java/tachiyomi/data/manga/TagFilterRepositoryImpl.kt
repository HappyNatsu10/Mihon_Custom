package tachiyomi.data.manga

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.data.Database
import tachiyomi.data.Manga_tag_filter
import tachiyomi.data.subscribeToOneOrNull
import tachiyomi.domain.manga.model.MangaTagFilter
import tachiyomi.domain.manga.model.TagMatchMode
import tachiyomi.domain.manga.repository.TagFilterRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class TagFilterRepositoryImpl(
    private val database: Database,
) : TagFilterRepository {

    override suspend fun getTagFilter(mangaId: Long): MangaTagFilter? {
        return database.manga_tag_filterQueries
            .getFilterByMangaId(mangaId)
            .awaitAsOneOrNull()
            ?.let(::mapEntity)
    }

    override fun getTagFilterAsFlow(mangaId: Long): Flow<MangaTagFilter?> {
        return database.manga_tag_filterQueries
            .getFilterByMangaId(mangaId)
            .subscribeToOneOrNull()
            .map { it?.let(::mapEntity) }
    }

    override suspend fun setTagFilter(filter: MangaTagFilter) {
        val tagsJson = Json.encodeToString(filter.tags.toList())
        database.manga_tag_filterQueries.setFilter(
            mangaId = filter.mangaId,
            scopeId = filter.scopeId,
            matchMode = filter.matchMode.name,
            tags = tagsJson,
        )
    }

    override suspend fun deleteTagFilter(mangaId: Long) {
        database.manga_tag_filterQueries.deleteFilter(mangaId)
    }

    private fun mapEntity(entity: Manga_tag_filter): MangaTagFilter {
        val tagsSet = try {
            Json.decodeFromString<List<String>>(entity.tags).toSet()
        } catch (_: Exception) {
            emptySet()
        }
        return MangaTagFilter(
            mangaId = entity.manga_id,
            scopeId = entity.scope_id,
            matchMode = TagMatchMode.fromString(entity.match_mode),
            tags = tagsSet,
        )
    }
}
