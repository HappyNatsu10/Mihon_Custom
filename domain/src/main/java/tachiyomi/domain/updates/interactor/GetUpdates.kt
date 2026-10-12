package tachiyomi.domain.updates.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.repository.UpdatesRepository
import kotlin.time.Instant

@Inject
class GetUpdates(
    private val repository: UpdatesRepository,
) {

    // CUSTOM: after is nullable and limit is configurable to support full updates history
    suspend fun await(read: Boolean, after: Instant?, limit: Long = 500): List<UpdatesWithRelations> {
        return repository.awaitWithRead(read, after, limit = limit)
    }

    fun subscribe(
        instant: Instant?,
        limit: Long = 500,
        unread: Boolean?,
        started: Boolean?,
        bookmarked: Boolean?,
        hideExcludedScanlators: Boolean,
        includedCategories: List<Long>,
        excludedCategories: List<Long>,
    ): Flow<List<UpdatesWithRelations>> {
        return repository.subscribeAll(
            instant,
            limit = limit,
            unread = unread,
            started = started,
            bookmarked = bookmarked,
            hideExcludedScanlators = hideExcludedScanlators,
            includedCategories = includedCategories,
            excludedCategories = excludedCategories,
        )
    }

    fun subscribe(read: Boolean, after: Instant?, limit: Long = 500): Flow<List<UpdatesWithRelations>> {
        return repository.subscribeWithRead(read, after, limit = limit)
    }
}
