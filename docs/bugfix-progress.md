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
