# Japanese Manga Engine — third-party notices

The Japanese manga model is optional and is downloaded only after the user opts in. The normal NovelStudio OCR pipeline remains responsible for text-region detection, grouping/reading order, coordinates, text-removal geometry and Bengali overlay placement. PP-OCRv6_manga is used in recognition-only mode on existing app-detected text crops; it does not add or move bubbles. Its paired detector weight remains available only for the stand-alone diagnostic screen.

## PP-OCRv6_manga v0.2
- Model card: https://huggingface.co/Kellenok/PP-OCRv6_manga
- License declared by the model card: Apache-2.0
- Files: `det/manga_det_v0.2.onnx`, `rec/manga_rec_v0.2.onnx`, `ppocrv6_dict.txt`.
- Normal scan path: recognizer + dictionary only; the detector session is loaded lazily by the manual diagnostic screen.

## ONNX Runtime Web 1.23.2
- Project: https://github.com/microsoft/onnxruntime
- Package: https://www.npmjs.com/package/onnxruntime-web/v/1.23.2
- License: MIT
- Files: `ort.wasm.min.js`, `ort-wasm-simd-threaded.mjs`, `ort-wasm-simd-threaded.wasm`.

## Integration and limitations
The default OCR engine supplies each crop and its geometry. The optional recognizer returns replacement text by crop index, and the app keeps the original block geometry unchanged. The normal path processes a bounded number of existing blocks per pass to limit CPU/RAM use; every unprocessed or rejected block keeps its normal ML Kit text. If the model is not installed or fails, scanning falls back to the existing OCR. Tiny furigana, vertical/rotated sound effects, blurred scans and heavily stylised lettering can still be missed or misread. Text removal remains the existing conservative pixel cleaner, not neural inpainting, so textured artwork and complex backgrounds may retain some original glyphs.
