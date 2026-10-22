# NovelStudio Lens OCR — Chromium Extension (MV3)

A desktop Chromium extension prototype inspired by NovelStudio's OCR pipeline. It discovers large images, batches them, sends them through a Google Lens web upload adapter, caches by image-content hash + target language, translates OCR text, and places selectable translated text in a live overlay aligned to each image.

## Install locally
1. Clone or download this repository on a desktop computer.
2. Open chrome://extensions (or edge://extensions).
3. Enable Developer mode.
4. Choose Load unpacked and select the desktop-extension folder.
5. Open an image-heavy chapter page and click the extension icon to toggle it. Configure language at Extension options.

## Pipeline
- Discovery: MutationObserver watches new DOM nodes and discovers sufficiently large img elements.
- Batching: bounded image queue with configurable batch size and wait.
- OCR: background service worker posts the image to the unofficial Google Lens web upload endpoint.
- Cache: SHA-256 of image bytes + target language, with a seven-day TTL.
- Translation: Google Translate's unofficial gtx endpoint; target language is selectable.
- Placement: live overlay positioned over the image; original OCR text is available as a title.

## Important limitations
- This is a JavaScript port of the pipeline pattern, not binary reuse of Kotlin code. Android/Kotlin WebView methods cannot be imported directly into a Chromium extension; algorithms must be ported and verified against the source.
- Google Lens and Google Translate endpoints are undocumented/unofficial and can change, rate-limit, reject requests, or return a page that is not OCR text. A production release should validate the Lens response structure against known fixtures and add a robust parser.
- This first implementation scans regular img elements. CSS backgrounds, canvas-only panels, closed shadow roots, and authenticated images may not be recognized. Some image servers reject extension fetches.
- Translation endpoint availability is not guaranteed. No API keys or credentials are bundled.
- The extension requests all-site access at runtime so it can scan chapter pages. Grant permission only for sites you trust. Images/text are sent to Google services for OCR/translation.

## Files
- manifest.json — Manifest V3 metadata and permissions.
- content.js — image discovery, batching, live overlay placement.
- background.js — Lens upload, translation, hashing, local cache.
- options.html / options.js — target language and performance settings.

## Validation
No build step is required: load the folder as an unpacked MV3 extension. Before publishing, test on Chrome and Edge with a known sample image and verify Lens responses. Google Lens is not a stable public OCR API.
