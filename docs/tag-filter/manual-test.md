# Tag Filter Manual Testing Guide

This document describes step-by-step procedures to manually verify the Tag Filter feature on the Manga page in Mihon.

---

## 1. Prerequisites & Environment

1. Install the debug APK: `mihon-debug.apk` built with `./gradlew assembleDebug`.
2. Make sure you have at least one normal source (e.g., MangaDex, Mangasee, or Local Source) and one source implementing `TagFilterSource` (e.g., `FakeTagFilterSource` or the real Pixiv extension).

---

## 2. Test Cases (Fake Source & Pixiv Extension)

### TC-01: Contract Detection & Visibility
- **Action:** Open a manga entry from a standard source (e.g. Local Source or MangaDex). Tap the filter icon on the top bar or chapter header.
- **Expected:** In the **Filter** tab of the sheet, the "Tags" row is **NOT visible**.
- **Action:** Open an entry whose source implements `TagFilterSource` (e.g. Pixiv artist or `FakeTagFilterSource`). Tap the filter icon.
- **Expected:** In the **Filter** tab, the **Tags** row is visible with a tag icon, the label "Tags", and any active selection summary.

### TC-02: Tag Picker UI & Scopes
- **Action:** Tap the "Tags" row in the filter sheet.
- **Expected:**
  1. The Tag Filter dialog opens immediately.
  2. Top area displays scope chips (e.g., `All`, `Illustration`, `Manga`, `Novel`).
  3. Dialog opens on the first scope returned (`All`).
  4. Search bar is focused/ready at top.
  5. List of tags displays canonical names, translated names (if available), and counts in rounded pill badges.
  6. Tags are sorted by count (highest first) by default.
  7. Top right has a Refresh button.

### TC-03: Scope Switching & Persistence
- **Action:** Tap the "Illustration" scope chip.
- **Expected:** Tag list updates to show only tags from the Illustration scope.
- **Action:** Select 1 or 2 tags, tap OK/Apply, and exit the manga screen. Re-open the manga screen and re-open the Tag Filter dialog.
- **Expected:** The dialog opens with the previously selected scope ("Illustration") and your selected tags intact.

### TC-04: Search & Sorting
- **Action:** Type a query into the "Search tags…" input.
- **Expected:** The list narrows in real-time matching either the original Japanese tag name or the English translated name (case-insensitive).
- **Action:** Tap the sort toggle (`Count` / `A–Z`).
- **Expected:** List dynamically reorders alphabetically by tag name. Tapping again restores count descending order.
- **Action:** Tap the 'X' button in the search field.
- **Expected:** Search query is cleared and full list of tags is restored.

### TC-05: Single Tag Filtering
- **Action:** Check one tag (e.g. "Original") and tap "OK".
- **Expected:**
  1. Filter sheet closes and returns to the chapter list.
  2. Filter icon on the top bar reflects the active state (highlighted color).
  3. Chapter list shows ONLY chapters whose URLs match works tagged with "Original".
  4. Chapter count header updates to reflect filtered count.

### TC-06: Multi-Select & Match Modes (ALL vs ANY)
- **Action:** Open Tag Filter dialog and select two tags (e.g. "Original" and "Fantasy").
- **Action:** Set Match Mode to "All of these" (default) and tap OK.
- **Expected:** Only chapters that have BOTH "Original" AND "Fantasy" are displayed (set intersection).
- **Action:** Re-open dialog, toggle Match Mode to "Any of these", and tap OK.
- **Expected:** Chapters that have EITHER "Original" OR "Fantasy" (or both) are displayed (set union).

### TC-07: Unsynced Works Notice
- **Action:** Select a tag whose works include items not yet added to the app's saved chapter list.
- **Expected:**
  1. The app does NOT crash and ignores unmatching URLs.
  2. A banner appears above the chapter list: `"N matching works are not in your list yet. Refresh to load them."`
  3. Tapping the banner triggers a refresh from source.

### TC-08: Empty Result State & Reset
- **Action:** Select a tag combination that matches zero saved chapters.
- **Expected:**
  1. The chapter list is replaced by a centered empty state:
     - Tag icon
     - Text: `"No chapters match the selected tags"`
     - Button: `"Clear tag filter"`
  2. Tapping `"Clear tag filter"` clears the active tag filter and restores the full chapter list.

### TC-09: Refresh Safety (CRITICAL)
- **Action:** With an active tag filter showing a reduced set of chapters (e.g. 5 out of 100 chapters), pull-to-refresh or tap the Refresh button in the top app bar.
- **Expected:**
  1. The entry refreshes from source.
  2. **Chapters not matching the tag filter are NEVER deleted from the database.**
  3. After clearing the tag filter, all 100 chapters remain present in the library/entry.

### TC-10: Combination with Existing Filters
- **Action:** Apply a tag filter. Then in the filter sheet, toggle "Unread" to included or "Downloaded" to included.
- **Expected:** Chapters are filtered by both the existing filters AND the tag filter (logical AND).

### TC-11: Offline & Cache Verification
- **Action:** Open an entry's tag filter dialog once to populate disk cache.
- **Action:** Turn off Wi-Fi and mobile data (Airplane Mode). Re-open the tag filter dialog.
- **Expected:** Tags and cached chapter URLs load immediately from disk cache without network error.
- **Action:** Tap the top-right Refresh button while offline.
- **Expected:** Displays an error message with a "Retry" button, while previously cached selections remain safe.

