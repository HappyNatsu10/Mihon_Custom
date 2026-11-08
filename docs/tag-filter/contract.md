# Source API Tag Filter Contract v1

**Package:** `eu.kanade.tachiyomi.source.tagfilter`  
**Version:** `v1` (Frozen)  
**Specification:** Contract v1 from `Tag_Filter_App_PRD.md`

---

## 1. Overview

The `TagFilterSource` interface enables sources/extensions to expose artist-level or manga-level tags and paged work lookups to the Mihon manga page filter sheet.

Sources that do not implement this interface are completely unaffected. The app detects support using a simple type check:
```kotlin
if (source is TagFilterSource) { ... }
```

---

## 2. Interface Definition

```kotlin
package eu.kanade.tachiyomi.source.tagfilter

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga

interface TagFilterSource : Source {

    /**
     * Content types the tag list can be scoped to (e.g. All / Illustration / Manga / Novel).
     * Return only scopes that apply to this entry. Empty list = feature unavailable.
     */
    suspend fun getTagScopes(manga: SManga): List<TagScope>

    /**
     * All tags for the entry within a scope, with counts.
     */
    suspend fun getTags(manga: SManga, scope: TagScope): List<SourceTag>

    /**
     * One page (page starts at 1) of works matching a tag within a scope.
     * Returns chapter URLs EXACTLY as the source produces them in SChapter.url,
     * so the app can match them by string equality.
     */
    suspend fun getTaggedChapterUrls(manga: SManga, scope: TagScope, tag: SourceTag, page: Int): TaggedPage
}

/** Stable scope id, e.g. "all", "illust", "manga", "novel". */
data class TagScope(val id: String, val name: String)

/** `name` is the canonical tag text and is what gets passed back to the source. */
data class SourceTag(val name: String, val count: Int?, val translatedName: String? = null)

data class TaggedPage(val chapterUrls: List<String>, val hasNextPage: Boolean, val total: Int? = null)

/** Thrown by sources for expected failures so the app can show a clear message. */
class TagLookupException(val reason: Reason, message: String? = null, cause: Throwable? = null) :
    Exception(message, cause) {
    enum class Reason { LOGIN_REQUIRED, RATE_LIMITED, NOT_SUPPORTED, OTHER }
}
```

---

## 3. Rules & Semantics

1. **Paging:** Page numbers start at **1**. The app pages through `getTaggedChapterUrls` until `hasNextPage` is `false`.
2. **Matching:** `chapterUrls` returned in `TaggedPage` must match `SChapter.url` exactly as string equality.
3. **Threading:** All calls are suspended and executed on background dispatchers (`Dispatchers.IO`).
4. **Classloading & Extension Bundling:**
   - The app owns this contract in `:source-api`.
   - Extensions may bundle an identical copy of `TagFilterSource.kt` in the same package `eu.kanade.tachiyomi.source.tagfilter`.
   - When running inside Mihon with this contract, `DelegateLastClassLoaderCompat` resolves `eu.kanade.tachiyomi.source.*` from the parent (app) classloader first, ensuring shared class identity.
   - When running on stock Mihon without this contract, the extension uses its own bundled copy and gracefully degrades.

