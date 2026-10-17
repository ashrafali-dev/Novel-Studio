# Japanese Manga Engine — third-party notices

This optional engine is separate from NovelStudio's existing OCR/Webtoon translation pipeline. OCR weights and the runtime are downloaded only after the user opts in, into app-private storage; they are not bundled in the APK.

## PP-OCRv6_manga v0.2
- Model card: https://huggingface.co/Kellenok/PP-OCRv6_manga
- License declared by the model card: Apache-2.0
- Files used: det/manga_det_v0.2.onnx, rec/manga_rec_v0.2.onnx, ppocrv6_dict.txt.

## ONNX Runtime Web 1.23.2
- Project: https://github.com/microsoft/onnxruntime
- Package: https://www.npmjs.com/package/onnxruntime-web/v/1.23.2
- License: MIT
- Files used: ort.wasm.min.js, ort-wasm-simd-threaded.mjs, ort-wasm-simd-threaded.wasm.

## Multilingual PaddleOCR pack (optional, on-demand download)
- Detection model: [PaddlePaddle/PP-OCRv6_small_det_onnx](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/tree/main) — Apache-2.0.
- General recognition model: [PaddlePaddle/PP-OCRv6_small_rec_onnx](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/tree/main) — Apache-2.0; used for Latin/English, Chinese and Japanese text.
- Korean recognition model: [PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx](https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx/tree/main) — Apache-2.0.
- Character dictionaries: [PaddleOCR ppocr/utils/dict](https://github.com/PaddlePaddle/PaddleOCR/tree/main/ppocr/utils/dict) — PaddleOCR project license applies.
- The model files and ONNX Runtime Web 1.23.2 are downloaded only after the user opts in. They are stored in app-private storage and do not increase the APK download size. The optional pack is about 50 MB, depending on server response size. The ONNX Runtime Web package is MIT licensed.

## Limitations
- The dual-label YOLO bubble/text segmentation weight from the upstream Manga Translator repository is not included because the public source tree does not supply the weight or a verified download URL. This integration uses PaddleOCR text-line detection plus recognition instead; it is not equivalent to a bubble segmentation model.
- OCR can still miss or misread tiny furigana, rotated sound effects, blurred panels and unusual fonts. ML Kit remains the fallback if the optional model pack is absent or an OCR call fails. Models are not bundled in the APK.
