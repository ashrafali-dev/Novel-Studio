# Multilingual Comic OCR Engine — third-party notices

This optional engine is used by NovelStudio's OCR/Webtoon translation pipeline when selected. Model weights and runtime are downloaded only after the user opts in, into app-private storage; they are not bundled in the APK. The comic-specific detector is shared across Japanese, Chinese and Korean, while Korean uses a dedicated Hangul recognizer.

## PP-OCRv6_manga v0.2
- Model card: https://huggingface.co/Kellenok/PP-OCRv6_manga
- License declared by the model card: Apache-2.0
- Files used: det/manga_det_v0.2.onnx, rec/manga_rec_v0.2.onnx, ppocrv6_dict.txt.

## PP-OCRv5 Korean mobile recognizer
- Model: https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx
- Matching PP-OCRv5 dictionary: https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/49043b4cb8eaf8388a6399394ebdc014851c217c/paddle/PP-OCRv5/rec/korean_PP-OCRv5_rec_mobile/ppocrv5_korean_dict.txt
- Dictionary SHA-256: `a88071c68c01707489baa79ebe0405b7beb5cca229f4fc94cc3ef992328802d7`
- License: Apache-2.0

## ONNX Runtime Web 1.23.2
- Project: https://github.com/microsoft/onnxruntime
- Package: https://www.npmjs.com/package/onnxruntime-web/v/1.23.2
- License: MIT
- Files used: ort.wasm.min.js, ort-wasm-simd-threaded.mjs, ort-wasm-simd-threaded.wasm.

## Limitations
The comic detector and recognizer are trained primarily on Japanese/Chinese comics. Korean uses a general Korean recognizer paired with the comic detector, so stylized Hangul SFX still needs testing and eventual fine-tuning. The engine can still miss tiny furigana, rotated effect lettering, blurred panels and unusual fonts.
