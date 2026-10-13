# Mihon Custom - Manual QA & Verification Guide

This guide provides test procedures for validating the features added in **Mihon Custom** (`app.mihon.custom`):
1. **Fork Setup & Coexistence** (Application ID, icons, independent storage)
2. **Feature A: Complete Updates History** (No 3-month cutoff, no 500-item limit)
3. **Feature B: Grouped Updates by Manga** (Expandable rows, bulk actions, settings toggle)
4. **Feature C: In-Reader Page Translator** (ML Kit on-device, Gemini vision LLM, caching, webtoon chunking)

---

## 1. Fork Setup & Coexistence

### Test 1.1: Parallel Installation alongside Official Mihon
- **Preconditions:** Official Mihon (`app.mihon`) is installed on the target Android device or emulator with existing manga and settings.
- **Steps:**
  1. Build and install `app-universal-debug.apk`:
     ```bash
     adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
     ```
  2. Inspect the app drawer / launcher.
- **Expected Results:**
  - Both "Mihon" and "Mihon Custom" appear as separate apps.
  - Mihon Custom has a distinct launcher icon badge ("C") and theme tint.
  - Neither app interferes with each other's database, cache, or downloaded chapters.
  - Official Mihon is NOT overwritten.

### Test 1.2: Disabled Updater & Analytics
- **Steps:**
  1. Open Mihon Custom -> More -> About.
  2. Verify that automatic background update checks to upstream GitHub are disabled or cleanly bypassed.
  3. Verify that no crash or usage telemetry is transmitted to upstream endpoints.

---

## 2. Feature A: Complete Updates History

### Test 2.1: Full Updates History Display
- **Preconditions:** Library contains manga with updates spanning more than 3 months ago (or import a backup with extensive history).
- **Steps:**
  1. Open the **Updates** tab.
  2. Scroll down through the updates list.
- **Expected Results:**
  - Updates older than 3 calendar months remain visible in their respective date sections.
  - History is not capped at 500 rows; users can scroll through all historical update events.
  - Date headers (Today, Yesterday, date formats) remain correctly sorted in descending chronological order.

### Test 2.2: Retention After Library Fetch
- **Steps:**
  1. Note the oldest updates visible at the bottom of the Updates tab.
  2. Perform a manual global update swipe-to-refresh or trigger library update.
  3. Allow newly fetched chapters to appear.
- **Expected Results:**
  - New chapters appear at the top under "Today".
  - Old updates at the bottom are NOT pruned or removed.

### Test 2.3: "Keep full updates history" Toggle
- **Steps:**
  1. Navigate to **More -> Settings -> Library**.
  2. Locate the switch **"Keep full updates history"** (default: ON).
  3. Turn the switch **OFF**.
  4. Return to the Updates tab.
- **Expected Results:**
  - Updates tab restores upstream behavior (showing up to 500 items and respecting the 3-month date cutoff).
  5. Turn the switch **ON** again -> full history returns immediately.

---

## 3. Feature B: Grouped Updates by Manga

### Test 3.1: Automatic Grouping within Date Sections
- **Steps:**
  1. Update a manga that has 2 or more new chapters released on the same day.
  2. Update a manga that has only 1 chapter released on that day.
  3. Observe the Updates tab.
- **Expected Results:**
  - The manga with 1 chapter renders as a standard chapter row.
  - The manga with 2+ chapters renders as a collapsed single group row displaying:
    - Manga cover and title.
    - Summary subtitle (e.g., `3 new chapters · Ch. 45–47` or `2 new chapters · Ch. 12`).
    - Unread chapter dot indicator and download status.
    - Expand/collapse chevron icon.

### Test 3.2: Group Expand & Collapse
- **Steps:**
  1. Tap anywhere on a collapsed group row (outside of the manga cover).
- **Expected Results:**
  - The group expands smoothly, revealing the nested individual chapter rows.
  - Each nested chapter displays its chapter title, bookmark status, read status, and download button.
  2. Tap the group row again -> it collapses cleanly back to the summary row.
  3. Tap the manga cover -> opens the Manga Details screen.

### Test 3.3: Selection and Bulk Actions on Groups
- **Steps:**
  1. Long-press on a collapsed group row.
- **Expected Results:**
  - Selection mode activates.
  - All chapters belonging to that group are marked as selected (top app bar shows count corresponding to all chapters in the group).
  2. In the top/bottom selection action bar, tap **"Mark as read"**.
- **Expected Results:**
  - All chapters in the group are marked as read.
  - The group unread indicator disappears.
  3. Select another group with un-downloaded chapters and tap **Download**.
- **Expected Results:**
  - All chapters in that group are queued for download.

### Test 3.4: "Group updates by manga" Setting
- **Steps:**
  1. Go to **More -> Settings -> Library**.
  2. Toggle **"Group updates by manga"** OFF.
  3. Return to Updates tab.
- **Expected Results:**
  - Updates list displays the flat, un-grouped upstream list.
  4. Toggle it back ON -> grouped view resumes immediately.

---

## 4. Feature C: In-Reader Page Translator

### Test 4.1: UI Controls & Master Switch
- **Steps:**
  1. Open any chapter in the Reader.
  2. Tap the screen center to show the reader app bars.
- **Expected Results:**
  - A **Translate** icon button appears in the bottom navigation bar (between Bookmarks/Crop and Settings).
  3. Open Reader Settings (gear icon) -> navigate to the **Translation** tab.
  4. Toggle the master switch **"Enable translation"** OFF.
- **Expected Results:**
  - The Translate button disappears from the bottom bar.
  5. Turn "Enable translation" back ON -> the button reappears.

### Test 4.2: On-Device Translation (P0 - Google ML Kit)
- **Preconditions:** Reader Translation Provider set to **"On-device (ML Kit)"**. Target language set to **English**. Source language set to **Japanese** (or Auto-detect). Internet connection available on first run to download language model.
- **Steps:**
  1. Open a manga page with Japanese text dialog bubbles.
  2. Tap the **Translate** button in the bottom bar.
- **Expected Results:**
  - Bottom bar icon displays a circular progress spinner while processing.
  - A subtle semi-transparent bubble overlay covers the Japanese text with rendered English translation text fitted to the bounding boxes.
  - The Translate button tints with the primary theme color indicating active translation view.

### Test 4.3: Instant Toggle (Original vs. Translated)
- **Steps:**
  1. With the page currently translated, tap the **Translate** button again.
- **Expected Results:**
  - The page switches immediately back to the original unmodified image without network or OCR lag.
  2. Tap the button once more.
- **Expected Results:**
  - The page switches back to the translated overlay instantly from memory/cache.

### Test 4.4: Webtoon / Continuous Reader Chunking
- **Steps:**
  1. Switch reader viewer to **Webtoon** mode.
  2. Open a tall strip chapter.
  3. Tap **Translate**.
- **Expected Results:**
  - Image is chunked across overlapping vertical segments without causing `OutOfMemoryError`.
  - Translations appear across speech bubbles from top to bottom of the continuous page.

### Test 4.5: LLM Vision Provider (P1 - Gemini)
- **Steps:**
  1. Go to Reader Settings -> **Translation**.
  2. Select Provider: **"Gemini Vision (Cloud LLM)"**.
  3. Enter a valid Google Gemini API key.
  4. Return to reader and tap **Translate** on a page.
- **Expected Results:**
  - A one-time privacy confirmation dialog appears stating that page images will be transmitted to the Gemini API.
  - Upon selecting **"I Understand & Proceed"**, the image is translated with high-context manga dialogue translation.
  - The prompt response parses speech bubbles and renders the overlay onto the page.

### Test 4.6: Translation Cache & LRU Cleanup
- **Steps:**
  1. Translate pages 1, 2, and 3.
  2. Turn back to page 1 and tap Translate -> loads instantaneously from the disk cache.
  3. Go to Reader Settings -> **Translation** -> tap **"Clear translation cache"**.
- **Expected Results:**
  - Toast confirmation appears: "Translation cache cleared".
  - The translated files in cache are deleted.
  - Reading progress, downloaded chapters, and library data remain completely unaffected.

### Test 4.7: Offline & Error Resilience
- **Steps:**
  1. Turn on Airplane mode.
  2. Tap Translate on an uncached page using Gemini provider.
- **Expected Results:**
  - An error toast message is shown (e.g. "Network error while translating page").
  - The reader does not crash, freeze, or block page turning.
  - The original image remains displayed.
