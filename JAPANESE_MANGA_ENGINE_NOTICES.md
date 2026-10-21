# Multilingual Comic OCR Engine — third-party notices

This optional engine is used by NovelStudio's OCR/Webtoon translation pipeline when selected. Model weights and runtime are downloaded only after the user opts in, into app-private storage; they are not bundled in the APK. The comic-specific detector is shared across Japanese, Chinese and Korean, while Korean uses a dedicated Hangul recognizer.

## PP-OCRv6_manga v0.2
- Model card: https://huggingface.co/Kellenok/PP-OCRv6_manga
- Pinned revision: `ba1d479e8a61a20e8318c9758c73fbbbd290b98d`
- License declared by the model card: Apache-2.0
- `det/manga_det_v0.2.onnx`: 1,816,954 bytes; SHA-256 `d132078c46e292b226fb5a2ca52a7612ad319262dfdf493e7d8e3be435295978`
- `rec/manga_rec_v0.2.onnx`: 21,167,540 bytes; SHA-256 `de12c84c63e62c80339e882e675983d886670dcb6f0147e1ed041afd6fa81888`
- `ppocrv6_dict.txt`: 74,947 bytes; SHA-256 `b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d`

## PP-OCRv5 Korean mobile recognizer
- Model: https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx
- Pinned revision: `5c6f574b8e2230adf4287b33e736d71b9fabd28e`
- `inference.onnx`: 13,418,787 bytes; SHA-256 `92f0b7785e64fc9090106a241cf4c1eb97472824558272751b88a2a4476d3a08`
- Matching PP-OCRv5 dictionary (official PaddleOCR, pinned revision): https://github.com/PaddlePaddle/PaddleOCR/blob/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/ppocr/utils/dict/ppocrv5_korean_dict.txt
- Dictionary size: 47,451 bytes; SHA-256 `a88071c68c01707489baa79ebe0405b7beb5cca229f4fc94cc3ef992328802d7`
- License: Apache-2.0

## ONNX Runtime Web 1.23.2
- Project: https://github.com/microsoft/onnxruntime
- Package: https://www.npmjs.com/package/onnxruntime-web/v/1.23.2
- License: MIT
- Files used: ort.wasm.min.js, ort-wasm-simd.mjs, ort-wasm-simd.wasm (single-thread SIMD build for WebView compatibility).

## Limitations
The comic detector and recognizer are trained primarily on Japanese/Chinese comics. Korean uses a general Korean recognizer paired with the comic detector, so stylized Hangul SFX still needs testing and eventual fine-tuning. The detector is preprocessed according to the model example and now uses NovelStudio's OpenCV-backed DBPostProcess for contour scoring, polygon expansion (unclip), rotated rectangles and coordinate scaling. It also applies NovelStudio's multiline split heuristic. The separate upstream furigana filter is not yet ported, and end-to-end Japanese/Chinese/Korean CER on NovelStudio has not been measured; upstream CER benchmarks must not be read as NovelStudio accuracy. The engine can still miss tiny furigana, rotated effect lettering, blurred panels and unusual fonts.
