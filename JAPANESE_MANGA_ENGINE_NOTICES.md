# Japanese Manga Engine — third-party notices

The Japanese manga OCR engine is an optional, dedicated detector/recognizer that can feed NovelStudio's existing translation and bubble-overlay pipeline. OCR weights and the runtime are downloaded only after the user opts in, into app-private storage; they are not bundled in the APK.

## PP-OCRv6_manga v0.2
- Model card: https://huggingface.co/Kellenok/PP-OCRv6_manga
- License declared by the model card: Apache-2.0
- Files used: det/manga_det_v0.2.onnx, rec/manga_rec_v0.2.onnx, ppocrv6_dict.txt.

## ONNX Runtime Web 1.23.2
- Project: https://github.com/microsoft/onnxruntime
- Package: https://www.npmjs.com/package/onnxruntime-web/v/1.23.2
- License: MIT
- Files used: ort.wasm.min.js, ort-wasm-simd-threaded.mjs, ort-wasm-simd-threaded.wasm.

## Integration and limitations
The reader can route Japanese OCR to this model, group detected text lines into speech-bubble units, pass each bubble as one translation segment, and draw the Bengali result over its source region. The grouping and text-removal steps are heuristic and need device testing on different manga readers. Tiny furigana, vertical/rotated sound effects, blurred scans, overlapping balloons and textured artwork can still be missed or misread. The current pixel cleaner is conservative: it is intended for high-contrast lettering on a reasonably flat bubble background, not full neural inpainting of complex artwork.
