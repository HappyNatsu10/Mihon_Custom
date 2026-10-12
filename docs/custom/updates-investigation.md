# Investigation: Upstream Mihon Updates Tab Limits & Cutoffs

## 1. Executive Summary
In upstream Mihon (v0.21.1), older updates disappear from the Updates tab over time due to two intentional constraints in the codebase:
1. **A hardcoded 3-month time cutoff**: The `UpdatesViewModel` passes `Clock.System.now().minus(3, DateTimeUnit.MONTH, TimeZone.currentSystemDefault())` into the domain interactor query.
2. **A hardcoded row limit of 500 items**: The domain interactor `GetUpdates` explicitly hardcodes `limit = 500` on all queries (`subscribeAll`, `await`, `subscribeWithRead`).
3. **Database query filter (`dateUpload > :after`)**: In `updatesView.sq`, the queries `getRecentUpdatesWithFilters`, `getReadUpdates`, and `getUnreadUpdates` enforce `WHERE dateUpload > :after` and `LIMIT :limit`. Consequently, any chapter older than 3 months or beyond the 500-item cutoff is filtered out. Additionally, chapters without an upload date (or uploaded in the past before the 3-month window) were omitted even if recently fetched.

No background job or database trigger deletes old chapters from the SQLite `chapter` table; the disappearance of older updates was entirely due to these query filters and hardcoded bounds.

---

## 2. Key Codebase Touchpoints

### 2.1 View Model (`eu.kanade.tachiyomi.ui.updates.UpdatesViewModel`)
In `UpdatesViewModel.kt` (lines 167–177):
```kotlin
getUpdates.subscribe(
    Clock.System.now().minus(3, DateTimeUnit.MONTH, TimeZone.currentSystemDefault()),
    unread = it.filterUnread.toBooleanOrNull(),
    started = it.filterStarted.toBooleanOrNull(),
    bookmarked = it.filterBookmarked.toBooleanOrNull(),
    hideExcludedScanlators = it.filterExcludedScanlators,
    includedCategories = it.filterIncludedCategories,
    excludedCategories = it.filterExcludedCategories,
)
```
- Upstream hardcoded the cutoff to 3 months before current system time.

### 2.2 Domain Interactor (`tachiyomi.domain.updates.interactor.GetUpdates`)
In `GetUpdates.kt`:
```kotlin
suspend fun await(read: Boolean, after: Instant): List<UpdatesWithRelations> {
    return repository.awaitWithRead(read, after, limit = 500)
}

fun subscribe(
    instant: Instant,
    ...
): Flow<List<UpdatesWithRelations>> {
    return repository.subscribeAll(
        instant,
        limit = 500,
        ...
    )
}
```
- Hardcoded `limit = 500` truncated the result set regardless of available history.

### 2.3 SQLDelight View and Query (`data/src/main/sqldelight/tachiyomi/view/updatesView.sq`)
In `updatesView.sq`:
```sql
CREATE VIEW updatesView AS
SELECT ...
FROM manga JOIN chapter ON manga.id = chapter.manga_id
LEFT JOIN excluded_scanlator ON manga.id = excluded_scanlator.manga_id
    AND chapter.remote_scanlator = excluded_scanlator.scanlator
WHERE user_favorite_at IS NOT NULL
AND state_date_fetch > user_favorite_at
ORDER BY state_date_fetch DESC;

getRecentUpdatesWithFilters:
SELECT ...
FROM updatesView
WHERE dateUpload > :after
...
ORDER BY dateFetch DESC
LIMIT :limit;
```
- The view itself correctly enforces inclusion rules:
  1. Favorite status: `user_favorite_at IS NOT NULL`
  2. "Fetched after added" rule: `state_date_fetch > user_favorite_at` (prevents initial library import from flooding Updates)
  3. Excluded scanlators: `excluded_scanlator.scanlator` join
- However, the query `WHERE dateUpload > :after` with `LIMIT :limit` prevented access to the full update stream.

---

## 3. Changes Made for Feature A

1. **Preference Configuration (`UpdatesPreferences`)**:
   - Added preference `keepFullUpdatesHistory` (boolean, default: `true`).
   - When enabled (`true`): No date cutoff (`after = null` or epoch 0) and no row limit (`limit = -1L`).
   - When disabled (`false`): Restores upstream 3-month cutoff and 500-item limit.

2. **SQLDelight Query (`updatesView.sq`)**:
   - Updated `WHERE (:after IS NULL OR dateUpload > :after)` across `getRecentUpdatesWithFilters`, `getReadUpdates`, and `getUnreadUpdates`.
   - Passing `:limit = -1` in SQLite retrieves all matching records without a cap.

3. **Repository & Interactor Layer**:
   - Updated `UpdatesRepository` and `GetUpdates` to support nullable `after: Instant?` and configurable `limit: Long` (defaulting to 500 for legacy/widget callers).

4. **ViewModel Integration**:
   - In `UpdatesViewModel`, combined `updatesPreferences.keepFullUpdatesHistory().changes()` into the preference flow.
   - When `keepFullUpdatesHistory` is active:
     - `after = null`
     - `limit = -1L`
   - Dynamically reloads when the preference is toggled.

5. **Settings UI**:
   - Added a setting item in **Settings → Library → Updates tab** ("Keep full updates history") with subtitle explaining that older updates are preserved.
