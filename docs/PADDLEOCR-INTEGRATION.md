# PaddleOCR integration in NovelStudio

NovelStudio integrates the Android inference implementation from the official PaddleOCR repository:
https://github.com/PaddlePaddle/PaddleOCR/tree/main/deploy/ppocr-android/ppocr-sdk

The copied SDK source files retain their upstream Apache-2.0 headers. The corresponding license is included in `THIRD_PARTY_LICENSES/PaddleOCR-LICENSE.txt`.

## Runtime design

- ML Kit remains the default engine and requires no optional model download.
- PP-OCRv6 Small and PP-OCRv6 Medium/Heavy are downloadable, on-device ONNX model packs.
- Model binaries are stored privately in the app's files directory and are not bundled in the APK.
- Paddle OCR detection returns quadrilateral text regions; the adapter maps them to NovelStudio's image-coordinate `OcrBlock`, preserving polygons for positioning and scaling.
- Paddle's detection/cropping/recognition/postprocessing pipeline is used directly. Existing NovelStudio line grouping, bubble-interior detection, layout persistence, text removal and translation rendering remain downstream.
- A Paddle model initialization/inference failure falls back to ML Kit. Android below API 26 also uses ML Kit because the official Paddle Android SDK targets API 26+.
- Model downloads are validated before they are installed. Small detector revision is pinned; model revisions and published recognition/detection hashes are set in `PaddleOcrModels.kt`.

## Model profiles

- PP-OCRv6 Small: lower resource profile intended for routine scanning.
- PP-OCRv6 Medium/Heavy: larger model option for users prioritizing OCR detail at a higher RAM/latency cost.

The standard PP-OCRv6 Small/Medium recognition dictionaries are primarily suited to English/Chinese. NovelStudio routes Korean and Japanese to its existing language-specific PP-OCRv4 model packs when those are installed, and otherwise falls back to ML Kit rather than feeding those scripts to an incompatible dictionary. Install Korean/Japanese packs from the legacy model section when using those languages. The names do not promise that Heavy is more accurate on every manga font or language. Actual CER/WER and speed depend on language, artwork, device and test corpus. PaddleOCR detects text regions, not entire speech-balloon shapes; NovelStudio preserves its existing image-aware bubble-interior detection, line grouping, cumulative layout and translation-placement logic.

## Runtime dependencies

The official Android implementation uses ONNX Runtime 1.21.1, OpenCV 4.5.3 and Kotlin coroutines. The application retains minSdk 24, but the Paddle adapter explicitly uses ML Kit on API 24–25.
