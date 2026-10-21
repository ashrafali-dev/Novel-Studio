# Multilingual Comic OCR Engine — third-party notices

This optional engine is used by NovelStudio's OCR/Webtoon translation pipeline when selected. Model weights and runtime are downloaded only after the user opts in, into app-private storage; they are not bundled in the APK. The comic-specific detector is shared across Japanese, Chinese and Korean, while Korean uses a dedicated Hangul recognizer.

## PP-OCRv6_manga v0.2
- Model card: https://huggingface.co/Kellenok/PP-OCRv6_manga
- Pinned revision: `ba1d479e8a61a20e8318c9758c73fbbbd290b98d`
- License declared by the model card: Apache-2.0
- Detector file is pinned to the model-card revision; its actual downloaded length and SHA-256 are recorded in the install manifest.
- Recognizer file is pinned to the same model-card revision; its actual downloaded length and SHA-256 are recorded in the install manifest.
- Matching dictionary is pinned to the model-card revision; its actual downloaded length and SHA-256 are recorded in the install manifest.

## PP-OCRv5 Korean mobile recognizer
- Model: https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx
- Pinned revision: `5c6f574b8e2230adf4287b33e736d71b9fabd28e`
- The Korean recognizer is pinned to its model revision; its actual downloaded length and SHA-256 are recorded in the install manifest.
- Matching PP-OCRv5 dictionary (official PaddleOCR, pinned revision): https://github.com/PaddlePaddle/PaddleOCR/blob/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/ppocr/utils/dict/ppocrv5_korean_dict.txt
- The matching dictionary is pinned to the official PaddleOCR revision; its actual downloaded length and SHA-256 are recorded in the install manifest.
- License: Apache-2.0

## ONNX Runtime Web 1.23.2
- Project: https://github.com/microsoft/onnxruntime
- Package: https://www.npmjs.com/package/onnxruntime-web/v/1.23.2
- Runtime asset download base: `https://unpkg.com/onnxruntime-web@1.23.2/dist/` (not jsDelivr; jsDelivr returned HTTP 404 for the threaded `.mjs` asset in the app's download test).
- License: MIT
- Files used: `ort.wasm.min.js`, `ort-wasm-simd-threaded.mjs`, `ort-wasm-simd-threaded.wasm` from the published 1.23.2 package. The appassets loader applies same-origin COOP/COEP headers to the hidden OCR document; the runtime sets `numThreads = 1` to limit CPU use.

## Limitations
The comic detector and recognizer are trained primarily on Japanese/Chinese comics. Korean uses a general Korean recognizer paired with the comic detector, so stylized Hangul SFX still needs testing and eventual fine-tuning. English uses the app's bundled ML Kit Latin recognizer and does not require this model pack; the model-management test screen includes an English option that can run without downloading the CJK pack. The detector is preprocessed according to the model example and now uses NovelStudio's OpenCV-backed DBPostProcess for contour scoring, polygon expansion (unclip), rotated rectangles and coordinate scaling. It also applies NovelStudio's multiline split heuristic. The separate upstream furigana filter is not yet ported, and end-to-end Japanese/Chinese/Korean CER on NovelStudio has not been measured; upstream CER benchmarks must not be read as NovelStudio accuracy. The engine can still miss tiny furigana, rotated effect lettering, blurred panels and unusual fonts.
