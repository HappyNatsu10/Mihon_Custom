package tachiyomi.domain.manga.model

enum class TagMatchMode {
    ALL,
    ANY,
    ;

    companion object {
        fun fromString(value: String?): TagMatchMode =
            entries.find { it.name.equals(value, ignoreCase = true) } ?: ALL
    }
}

data class MangaTagFilter(
    val mangaId: Long,
    val scopeId: String?,
    val matchMode: TagMatchMode,
    val tags: Set<String>,
)
