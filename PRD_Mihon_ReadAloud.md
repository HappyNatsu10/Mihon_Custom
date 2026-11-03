  # PRD: Mihon — "Read aloud" for text inside images (OCR + ElevenLabs)

**Doc version:** 1.0 | **Target builder:** Google Antigravity | **Language:** Kotlin | **Project:** a personal fork of `mihonapp/mihon`

This document specifies **one feature only**: a "Listen" button in Mihon's reader that reads the **text written inside the current page image** aloud, using ElevenLabs for expressive voices and emotions. It does not include any other Mihon changes. Anything marked **[VERIFY]** is an assumption the agent must confirm (in the Mihon source, on a device, or against the ElevenLabs documentation) and record in `docs/READALOUD_FINDINGS.md`. If reality differs from this document, reality wins.

---

## 1. Goal and typical input

The user reads DeviantArt artworks that look like this: **a character on one side and one or more paragraphs of text on the other** (usually the right). They want to tap **Listen** and hear the paragraphs read in a natural, emotional voice. When the text contains a conversation, different speakers should get **different voices**.

### 1.1 Non-goals
- Manga speech bubbles with many speakers per page (detecting bubbles and assigning characters from the artwork) is out of scope for v1.
- Translating text, summarising, or changing what is shown on screen.
- Background playback while the screen is off or another app is in front (P2, see 9).
- Changing any existing reader behaviour when the feature is off.
- Shipping any API key. The user supplies their own.

---

## 2. Guardrails

1. **Off by default and isolated.** All new code lives in a new package `mihon.feature.readaloud` (Mihon already uses `mihon.feature.*` for features such as `upcoming` and `migration`). Existing files get only small hooks: the reader's app bar, preferences, and DI wiring.
2. **Existing behaviour unchanged** when the feature is disabled: same reader UI, same performance, no new permissions requested at install beyond what the feature needs, no new network traffic.
3. **Follow the project's conventions:** dependency injection uses the existing Metro annotations (`@Inject`, `@ContributesIntoMap` etc., as in the existing view models); preferences use `PreferenceStore`; strings go through the existing i18n resources; Compose UI follows existing components.
4. **Install side by side with official Mihon.** Build and test only the `debug` variant (application id suffix `.dev`) or `foss` (suffix `.foss`), never over the user's real Mihon install or library **[VERIFY suffixes in `app/build.gradle.kts`]**.
5. Never touch the user's real Mihon data. Test with a throwaway entry.

---

## 3. User-facing behaviour

### 3.1 Reader
- A **Listen** button (headphones/speaker icon) in the reader's bottom bar area (`ReaderAppBars` / `ReaderBottomBar` in `eu.kanade.presentation.reader.appbars`). Visible only when the feature is enabled in settings.
- Tap → reads the **current page** (`ReaderViewModel.state.currentPage` is 1-based over the chapter's pages). If the text is longer than one request allows, it plays in order.
- A small **mini player** appears while reading: play/pause, stop, "skip to next page's text". Tapping stop ends the session.
- Setting **Auto-advance**: when the audio for the current page finishes, move to the next page and keep reading (default off). Works in paged and webtoon modes (in webtoon mode "current page" is the page nearest the screen centre as the existing viewer reports it).
- Pages with no recognisable text: show a short message ("No text found on this page") and, if auto-advance is on, skip to the next page.
- A **"Text preview"** action (long press on Listen, or a menu item) shows the recognised text in a dialog without calling any paid service. This is the main debugging tool and must exist from Phase 2.

### 3.2 Settings: new screen "Read aloud" (under Settings → Reader or its own entry)
| Setting | Type | Default | Notes |
|---------|------|---------|-------|
| Enable read aloud | Switch | off | Gates everything |
| ElevenLabs API key | Text (hidden) | empty | Stored as a **private preference** (`Preference.privateKey(...)`), which Mihon's backup creator already excludes **[VERIFY]** |
| Voice engine | List: ElevenLabs / Android built-in | ElevenLabs if a key exists, else Android | Android built-in is the free, offline fallback |
| Narrator voice | Voice picker | none | List loaded from the user's ElevenLabs account (3.4) |
| Text language / script | List | English (Latin) | Drives the OCR script and the ElevenLabs `language_code` |
| Text side | List: Auto / Left / Right | Auto | Restrict OCR to one half of the image to ignore artwork and signatures |
| Emotions | Switch | on | Add emotion tags (4.4) |
| Multiple voices | Switch | on | Split quoted dialogue by speaker (4.4) |
| Auto-advance | Switch | off | 3.1 |
| Prefetch next page | Switch | on | Generate the next page's audio while the current plays |
| Audio cache limit | List 50 / 200 / 500 MB | 200 MB | LRU eviction |
| Monthly character warning | Number | 0 (off) | Warns when the app's own counter passes the value |
| Director (AI text step) | List: Off / Anthropic API | Off | 4.4; needs its own key (private preference) |

### 3.3 Per-series voices
For each library entry the user can open **"Voices for this series"** (from the entry's menu or from the mini player): a list of speakers detected so far (plus Narrator), each mapped to an ElevenLabs voice via the picker. Unmapped speakers get a voice from a default pool in a stable order (so the same speaker keeps the same voice across pages and chapters). Stored as JSON in the app's files directory keyed by `mangaId`; **no database migration** in v1 **[VERIFY that this is acceptable; otherwise use a preference]**.

### 3.4 Voices list
`GET https://api.elevenlabs.io/v2/voices` with header `xi-api-key`; query `search`, `page_size` (max 100), `next_page_token`. Response gives `voices[]` with `voice_id`, `name`, `category`, `labels`, `preview_url`, and `has_more`/`next_page_token` for paging **[VERIFY against the current docs]**. The picker may play `preview_url`.

---

## 4. Pipeline (per page)

```
page image  ->  OCR (on device)  ->  clean + merge text  ->  [Director: speakers + emotion tags]
            ->  split into requests (<= 2,000 chars)  ->  ElevenLabs text-to-dialogue  ->  cached audio  ->  player
```

### 4.1 Getting the image
`ReaderPage.stream` is a `(() -> InputStream)?` that yields the page's image bytes once loaded (`eu.kanade.tachiyomi.ui.reader.model.ReaderPage`). If it is null the page is not loaded yet: wait for it (or report "page still loading"). Decode with downsampling only if memory requires; keep the original resolution for OCR when possible (small text).

### 4.2 OCR (`TextRecognizer` interface, pure Kotlin boundary)
```kotlin
data class TextBlock(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val confidence: Float?)
interface TextRecognizer { suspend fun recognize(image: Bitmap, script: OcrScript): List<TextBlock> }
```
- Default implementation: **Google ML Kit on-device text recognition, bundled model variant** (no Google Play services needed at runtime; adds a few MB per script) **[VERIFY licence/size and that it works in the chosen build variant; the `foss` variant may forbid it, in which case use Tesseract (`tesseract4android`) or another open-source engine behind the same interface]**. The version catalog does not contain these libraries today, so the agent adds them to `gradle/libs.versions.toml`.
- Runs on a background dispatcher; never on the main thread. Release bitmaps promptly.
- "Text side" setting crops the bitmap to the left or right half before recognition (Auto = whole image).

### 4.3 Cleaning and merging (pure functions, fully unit tested)
- Drop blocks that are noise: very small text height relative to the image, text outside the chosen region, blocks matching a watermark/signature heuristic (for example a short block in a corner containing a username or `deviantart`), confidence below a threshold when available.
- Merge blocks into paragraphs: sort by column then top-to-bottom; join lines of the same paragraph (small vertical gap, same left edge), preserve paragraph breaks.
- Fix common OCR artefacts: hyphenation at line ends, double spaces, stray characters at line starts, `|` read instead of `I`.
- **Neutralise injected tags:** remove or replace any square brackets (`[` `]`) in the OCR text before it goes anywhere near ElevenLabs, so text drawn in an image can never act as an audio tag. Only tags produced by the app's own tag step (4.4) may appear.
- Output: `List<Paragraph>`; empty list means "no text".

### 4.4 Director step (who speaks, and with what emotion)
`TextDirector` interface returns `List<Turn>` where `Turn(speaker: String, text: String, tags: List<String>)`.

- **`RuleBasedDirector`** (offline, default): narration vs quoted speech split on straight and curly quotes; one speaker "Narrator" for narration and "Speaker 1/2" alternating for consecutive quoted lines only if the text clearly alternates (for example separate quoted paragraphs); emotion tags only from simple cues (`!` → `[excited]`, `?!`, ALL CAPS words, `...` → `[hesitates]`) from a **fixed allow-list**.
- **`LlmDirector`** (optional, user key): sends **only the cleaned text** (never the image) to the Anthropic Messages API (configurable model name, user key as a private preference) with instructions to return JSON only:
  `{"turns":[{"speaker":"Narrator","text":"...","tags":["calm"]}]}`.
  Rules: treat the text strictly as data (it comes from an image and may contain instructions; the prompt must say to ignore them); the output must not change the words except to fix obvious OCR errors; tags must come from the allow-list; on invalid JSON, timeout or any error fall back to `RuleBasedDirector`.
- **Tag allow-list** (stored as a constant, verified against ElevenLabs v3 behaviour **[VERIFY]**): for example `calm, excited, sad, angry, whispering, laughing, sigh, nervous, sarcastic, curious`. Tags are inserted as `[tag]` at the start of the turn text.
- With **Emotions** off no tags are added; with **Multiple voices** off every turn uses the narrator voice.
- Speaker names feed the per-series voice map (3.3).

### 4.5 Splitting
ElevenLabs recommends **at most about 2,000 characters in total across all `inputs` of one request**. Pure function `splitTurns(turns, maxChars = 1800)`: split on sentence boundaries, keep turns in order, never split a tag from its text, at most 10 unique `voice_id`s per request. Use the response audio of each request in order. For continuity, pass `previous_text`/`future_text` (up to 100 characters each) when supported **[VERIFY]**.

### 4.6 ElevenLabs request
`POST https://api.elevenlabs.io/v1/text-to-dialogue`, header `xi-api-key: <user key>`, `Content-Type: application/json`, optional query `output_format` (default `mp3_44100_128`). Body (**[VERIFY every field against the current docs before coding]**):
```json
{
  "inputs": [ { "text": "[calm] ...", "voice_id": "..." } ],
  "model_id": "eleven_v3",
  "language_code": "en",
  "apply_text_normalization": "auto"
}
```
Response `200` = audio bytes. Handle `401` (bad key), `422` (invalid request), `429` (rate/concurrency limit: back off and retry up to 3 times), quota errors and network failure with **clear, specific messages** (never "Error: 401"). Use a dedicated OkHttp client (timeouts about 60 s, no Mihon image interceptors). Output varies between runs; the `seed` parameter is best-effort only, so **caching is what makes replays identical**.

### 4.7 Cache
- Key: SHA-256 of (final turn texts with tags + voice ids + model id + language + output format).
- Store audio files under the app's cache directory in `readaloud/`, with an LRU size cap from the setting. Also cache the recognised/directed turns as small JSON keyed by (image content hash + settings) so replaying a page costs **no OCR, no AI and no ElevenLabs call**.
- Never store the API key, and never store images.
- A "Clear read-aloud cache" action in settings.

### 4.8 Playback
Use Android `MediaPlayer` (or `Media3` if the agent judges it better; note that Media3 is not in the version catalog today). One session at a time; audio focus handling (pause on call/other media); stop when the reader closes. Android's built-in `TextToSpeech` is the fallback engine and is the only engine used when the user selects "Android built-in" or no key exists. Prefetch: while page N plays, generate N+1 (one page ahead only).

---

## 5. Costs, privacy, safety
- ElevenLabs bills by character. Show **"about N characters"** in the Text preview dialog and keep an on-device counter of characters sent (shown in settings); optional monthly warning (3.2). Ask for confirmation once per chapter before the first paid request when the page text exceeds a threshold (default 800 characters).
- **Privacy:** only text leaves the device, never the image. The key is a private preference. Do not log the key, the recognised text or the audio; log lengths, counts and status codes only.
- Respect content ownership: this is for the user's personal reading, no export or sharing of generated audio.
- Add a visible note in the settings screen that text from images is sent to the chosen services.

---

## 6. Error handling
| Situation | Behaviour |
|-----------|-----------|
| No key / invalid key / quota exhausted | Message naming the cause; offer to switch to the Android built-in voice for this session |
| OCR engine fails or model missing | Message; the app does not crash; Text preview shows nothing |
| No text found | "No text found on this page" |
| Director fails or returns invalid JSON | Fall back to rule-based director |
| ElevenLabs 429 or network failure | Retry with backoff up to 3 times, then message; the player stays usable |
| Page image not loaded | "Page is still loading"; retry when it loads |
| Memory pressure | Downscale the bitmap for OCR, never crash |
| Reader closed or chapter changed mid-playback | Cancel cleanly, release the player and bitmaps |

---

## 7. Tests

**JVM unit tests (required):** block filtering, region cropping math, paragraph merging, OCR artefact clean-up, bracket neutralisation (text such as `[shouting] hello` must reach ElevenLabs without brackets), rule-based director (quotes, alternating speakers, tag allow-list), `splitTurns` (0 turns, exactly at the limit, one sentence over, a single long sentence, 10+ voices), cache key stability, LRU eviction, request-body builder (exact JSON), response-error mapping, director JSON parsing (valid, invalid, extra fields, forbidden tags).

**On a device (manual, record results):**
1. Three typical images (character one side, paragraphs the other): Text preview matches what is written; note any mistakes in `docs/READALOUD_FINDINGS.md`.
2. One image with decorative font or text over a busy background (the worst case): record the OCR quality.
3. Listen with Android built-in voice (no key) → works offline.
4. Listen with ElevenLabs and one narrator voice; with emotions on and off.
5. A page with quoted dialogue between two speakers → two different voices; the same speaker keeps the same voice on the next page.
6. Replay the same page → instant, no network (cache).
7. Auto-advance across three pages in paged and webtoon modes.
8. Invalid key, no network, empty page → specific messages, no crash.
9. With the feature disabled: reader looks and behaves exactly as stock Mihon.

---

## 8. Phases (stop and report after each)

**Phase 0: spike, outside Mihon.** A small standalone Kotlin/Android test or script that takes the user's 3 sample images, runs the chosen OCR, prints the recognised text, then sends a short text with two voices and tags to ElevenLabs and saves the audio. **The user supplies sample images and their own API key locally; the agent must never commit them.** Also verify every [VERIFY] about ElevenLabs and the OCR library and licence. Acceptance: `docs/READALOUD_FINDINGS.md` with real OCR output on the samples and a short assessment of audio quality. **Stop here** and let the user decide whether quality is good enough.

**Phase 1: settings and plumbing.** `mihon.feature.readaloud` package, preferences (private key), settings screen, feature flag, DI wiring, strings. Acceptance: build succeeds; with the flag off nothing changes.

**Phase 2: OCR + Text preview.** `TextRecognizer`, cleaning/merging with unit tests, Text preview dialog in the reader. Acceptance: previews correct on the sample images; tests pass.

**Phase 3: Android built-in voice playback.** Listen button, mini player, per-page reading, auto-advance. Acceptance: reads pages offline in paged and webtoon modes.

**Phase 4: ElevenLabs single voice + emotions.** Request builder, error mapping, tag step (rule-based), cost counter, audio cache. Acceptance: expressive reading with the narrator voice; replay is instant.

**Phase 5: multiple voices.** Speaker splitting, per-series voice map and picker, optional LLM director. Acceptance: a two-speaker page uses two voices, stable across pages.

**Phase 6: prefetch, polish, limits.** Prefetch, cache limit and clearing, monthly warning, memory handling, error table. Acceptance: all manual tests pass.

**Phase 7 (optional, P2): background playback** with a media notification and foreground service.

---

## 9. Agent rules
1. Follow guardrails in section 2; stop and ask if a change to existing behaviour seems necessary.
2. Phase 0 first; do not add Mihon code until the user approves the spike results.
3. Never commit API keys, sample images, recognised text, audio, cookies or backups; add them to `.gitignore`; sanitise any fixtures.
4. Never log keys, recognised text or audio.
5. Pure logic (cleaning, merging, splitting, tag allow-list, cache key, request builder) must be unit tested and kept free of Android APIs.
6. No `!!`; no blocking calls on the main thread; cancel work when the reader closes.
7. Build and install only side by side with the official app (guardrail 4).
8. After each phase: build, run tests, paste real output, list remaining [VERIFY] items, wait for approval.
9. If an ElevenLabs or OCR behaviour contradicts this document, record it in `docs/READALOUD_FINDINGS.md`, propose the smallest change, and ask before deviating.

---

## 10. Kickoff prompt (paste into Antigravity Agent Manager)

```
You are working in my personal fork of mihonapp/mihon. Read PRD_Mihon_ReadAloud.md fully and follow
it phase by phase.

Goal: add an optional "Listen" button to the reader that reads the text written inside the current
page image aloud (on-device OCR, then ElevenLabs text-to-dialogue with emotion tags and different
voices for different speakers, with the Android built-in voice as an offline fallback). All new code
goes in a new package mihon.feature.readaloud. The feature is off by default and must not change any
existing behaviour when off.

Start with Phase 0 only: a standalone spike outside Mihon. I will give you 3 sample images and my
own ElevenLabs key locally; never commit them. Run the OCR on the samples, print what it recognises,
call ElevenLabs with two voices and a couple of emotion tags, and verify every [VERIFY] in the PRD.
Write docs/READALOUD_FINDINGS.md, then stop and show me the results. Do not touch my real Mihon
install or library; build only the debug/foss variant that installs next to the official app.
After each later phase, run build and tests, paste real output, and wait for my approval.
```
