# PRD: Mihon Custom (working name)

**Document type:** Product Requirements Document for an AI coding agent (Antigravity)
**Base project:** Mihon (https://github.com/mihonapp/mihon), Android manga reader, Kotlin + Jetpack Compose
**Base version:** Fork from the **v0.21.0** release tag (the current latest stable release, confirmed by the project owner). Before starting, the agent should still glance at the GitHub Releases page; if a newer stable tag exists, use that instead and note it in the summary. Do not fork from `main`/`master` unless the tag cannot be found.
**Package/namespace of upstream:** `app.mihon` (see Section 3 for the new app ID)

---

## 1. Summary

Build a personal fork of Mihon with three changes:

1. **Complete Updates history:** the Updates tab keeps showing *all* updates, and older updates are never pushed out when new ones arrive.
2. **Grouped updates:** if one manga has more than one update in the list, those updates are collapsed into a single expandable group row.
3. **In-reader translator:** the reader can translate the text on a manga page into the user's chosen language.

Everything else must behave exactly like upstream Mihon. This is a personal-use fork, so the code should stay **easy to rebase onto future Mihon releases**.

---

## 2. Goals and Non-Goals

### Goals
- G1: A user can scroll the Updates tab back through their entire update history with nothing silently dropped.
- G2: Multiple updates for the same manga appear as one group instead of a wall of repeated rows.
- G3: A user can translate a page while reading and see the translation on the page.
- G4: The fork installs next to official Mihon and never overwrites or collides with it.
- G5: Changes are isolated and small enough to merge upstream updates with minimal conflicts.

### Non-Goals
- No new tracker integrations, sync features, or extension-repo changes.
- No redesign of Library, Browse, History or More tabs.
- No Play Store release. Debug/sideload APK is enough.
- No translation of non-reader UI text (app UI localisation is unchanged).
- No scanlation-quality typesetting or inpainting. A readable overlay is the target (see Section 6).

---

## 3. Fork Setup (do this first)

| Item | Requirement |
|---|---|
| Application ID | Change to a unique ID, e.g. `app.mihon.custom`. Change in every build variant (release, debug, preview, benchmark if present) so nothing collides with official Mihon. |
| App name | "Mihon Custom" (or the name the user picks). Update `app_name` string and launcher label. |
| Launcher icon | Keep upstream icon but add a visible tint/badge or other mark so it is distinguishable from official Mihon. |
| In-app updater | **Disable** the built-in "check for app updates" so the fork does not offer to install upstream Mihon over itself. |
| Crash reporting / analytics | Make sure nothing reports to upstream endpoints. Remove or leave disabled if present. |
| Signing | Debug signing is fine. Document how to produce a release-signed APK with a local keystore (not committed). |
| Build | Must build with the repo's own documented toolchain (Android Studio + the JDK/Gradle versions the repo specifies). Verify `./gradlew assembleDebug` succeeds **before** making any changes, and record the result. |
| Git | Create a branch `custom/main` from the chosen tag. One commit (or small group of commits) per feature so each can be reverted independently. |

**Acceptance:** Debug APK installs alongside official Mihon, launches, shows the new name, and a fresh install works with an empty library.

---

## 4. Feature A: Show All Updates (no old updates dropped)

### 4.1 Problem
In upstream Mihon, the Updates tab does not show everything that has ever been fetched. Older updates disappear from the list as new ones arrive. The user wants the complete history preserved and visible.

### 4.2 Investigation (agent must do this before coding)
Do **not** assume the cause. Trace and document it. The likely places to inspect (paths may differ slightly between versions, so search the repo to confirm):

- Updates screen and state: `UpdatesTab`, `UpdatesScreenModel`, `UpdatesScreen` / `UpdatesUiItem` under `app/src/main/java/eu/kanade/tachiyomi/ui/updates/` and `eu/kanade/presentation/updates/`.
- Domain query: `GetUpdates` interactor and `UpdatesWithRelations` model under `domain/.../tachiyomi/domain/updates/`.
- Repository and SQLDelight: `UpdatesRepository` implementation in `data/` and the `updatesView.sq` view plus `updates.sq` queries (look for a **date cutoff** such as "after N months" and/or a **`LIMIT`**).
- Anything that deletes or hides old rows, for example after a library update or on app start.
- The filter options added to the Updates tab in v0.19.4 and the category filtering added in v0.20.3, which must keep working. Since the base is v0.21.0, also check the v0.21.0 release notes for any further Updates-tab or reader changes and account for them.

The agent must write the finding in `docs/custom/updates-investigation.md` (what limits exist, where, and what was changed).

### 4.3 Requirements
- **A1:** Remove any time-window cutoff and any row-count limit that prevents older updates from being listed.
- **A2:** Old updates must not be deleted or hidden when a library update brings in new chapters.
- **A3:** Keep upstream's existing inclusion rules: only manga that are in the library (favorites), respect excluded scanlators, and keep the existing "fetched after added" logic that stops the initial chapter list import from flooding the tab. Do not change what counts as an "update", only remove the dropping of old ones.
- **A4:** Keep the existing date headers (Today, Yesterday, dates), sort order (newest first), swipe actions, selection mode, download state, read/bookmark state, filters, category filter, and the Upcoming calendar entry point.
- **A5 (performance):** The list must stay smooth with a large history. Benchmark with a seeded database of at least 5,000 update rows. If a single unbounded flow is janky or slow to open, add incremental loading (Paging 3 with the SQLDelight paging extension, or load-more by date window). Pick the simplest approach that meets the budget below.
- **A6:** Add a toggle in Settings (Advanced or Library section, wherever upstream puts update-related options): **"Keep full updates history"**, default **ON**. When OFF, restore upstream behavior. This also makes it easy to compare against stock Mihon.

### 4.4 Data and migration
- Prefer **no database schema change**. If a change to `updatesView` or any `.sq` file is needed, add a proper SQLDelight migration and verify an upgrade from an existing upstream database works with no data loss.

### 4.5 Acceptance criteria
- [ ] With 200+ updates across many months, scrolling to the bottom shows the oldest update ever fetched.
- [ ] Triggering a library update that adds new chapters adds rows at the top and does **not** remove any existing rows.
- [ ] Turning the setting OFF reproduces upstream behavior.
- [ ] Opening the Updates tab with 5,000 rows: first content visible in under ~1 second on a mid-range device, scrolling stays smooth (no sustained dropped frames), and memory does not spike abnormally.
- [ ] Filters, category filter, selection, swipe actions and download buttons all work as before.

---

## 5. Feature B: Grouped Updates

### 5.1 Behavior
If the same manga has **more than one** update in the list, those updates are shown as **one group row** instead of separate rows. A manga with only one update stays a normal single row.

### 5.2 Grouping rules
- **B1 (scope):** Group within each **date section** (the existing date headers). Updates of the same manga that landed on the same date section are grouped together. Different dates stay in their own sections, so existing date ordering is not disturbed.
  *Default decision. See Open Questions Q1 for the alternative.*
- **B2 (threshold):** Group only when count is 2 or more.
- **B3 (group collapsed row):**
  - Manga cover and title, same styling as a normal row.
  - Subtitle summarizing the group, e.g. `3 new chapters · Ch. 45–47`. If chapter numbers are not parseable or not contiguous, show the count and the newest chapter name instead.
  - An expand/collapse chevron.
  - A visual indicator if any chapter in the group is unread, and a downloaded/queued indicator summarizing the group's download state (e.g. "2/3 downloaded").
- **B4 (group expanded):** Shows the individual chapter rows underneath, using the existing chapter row UI (read state, bookmark, download button/progress, swipe actions). Chapters inside the group are ordered newest first (or by chapter number descending if dates tie).
- **B5 (default state):** Groups start **collapsed**. Expanded/collapsed state survives scrolling and recomposition, and is lost on app restart (that is fine).
- **B6 (tap behavior):** Tapping a collapsed group row expands it. Tapping a chapter row opens the reader exactly as before. Tapping the cover opens the manga screen exactly as before.
- **B7 (selection and bulk actions):**
  - Long-pressing a group row enters selection mode and selects **all chapters in that group**.
  - Existing bottom-bar actions (mark read/unread, bookmark, download, delete) apply to every selected chapter.
  - A group is displayed as selected when all its chapters are selected, and partially selected when only some are.
  - Provide group-level quick actions: **Mark all read**, **Download all** (the same actions as upstream, applied to the group).
- **B8 (setting):** Add a toggle **"Group updates by manga"**, default **ON**. OFF shows the flat list exactly like upstream.

### 5.3 Implementation guidance
- Grouping is a **UI-layer transformation only**. Do not change the database for it.
- Put the grouping logic in a **pure, unit-testable function** (input: list of update items in a date section; output: list of single-or-group items). Place it in a new file so upstream merge conflicts are minimal.
- Extend `UpdatesUiItem` / the screen state with a sealed type (e.g. `Single` and `Group`) rather than rewriting the existing item model.
- The Updates screen currently uses a Compose `LazyColumn` with date headers; keep stable `key`s for group rows (e.g. `"group:{mangaId}:{dateSection}"`) so scroll position and expansion state are preserved.

### 5.4 Acceptance criteria
- [ ] A manga with 3 same-day updates shows one group row ("3 new chapters"); expanding it shows 3 chapter rows.
- [ ] A manga with 1 update still shows a normal row.
- [ ] Two manga each with 2+ updates produce two separate groups.
- [ ] Long-press on a group selects all of its chapters; "mark as read" updates all of them and the group's unread indicator clears.
- [ ] Downloading from a group row queues all its chapters and shows combined progress.
- [ ] Filters and category filter still apply correctly; a group only contains chapters that pass the active filters, and a group of 1 remaining chapter renders as a normal row.
- [ ] Turning "Group updates by manga" OFF returns the flat list.
- [ ] Unit tests cover: no updates, single update, exactly two, many manga mixed, chapters with unparseable numbers, filter reducing a group to one.

---

## 6. Feature C: In-Reader Page Translator

### 6.1 Concept
While reading, the user can translate the text on the current page. The translated text is drawn over the original text so the page stays readable. The feature must work in the paged readers (left-to-right, right-to-left, vertical) and the continuous/webtoon reader.

### 6.2 User-facing requirements
- **C1 (trigger):** A **Translate** button in the reader UI (in the reader's top or bottom menu, whichever fits upstream's layout) translates the **current page**. In continuous/webtoon mode it translates the page(s) currently visible.
- **C2 (toggle):** Once translated, the same button toggles **Original / Translated** instantly (cached, no reprocessing).
- **C3 (auto mode, optional setting):** "Auto-translate pages as they load", default **OFF**, because it costs time, data and battery.
- **C4 (settings):** New **Translation** section in the reader settings sheet and/or main Settings → Reader:
  - Enable translation (master switch)
  - Provider (see 6.3)
  - Source language: Auto-detect, Japanese, Korean, Chinese, English
  - Target language (default: English; the user can choose any language the provider supports)
  - Overlay style: text size (auto-fit by default), overlay background opacity
  - Clear translation cache
  - API key field(s), only for providers that need one
- **C5 (feedback):** Show a progress indicator on the page while translating, and a clear error message when it fails (no text found, no network, bad API key, model not downloaded). Failure must never crash the reader or block page turns.
- **C6 (non-destructive):** The original downloaded/cached page images are never modified. Translated output is a separate cached artifact.

### 6.3 Translation pipeline and providers
The pipeline has three stages. Put each behind an interface so providers can be swapped:

1. **Text detection + OCR** → text blocks with bounding boxes (and ideally grouped per speech bubble).
2. **Translation** → translated string per block.
3. **Rendering** → draw translated text into each block's region.

```kotlin
// Suggested shape; adapt names to repo conventions
interface PageOcr { suspend fun recognize(page: Bitmap, sourceLang: Lang): List<TextBlock> }
interface TextTranslator { suspend fun translate(blocks: List<String>, from: Lang, to: Lang): List<String> }
interface PageRenderer { fun render(page: Bitmap, blocks: List<TextBlock>, translated: List<String>, style: OverlayStyle): Bitmap }
```

**Providers to implement (priority order):**

| Priority | Provider | Notes |
|---|---|---|
| P0 (MVP) | **On-device**: Google ML Kit Text Recognition (Japanese/Chinese/Korean/Latin models) + ML Kit on-device Translation | No API key, works offline after language packs download, free. OCR quality on vertical Japanese and stylized manga fonts is limited, so set expectations in the UI. |
| P1 | **LLM vision provider** (user-supplied API key, e.g. Gemini or another vision-capable model) | Sends the page image and gets back text regions with translations. Usually much better on manga. Must be clearly marked as sending page images to a third party. Provider/model name and endpoint configurable. |
| P2 (optional) | Cloud OCR + cloud translation (e.g. Google Cloud Vision/Translate or DeepL) | Only if P1 is done and time allows. |

The agent should **start with P0 end-to-end**, then add P1. Do not block the MVP on P1/P2.

### 6.4 Rendering approach (important)
Mihon's readers display pages through a zoomable image view (a subsampling image view used by the paged viewer and the webtoon viewer). Aligning a separate overlay layer with zoom/pan is fragile.

**Required approach:** render the translation **into a new bitmap** (original page + filled rect/bubble-shaped cover over detected text + translated text, auto-fitted and wrapped to the block), save it to the translation cache, and then **swap the page's image source** to the translated image. Toggling back restores the original source. This keeps zoom, pan, double-tap and page transitions working with no extra alignment code.

Details:
- Fill color for each block: sample the surrounding background color (white for most bubbles) so the cover is not a harsh box; keep text color high-contrast.
- Text fitting: wrap into the block's width, shrink font size down to a floor, never overflow the page edges.
- For **webtoon/tall strips**, split the image into overlapping chunks before OCR (to respect OCR size limits and memory), then merge blocks and de-duplicate across chunk seams.
- Downscale intelligently for OCR when images are huge; render output at the original resolution (or a capped resolution) to avoid OOM. Handle `OutOfMemoryError` gracefully.
- Run OCR, translation and rendering off the main thread (coroutines on `Dispatchers.Default`/`IO`).

### 6.5 Caching
- Disk cache keyed by a hash of `(source page image identity, source lang, target lang, provider, provider settings, renderer version)`.
- Store the rendered translated image (and optionally the block JSON so re-rendering with different overlay styles does not require re-OCR).
- Do **not** add database tables for this. Use the app's cache directory with an LRU size cap (default 500 MB, configurable) and a "Clear translation cache" action.
- Do not include the translation cache in backups.

### 6.6 Privacy and security
- On-device provider: no page data leaves the phone. Say so in the settings description.
- Any cloud/LLM provider: show a one-time confirmation explaining that page images are sent to a third party.
- Store API keys using Mihon's preference store at minimum; prefer Android encrypted storage if easy. Never log keys. Never include keys in backups or crash output.
- Use Mihon's existing OkHttp/network helper for HTTP calls.

### 6.7 Implementation guidance
- Likely touchpoints (verify in the repo): `ReaderActivity`, `ReaderViewModel`, reader menu/overlay composables, `ReaderPreferences`, reader settings sheet, `viewer/pager/*`, `viewer/webtoon/*`, `ReaderPage`, and the shared page image view component.
- Put all new translation code in **a new package** (e.g. `eu.kanade.tachiyomi.ui.reader.translate`) plus a thin integration layer, so upstream merges stay simple.
- Add new strings to the repo's i18n module with the existing string-resource conventions (English only is fine for new strings).
- Add any new dependencies (ML Kit etc.) through the version catalog (`gradle/libs.versions.toml`) the way the repo already does, and check they work with the project's min SDK. F-Droid-style "foss" flavors, if present, may not allow Google ML Kit. If a flavor split exists, put ML Kit behind the non-foss flavor or behind an interface with a stub in foss, and document it.

### 6.8 Acceptance criteria
- [ ] Open a manga chapter with Japanese text, tap Translate: within a reasonable time (target under ~5 s per page on-device after models are downloaded) the page shows English text over the original text.
- [ ] Toggle Original/Translated switches instantly after the first translation.
- [ ] Zoom, pan, and page turn work normally on a translated page in LTR, RTL, vertical and webtoon modes.
- [ ] Translating a page a second time (same settings) returns instantly from cache; changing target language triggers a new translation.
- [ ] Airplane mode: on-device provider works once language packs are downloaded; cloud provider shows a clear "no network" error without crashing.
- [ ] A page with no detectable text shows a "No text found" message and leaves the page unchanged.
- [ ] Very long webtoon pages translate without OOM or crash.
- [ ] Master switch OFF hides the Translate button and removes all overhead.
- [ ] Clearing the cache frees the space and does not affect downloads or reading progress.

---

## 7. Cross-Cutting Requirements

- **Compatibility:** Keep upstream's min/target SDK. Verify on at least one low-end and one recent device/emulator.
- **Backups:** New settings should be stored in preferences so they back up and restore normally. Restoring an upstream backup into this fork must work, and a backup from this fork must still restore into official Mihon without crashing (new keys are simply ignored).
- **Theme and accessibility:** New UI uses the app's existing Material theme, supports light/dark/AMOLED, and has content descriptions on icon buttons.
- **Code hygiene:** Follow the repo's formatting (run its linter/spotless/ktlint task if configured), no unrelated refactors, no mass renames or reformatting of upstream files.
- **Upstream mergeability:** Prefer adding new files over editing large upstream files. Keep edits to upstream files small, and mark them with a short `// CUSTOM:` comment so they are easy to find during a rebase.
- **Tests:** Unit tests for the grouping function and the cache-key function; a repository/query test for Feature A if the repo has a DB test setup; manual test script in `docs/custom/manual-test.md`.

---

## 8. Milestones (suggested order for the agent)

| # | Milestone | Done when |
|---|---|---|
| M0 | Fork setup | Section 3 acceptance passes; baseline `assembleDebug` verified before any edits. |
| M1 | Updates investigation + Feature A | Investigation doc written; Section 4.5 passes; setting toggle works. |
| M2 | Feature B grouping | Pure grouping function + tests; UI, selection and bulk actions; Section 5.4 passes. |
| M3 | Translator MVP (P0 on-device) | Paged readers working, cache, settings, toggle; Section 6.8 passes for paged modes. |
| M4 | Translator: webtoon + polish | Chunked OCR for tall strips; rendering quality pass; error states. |
| M5 | Translator P1 (LLM vision provider) | Configurable provider with API key and privacy confirmation. |
| M6 | Hardening | Perf benchmark, OOM checks, release-signed build instructions, docs. |

Complete and verify each milestone (build + tests + manual check) before starting the next.

---

## 9. Instructions for the Coding Agent

1. Read the repo's README, contributing guide and build files first. Confirm the build works **before** changing anything.
2. Work milestone by milestone. At the end of each, summarize what changed, which files were touched, and how to test it.
3. Do not guess file paths or class names from this document. Search the actual repo and adapt. If the code differs from what this PRD assumes, follow the code and note the difference.
4. Keep diffs minimal. Do not modify unrelated code, dependencies or formatting.
5. If a requirement is ambiguous or conflicts with the actual architecture, pick the simplest option that satisfies the acceptance criteria, state the decision in the summary, and continue. Only stop to ask when the choice is destructive or expensive to undo.
6. Never commit keystores, API keys, or local config files.
7. Provide final build instructions and the path of the produced APK.

---

## 10. Open Questions (defaults chosen so work is not blocked)

| # | Question | Default used |
|---|---|---|
| Q1 | Should grouping span across dates (one group per manga for the whole tab) or stay within each date section? | **Within each date section** (keeps the existing date-header timeline intact). Offer "group across all dates" as a later option. |
| Q2 | Should "all updates" ever be trimmed (e.g. keep last 1 year)? | **Never trim** by default. Possible future setting for retention. |
| Q3 | Which translation target languages are required? | Whatever the selected provider supports; default English. |
| Q4 | Which cloud/LLM vision provider should P1 use? | Build a generic provider interface; first concrete one is Gemini or any vision-capable model with an API key the user supplies. |
| Q5 | Final app name and icon? | "Mihon Custom" with a tinted icon until the owner decides. |

---

## 11. Definition of Done

- All acceptance criteria in Sections 3, 4.5, 5.4 and 6.8 pass.
- Debug APK builds and installs alongside official Mihon.
- No regressions in Library, Browse, History, Updates filters, downloads, or the reader's existing behavior.
- `docs/custom/` contains the updates investigation, manual test script, and build instructions.
- Changes are organized in separate commits per feature on the `custom/main` branch.
