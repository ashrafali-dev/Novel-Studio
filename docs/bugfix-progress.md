# Bugfix progress log — 2026-10-24 session

Branch: `fix/ocr-cache-inpaint-perf-20261024` (from `origin/main` @ `d94788b`, version 1.6.9 / versionCode 69).

**Rule for this log:** only record what was verified in THIS session (file:line evidence, a test, or a CI run).
Do not trust earlier crashed sessions. Every entry has a commit hash once committed.

## Constraints in force

- Remote-first: edits/CI on GitHub; no local Gradle builds, no model downloads, no APK downloads on the phone.
- `main` is protected only in the sense that it is the published branch: `ci-latest` release is updated by
  `.github/workflows/build.yml` on every push to `main`. The release pipeline publishes APKs from `main` only.
- CI for this work: `.github/workflows/verify.yml` (unit tests + signed release build) on this branch and on PRs.
- No credentials/signing material/tokens in the repo (verified: `git log --all -S'ghp_'` = 0 hits).

## Repository facts established (read from origin/main)

- OCR router: `WebModeExtractor.defaultEngine` → `RoutingOcrEngine` (choose "lens", "paddle-small",
  "paddle-high"/"heavy", "manga", "mlkit"). Google Lens = `LensOcrEngine` + `LensProtocol` (hand-written protobuf).
- Paddle packs: `PaddleOcrModels.PACKS` = `small`, `heavy` (PP-OCRv6 Medium), `korean` (PP-OCRv5 Korean rec + v6 det).
- Ahead cache: `WebModeExtractor` (`aheadCache`, `aheadDone`, `aheadBlank`, `aheadFails`) — keyed by
  `WebModeLogic.normalizeUrl(p.src)`, used by `processAhead` and read by the visible path (`processInner:707`).
- Inpaint: `WebModeInpainter` (LaMa int4/int8 ONNX, 37 MB, SHA-256 pinned) — download, warm-up, `fill()`.
- Chatbot completion: `MainActivity.pollJob` + `Js.readLen` + `RealtimeCompletionPolicy`.

## Findings (evidence)

### A. OCR — Lens
- A1 `LensOcrEngine.post()` sets the request-local HTTP status with **`lastHttpStatus.set(0)` on every call**
  (`LensOcrEngine.kt:380`), but concurrent visible/ahead requests share the engine and `postOnce` only writes
  `a.status` on the `Attempt` object; the thread local is only published on success (`publish()` in `race()`).
  A failing request therefore reports `http=0` even when the server answered 200 — the reported
  "http=200 paragraphs=0" and "http=0 cancelled" diagnostics in the visible log are both distorted by this.
- A2 Image preparation (`prepare()`): an image is only downscaled when `area > 4 MP AND width/height > 2400`,
  yet the upload cap is 3.5 MB — a 2 400×2 400 photo-like panel can then fail with "picture too large to upload".
- A3 Lens silence (200 + zero paragraphs and zero lines) is used as `NO_TEXT` and then **cached** for
  `CACHE_TTL_MS` (3 min) because the negative TTL check uses `lines.isEmpty()`: a silent reply suppresses retries.
  (verify)
- A4 Cancellation source to confirm: `race()` → `InterruptedIOException("cancelled")` → "Google Lens: cancelled";
  also `Thread.currentThread().isInterrupted` check (`LensOcrEngine.kt:390`). (trace callers)

### A. OCR — Paddle
- P1 `PaddleV6OcrEngine.getRuntime()` is annotated `@Synchronized` (`PaddleV6OcrEngine.kt:34-35`); `run()` does
  `synchronized(lock) { getRuntime(); rt.run(bitmap) }`. The router serialises calls (`OcrGate`), so this is not
  the failure cause; record only. (verify)
- P2 `PaddleOcrModels` validation only checks file size >= 1 MB and config text; recognition failures surface as
  "empty/short" results → router falls back to ML Kit silently. (trace expected symptoms)

### B. Duplicate panel downloads / OCR
- B1 `processAhead` caches **only after OCR**: bytes are fetched inside the loop and discarded; the visible scan
  always calls `fetchWithRetry` for its own bytes. => two downloads per panel even when ahead finished first.
- B2 If ahead OCR is cancelled (`!alive()`), the loop returns; bytes already downloaded are not kept.
- B3 If ahead OCR is *in progress*, the visible scan has no in-flight reuse (no job registry) → duplicate OCR.

### C. Inpaint
- C1 `WebModeInpainter.restore()` is never called (grep: no call sites) → persisted `inpaintCrashes` is never
  restored, so the "disable after MAX_CRASHES" guard does nothing across process restarts.
- C2 `noteFailure()` is never called → the crash/freeze guard never counts a crash.
- C3 `warmUpAsync()`: one load attempt per process (`loadAttemptsInProcess`), failure => `sessionFailed` forever.
- C4 `loadSession()`: `addConfigEntry("session.disable_quant_qdq","1")` is claimed as *required* by this model —
  that key dequantizes int4/int8 weights into fp32; must be validated (attempt load without it remotely).
- C5 `inferLocked()` cost: mask assembly calls `bitmap.getPixel`? (verify) — per 512×512 loop.

### D. Chatbot answer verification
- D1 `pollJob` loops `handler.postDelayed` + `chatWv.evaluateJavascript` — one JS probe per poll (~90–160 ms in
  real-time mode). Measure the JS probe cost (`Js.readLen` DOM scan) before claiming latency.

## Plan (small, independently verifiable commits)

1. [x] Branch + `verify.yml` + this log.
2. OCR: make Lens diagnostics request-local + accurate report of silence, image math fix, negative-cache fix; tests.
3. OCR: Paddle config/failure surfacing; tests.
4. Panel cache shared by ahead/foreground + in-flight reuse; tests.
5. Inpaint: guarded native load that can never kill startup, restore/noteFailure wiring, QDQ validation.
6. Lap: chatbot verification probe measurement + optimisation (only if measurable).
7. Full remote CI, version bump, release from `main`.

## Commits

- `73d268a` verify.yml + this log — CI run 40031720598: build ok, tests failed only because Gradle
  evaluates release signing without secrets (fixed with a throwaway keystore in the test job).
- `9110f50` Lens reporting/limits + Paddle config validation + remote model check — CI 40033355467:
  the model-check job PASSED.
- `a37f088` compile fixes (LensTransport.Answer is an IOException, open class) — see CI after `7b04f83`.
- `7b04f83` shared PanelStore + PanelOcrCache (Bug B) — unit tests PanelStoreTest, PanelOcrCacheTest.

## Verified evidence so far

- Paddle model packs: pinned revisions serve exactly the SHA-256 the app hardcodes (small det
  9 880 512 B `d73e0058…`, small rec 21 159 378 B `5435fd74…`, medium rec 76 554 979 B
  `9c09abf0…`, Korean rec 13 418 787 B `92f0b778…`), recognisers report `dict + blank + space`
  classes and a 48 px input height (CI job "PaddleOCR model validation" = success).
- Lens: a 200 body without objects_response was thrown as `reply without text layer`; the
  diagnostic only ever showed the LAST attempt's status with no cause. Both are fixed and tested.


## Blocker: chatbot latency measurements need on-device/WebView runs

`pollJob`/`Js.readLen` cannot be measured on the JVM: the probe is DOM/WebView JS (`document.querySelector`,
`__asst`, `__trk`). No emulator is available in this environment; instrumenting on the phone is also excluded
(no builds on the phone). Plan: extract the completion decision into pure-logic code with deterministic tests
(decision rules), plus timing instrumentation in the log, and report the latency measurement as blocked.

## 2026-10-24 recovery session

CI had been failing since `b304a63`; the Bug C change was written but never compiled.

- `cceab6b` LensOcrEngine: `HttpStatus`/`Answer` expose `status`, not `code`; WebModeInpainter:
  missing `android.util.Log` import; added `fileState()` used by the failure dialog.
- `b50fe47` the remaining `cause.code` at LensOcrEngine:572.
- `4ecf814` `OcrPost.joinableColumns` made internal so `VerticalColumnMergeTest` compiles.
- `ab12941` test failures once compilation worked: PanelStore cache-hit path, byte budget
  (evicted `completed` markers kept the bytes alive), preparedSize doc/behaviour, `pollReplyText`
  field index (readLen has 10 fields; the reply is index 8, not 7), tategaki columns joined
  without a space, safeTextRect sliver rule.
- `e376b10` safeTextRect MIN_RELATIVE 0.18 -> 0.55 and the starter-cancellation hand-off.
- latest: PanelStore retries a load cut short by the starter's interrupt (its own class doc
  already required that the shared download survive caller cancellation).

Still open: full green `Verify (tests + build)` on the branch, then the release steps.

## Released 1.7.0 (2026-10-24)

- `eb6b15a` CI green (40043905170, all 3 jobs, no failed steps).
- Merge commit `18cc26e` (main = d94788b + eb6b15a); build.yml on main success (40044399609).
- Tag `v1.7.0` -> `18cc26e`; tag build 40044964176 success.
- Release https://github.com/ashrafali-dev/Novel-Studio/releases/tag/v1.7.0
  asset NovelStudio-SIGNED-RELEASE-APK.apk, 73 821 603 B, state=uploaded,
  served by the API (HTTP 206, application/vnd.android.package-archive).

Known environment issue: plain `github.com/.../releases/download/...` returns 404
from this network for EVERY release asset, including the pre-existing `ci-latest`.
The authenticated asset API serves it correctly.

Outstanding:
- Chatbot reply latency is still unmeasured (WebView DOM JS; no emulator in CI).
- verify.yml does not run on main (build.yml does), so main's PR-style job matrix
  is only validated on feature branches.
- Actions artifact quota is exhausted; APK artifacts are best-effort.

## 2026-10-24 Reading Panel / Live Lens pipeline diagnosis

Branch `fix/live-panel-reply-completion-20261024` (from main 18cc26e).

Reproduced from the screenshot, by reading the code (no device): Claude answered
`[001]…[007]`, two bubbles got `[008] B` / `[009] Q`, no bubble was painted, and the
app said "did not send any text".

P1  WebModeLayout.align accepts a one-character body as a translation.
    `body.isNotEmpty()` is the only test, so `[008] B` becomes the text of bubble 8.
    Proven: aligning the screenshot reply with n=9 yields out[7]="B", out[8]="Q"
    and `pass:true`. Symptom 3 (and 1: bogus bubbles count toward the 70% floor).

P2  align's 70% admission rule discards the WHOLE reply on a reading-panel scan.
    titleOffset=1 there, so `found*100 >= n*70` applies; 6/7 = 85% passes but e.g.
    4/7 = 57% returns null, and applyTranslation then toasts "Could not show the
    translation" and places nothing. Live batches are unaffected (titleOffset=0).
    This is the "translation visible in the chatbot, no bubble painted" case.

P3  Completion needs `meaningfulReply || (filledReply && allMarkers)`.
    A reply that is only markers has neither, so polling continues to the 6-minute
    timeout even though the chatbot finished, and the user is told the chatbot sent
    no text (symptom 2). A one-char body like "B" also passes __meaningful, so the
    opposite error exists too: garbage is accepted as done.

Planned fixes: reject implausible one-char bodies, make the 70% rule per-marker
instead of all-or-nothing, and treat "all markers present and stable" as a valid
completion signal. Each gets a JVM test.

### Fix (commit 1)

- `WebModeLayout.isPlausibleTranslation`: a marker body needs >= 2 letters/digits, so
  `[008] B` / `[009] Q` leave their bubbles blank instead of painting a stray letter.
- `WebModeLayout.align`: one plausible marker is enough. The 70% floor threw away the
  whole reply on reading-panel scans (titleOffset=1) when the chatbot had answered the
  page only partially.
- `Js.__plausible` + `readLen`: `filled` uses the same rule, so a truncated reply no
  longer reports itself complete (`done=1`) and the poll keeps waiting for real text.
  Uses `\p{L}\p{N}` with a code-point fallback for old WebViews.
- Tests: `WebModeAlignTest` (11 cases + plausibility parity with the JS rule).

Verified locally on OpenJDK 17 (no Gradle, no build): the align algorithm and the
plausibility rule both reproduce every test expectation.

Also checked, NOT a bug: `[~～]` is two literals in java.util.regex (a range needs an
explicit `-`); every CJK/Bengali translation correctly fails that match.

### Still to verify (blocked)

CI cannot run: Actions billing is blocked ("recent account payments have failed").
The Kotlin has therefore not been compiled and the new tests have not been run in CI.

### Fix (commit 2) — the "did not send any text" state

Root cause, reproduced on the JVM: a read that returns only UI chrome
("Write a message…", "Message collapsed Show more") is cleaned to an empty string,
so `hasMeaningfulRealtimeReply` is false and the job failed with "The chatbot did
not send any text" while the answer was visible. `__trk` can miss the answer's own
nodes for a poll (short nodes, base-text duplicates), and the only text it returned
was chrome.

- `ReplyHandoff.shouldReRead` + `MAX_RE_READS = 3`: a chrome-only read is re-read
  (250 ms apart) before the job is failed. The re-read goes to `readText()`, whose
  cache was already consumed, so it performs a fresh page scan.
- The counter resets on a good read and for every new job.
- `ReplyRereadTest` covers the bound, the reset and the terminal case.

Unchanged on purpose: when all re-reads are chrome, the job still fails with the
same message and the same bounded auto-retry.

### Fix (commit 3) — stage diagnostics

Placement had no logging at all, so "nothing appeared on the page" left no evidence.
Added `PLACE …` lines (tag `NovelStudioOCR`), counts and flags only, never the text:

- `PLACE start live=<bool> blocks=<n> placed=<n> markers=<n>` (only when loggable)
- `PLACE skipped: layout=… placed=…` / `PLACE skipped: no scan layout…`
- `PLACE scan mode blocks=… placed=…`
- `PLACE result live=… ok=<bool> script=<n>B`

### Verification performed locally (no CI)

The Kotlin compiler is available in the Gradle cache, so the changed files were
compiled and the real logic executed (no APK, no device, no models):

- `ReplyHandoff.kt` compiles; a harness exercised `shouldReRead` at attempts 0-2
  (retry), 3-50 (terminal), the meaningful case and the bound: ALL PASS.
- `WebModeLayout.kt` compiles (only stubs for Android/JSON siblings were supplied);
  a harness ran the REAL `align` and `isPlausibleTranslation` over the reported
  reply, partial answers, titleOffset, reversed/repeated/out-of-range/markerless
  replies, tilde bodies and the plausibility parity set: 34/34 PASS.

Still blocked: GitHub Actions cannot run (billing). Nothing has been built into an
APK, and `MainActivity.kt` (Android-dependent) has not been compiled.
