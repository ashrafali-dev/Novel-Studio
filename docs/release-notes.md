# Novel Studio 1.8.0

Fixes the Reading Panel / Live Lens pipeline, on top of everything in 1.7.0.

## Bubble placement and reply completion

- A marker whose text is a single letter or symbol (for example `[008] B`, `[009] Q`) is no longer
  painted over the artwork. Such a bubble keeps its original text instead of showing a stray
  character.
- A partially answered page now places the bubbles it does cover. Previously the whole reply was
  discarded unless at least 70% of the bubbles had a translation (reading-panel scans), so a good
  answer could produce nothing on the page.
- "The chatbot did not send any text" no longer appears while the answer is visible. A read that
  returned only interface labels is now re-read a few times before the job is reported as failed.
- The completion poll and the placement step now agree on what counts as a finished translation,
  so a reply that is still being written is not treated as final.

Diagnostic logging was added for each placement stage (counts only, never the translation).

## Google Lens OCR

- A 200 reply without the objects layer is no longer reported as "the picture has no text"; it is
  reported as a silent reply and never cached as an empty result.
- The failure line now reports the real HTTP status and cause instead of a misleading `http=0`.
- Upload sizing respects both the pixel-area and the dimension limit, so a large screenshot is no
  longer rejected with "picture too large to upload".
- Cancellations are distinguished from network faults; a cancelled scan no longer trips the breaker.

## PaddleOCR (small / high / Korean)

- The recognition config is validated by its actual `PostProcess: -> character_dict:` layout instead
  of two substring matches, so a detector yml or an error page can no longer be accepted as a
  dictionary (which left OCR permanently empty).
- Pinned model packs are verified in CI against the SHA-256 values the app hardcodes.

## Panel downloads and OCR (read-ahead vs. visible scan)

- One process-wide store for panel bytes: a panel already downloaded is never fetched twice, and a
  caller joins the fetch that is already running instead of starting its own.
- OCR results are shared the same way, with matching panel identity (signed CDN URLs with different
  query strings stay distinct).
- A cancelled read-ahead keeps the bytes it already downloaded.
- The byte budget is now actually enforced.

## AI Inpaint

- The model load can no longer create a start-up crash loop: a marker written before the load lets
  the next start detect that the process died inside the native load, switch AI Inpaint off, and
  stop after a small number of failures.
- An interrupted download is detected on the next start instead of being assumed complete.
- A failed load retries with the leanest session options and publishes the reason in the menu.
- Warm-up declines to load when the phone lacks memory headroom.
- The load/inference/delete paths share one lock, so a session can never be closed under a running
  inference.

## Chatbot reply hand-off

- The completion poll's reply is reused instead of walking every text node of the chat page again.

**Limitation:** the chatbot reply-path latency change has **not been measured**. The before/after
comparison is still pending because the relevant code is WebView DOM JavaScript and cannot run under
the JVM unit tests, and no emulator is used in this project's CI. No latency improvement is claimed.

## Tests

JVM unit tests cover Lens reply classification and upload geometry, Paddle config validation, the
panel byte/OCR caches (including cancellation), reply hand-off, script detection, bubble geometry,
marker-to-bubble alignment (`WebModeAlignTest`) and the chrome-only re-read rule (`ReplyRereadTest`).
CI also builds and verifies the signed release APK.
