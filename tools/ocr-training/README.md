# NovelStudio multilingual comic OCR training

This folder is the training-data entry point for improving stylized lettering without changing the Android runtime.

## Runtime in this branch

- **Detection:** PP-OCRv6 Manga v0.2, trained for manga/webtoon-style line detection.
- **Japanese + Chinese recognition:** the matching PP-OCRv6 Manga recognizer and dictionary.
- **Korean recognition:** PP-OCRv5 Korean mobile recognizer and its matching Hangul dictionary.
- **Ensemble:** the app also runs the selected ML Kit recognizer over the same image and reconciles overlapping results by geometry, text agreement and confidence. This lets a second recognizer recover some lines that the comic model misses.

The existing weights are reused rather than pretending a newly trained model already exists. The intended custom training target is **Korean comic lettering**, because the Korean recognizer is currently a general printed-text model. Japanese and Chinese already use a manga-trained recognizer.

## Dataset to collect

Create line-level crops (one text line or one SFX lettering region per image) and an UTF-8 `annotations.jsonl`. Keep the original image crop, and write the exact intended transcription. A sample record:

```json
{"image":"crops/ko_000001.png","text":"진짜?!","script":"ko","group":"series-a/chapter-12"}
{"image":"crops/ko_000002.png","text":"콰앙","script":"ko","group":"series-a/chapter-12"}
```

For Korean, include clean dialogue, bold/outlined text, colored text, glow/shadow, curved or slanted SFX, tiny text, vertical text, low-contrast text and text over artwork. Keep Unicode exactly as printed, including punctuation. The `group` field should identify a chapter/source group so similar panels do not leak from training into validation.

## Prepare recognition data

Install Python 3.10+ and Pillow (only used for image verification/copying):

```bash
python -m pip install pillow
python tools/ocr-training/prepare_recognition_crops.py \
  --annotations annotations.jsonl \
  --output ./ko-comic-dataset \
  --script ko \
  --val-ratio 0.1 \
  --seed 2026
```

The command checks that images exist and are readable, splits by group, copies the crops, and writes PaddleOCR-style `train_list.txt` and `val_list.txt`. Each list line is `relative/image/path<TAB>ground-truth text`. It does not download copyrighted chapters or manufacture labels.

## Fine-tuning and release workflow

1. Start from the official PP-OCRv5 Korean mobile recognizer training checkpoint and its exact character dictionary/config.
2. Fine-tune with the prepared Korean comic crops, retaining regular printed Korean samples to avoid losing ordinary text accuracy.
3. Evaluate on a **chapter-separated** holdout set. Report character error rate (CER), empty-recognition rate, and separate scores for dialogue, SFX and small text.
4. Export the trained recognizer to ONNX. Validate its output shape, dictionary order, blank index, image normalization and dynamic width against the Android/WASM decoder before changing model URLs.
5. Keep the Manga detector shared; do not replace a model that already works for Japanese/Chinese merely to improve Korean.

No accuracy claim should be made until a fixed Japanese/Chinese/Korean screenshot set is run through both the old and new engines.
