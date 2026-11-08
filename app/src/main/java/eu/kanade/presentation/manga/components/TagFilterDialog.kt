package eu.kanade.presentation.manga.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import eu.kanade.tachiyomi.source.tagfilter.SourceTag
import eu.kanade.tachiyomi.source.tagfilter.TagLookupException
import eu.kanade.tachiyomi.source.tagfilter.TagScope
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.Search
import tachiyomi.domain.manga.model.TagMatchMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun TagFilterDialog(
    scopes: List<TagScope>,
    selectedScopeId: String?,
    tags: List<SourceTag>,
    isLoading: Boolean,
    errorReason: TagLookupException.Reason?,
    selectedTags: Set<String>,
    matchMode: TagMatchMode,
    onScopeSelected: (TagScope) -> Unit,
    onTagToggled: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onClearAllTags: () -> Unit,
    onMatchModeChanged: (TagMatchMode) -> Unit,
    onRefreshTags: () -> Unit,
    onRetry: () -> Unit,
    onDismissRequest: () -> Unit,
    onApply: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var sortAlpha by remember { mutableStateOf(false) }

    val filteredTags = remember(tags, searchQuery, sortAlpha) {
        val query = searchQuery.trim()
        val list = if (query.isEmpty()) {
            tags
        } else {
            tags.filter {
                it.name.contains(query, ignoreCase = true) ||
                    it.translatedName?.contains(query, ignoreCase = true) == true
            }
        }
        if (sortAlpha) {
            list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        } else {
            list.sortedWith(
                compareByDescending<SourceTag> { it.count ?: 0 }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .heightIn(max = 680.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = stringResource(MR.strings.tag_filter_title))
                IconButton(onClick = onRefreshTags, enabled = !isLoading) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Refresh,
                        contentDescription = stringResource(MR.strings.action_webview_refresh),
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Scopes switcher
                if (scopes.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        scopes.forEach { scope ->
                            val selected = scope.id == selectedScopeId
                            FilterChip(
                                selected = selected,
                                onClick = { onScopeSelected(scope) },
                                label = { Text(scope.name) },
                            )
                        }
                    }
                }

                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(MR.strings.tag_filter_search_hint)) },
                    leadingIcon = {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.Search,
                            contentDescription = null,
                        )
                    },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = MaterialSymbols.Rounded.Close,
                                    contentDescription = stringResource(MR.strings.action_reset),
                                )
                            }
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                )

                // Selected chips row
                if (selectedTags.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        selectedTags.forEach { tagName ->
                            InputChip(
                                selected = true,
                                onClick = { onRemoveTag(tagName) },
                                label = {
                                    Text(
                                        text = tagName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                trailingIcon = {
                                    Icon(
                                        imageVector = MaterialSymbols.Rounded.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                    }
                }

                // Match mode and sort controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Match Mode (All vs Any)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = matchMode == TagMatchMode.ALL,
                            onClick = { onMatchModeChanged(TagMatchMode.ALL) },
                            label = { Text(stringResource(MR.strings.tag_filter_match_all)) },
                        )
                        FilterChip(
                            selected = matchMode == TagMatchMode.ANY,
                            onClick = { onMatchModeChanged(TagMatchMode.ANY) },
                            label = { Text(stringResource(MR.strings.tag_filter_match_any)) },
                        )
                    }

                    // Sort toggle
                    FilterChip(
                        selected = sortAlpha,
                        onClick = { sortAlpha = !sortAlpha },
                        label = {
                            Text(
                                if (sortAlpha) {
                                    stringResource(MR.strings.tag_filter_sort_alpha)
                                } else {
                                    stringResource(MR.strings.tag_filter_sort_count)
                                },
                            )
                        },
                    )
                }

                HorizontalDivider()

                // Content area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(min = 180.dp, max = 360.dp),
                ) {
                    when {
                        isLoading -> {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        }
                        errorReason != null -> {
                            val errorMsg = when (errorReason) {
                                TagLookupException.Reason.LOGIN_REQUIRED ->
                                    stringResource(MR.strings.tag_filter_error_login)
                                TagLookupException.Reason.RATE_LIMITED ->
                                    stringResource(MR.strings.tag_filter_error_rate_limit)
                                TagLookupException.Reason.NOT_SUPPORTED ->
                                    stringResource(MR.strings.tag_filter_error_not_supported)
                                else ->
                                    stringResource(MR.strings.tag_filter_error_generic)
                            }
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = errorMsg,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                TextButton(onClick = onRetry) {
                                    Text(stringResource(MR.strings.action_retry))
                                }
                            }
                        }
                        filteredTags.isEmpty() -> {
                            Text(
                                text = stringResource(MR.strings.tag_filter_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                        else -> {
                            val listState = rememberLazyListState()
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                items(filteredTags, key = { it.name }) { tag ->
                                    val isSelected = tag.name in selectedTags
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onTagToggled(tag.name) }
                                            .padding(vertical = 4.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = null,
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = tag.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                            if (!tag.translatedName.isNullOrBlank()) {
                                                Text(
                                                    text = tag.translatedName!!,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        if (tag.count != null) {
                                            Pill(
                                                text = tag.count.toString(),
                                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = MaterialTheme.typography.labelSmall.fontSize,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (selectedTags.isNotEmpty()) {
                    TextButton(onClick = onClearAllTags) {
                        Text(stringResource(MR.strings.tag_filter_clear))
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(MR.strings.action_cancel))
                }
                TextButton(onClick = onApply) {
                    Text(stringResource(MR.strings.action_ok))
                }
            }
        },
    )
}
