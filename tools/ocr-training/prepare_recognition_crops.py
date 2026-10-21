#!/usr/bin/env python3
"""Prepare line-level crops for a single-script PaddleOCR recognizer fine-tune.

Input JSONL records:
  {"image": "relative/path.png", "text": "exact transcription",
   "script": "ko", "group": "series/chapter"}

Images must already be cropped to a single text line / SFX region. The script
validates images, filters by script, splits by source group to avoid leakage,
and writes PaddleOCR's path<TAB>label train/validation lists.
"""
from __future__ import annotations

import argparse
import json
import random
import shutil
import sys
from collections import defaultdict
from pathlib import Path

from PIL import Image, UnidentifiedImageError

SUPPORTED = {"ja", "zh", "ko"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--annotations", type=Path, required=True, help="UTF-8 JSONL annotation file")
    parser.add_argument("--output", type=Path, required=True, help="Output dataset directory")
    parser.add_argument("--script", choices=sorted(SUPPORTED), required=True, help="One recognizer/script per dataset")
    parser.add_argument("--val-ratio", type=float, default=0.1, help="Validation fraction of groups (default: 0.1)")
    parser.add_argument("--seed", type=int, default=2026)
    return parser.parse_args()


def read_records(annotation_path: Path, script: str) -> list[dict]:
    root = annotation_path.resolve().parent
    records: list[dict] = []
    seen = set()
    with annotation_path.open("r", encoding="utf-8-sig") as stream:
        for line_no, raw in enumerate(stream, start=1):
            if not raw.strip():
                continue
            try:
                item = json.loads(raw)
            except json.JSONDecodeError as exc:
                raise ValueError(f"{annotation_path}:{line_no}: invalid JSON: {exc}") from exc
            if not isinstance(item, dict):
                raise ValueError(f"{annotation_path}:{line_no}: record must be a JSON object")
            if item.get("script") not in SUPPORTED:
                raise ValueError(f"{annotation_path}:{line_no}: script must be one of {sorted(SUPPORTED)}")
            if item["script"] != script:
                continue
            image_value = item.get("image")
            text = item.get("text")
            if not isinstance(image_value, str) or not image_value.strip():
                raise ValueError(f"{annotation_path}:{line_no}: image path is required")
            if not isinstance(text, str) or not text.strip():
                raise ValueError(f"{annotation_path}:{line_no}: non-empty exact text is required")
            image_path = (root / image_value).resolve()
            if not image_path.is_file():
                raise ValueError(f"{annotation_path}:{line_no}: image not found: {image_path}")
            try:
                with Image.open(image_path) as img:
                    img.verify()
            except (UnidentifiedImageError, OSError) as exc:
                raise ValueError(f"{annotation_path}:{line_no}: unreadable image: {image_path}: {exc}") from exc
            key = (str(image_path), text.strip())
            if key in seen:
                continue
            seen.add(key)
            group = str(item.get("group") or image_path.parent.as_posix())
            # Recognition list labels are single-line strings. Preserve CJK characters exactly;
            # replace only TSV/newline separators that would corrupt the annotation format.
            label = text.strip().replace("\t", " ").replace("\r", " ").replace("\n", " ")
            records.append({"image_path": image_path, "text": label, "group": group})
    return records


def main() -> int:
    args = parse_args()
    if not 0.02 <= args.val_ratio <= 0.4:
        print("--val-ratio must be between 0.02 and 0.4", file=sys.stderr)
        return 2
    if not args.annotations.is_file():
        print(f"Annotation file not found: {args.annotations}", file=sys.stderr)
        return 2

    try:
        records = read_records(args.annotations, args.script)
    except (OSError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        return 2
    if len(records) < 10:
        print(f"Need at least 10 valid {args.script} crops; found {len(records)}", file=sys.stderr)
        return 2

    groups: dict[str, list[dict]] = defaultdict(list)
    for record in records:
        groups[record["group"]].append(record)
    group_names = sorted(groups)
    random.Random(args.seed).shuffle(group_names)
    if len(group_names) > 1:
        val_count = max(1, min(len(group_names) - 1, round(len(group_names) * args.val_ratio)))
        val_groups = set(group_names[:val_count])
    else:
        # This is safe to run, but not safe as a final benchmark: all samples originate from one group.
        print("WARNING: only one group exists; validation will contain sampled crops from that same group.",
              file=sys.stderr)
        rng = random.Random(args.seed)
        indices = list(range(len(records)))
        rng.shuffle(indices)
        val_count = max(1, round(len(records) * args.val_ratio))
        val_records = {indices[i] for i in indices[:val_count]}
        group_names_for_val = set()
        val_groups = set()
    args.output.mkdir(parents=True, exist_ok=True)
    image_dir = args.output / "images"
    image_dir.mkdir(parents=True, exist_ok=True)

    train_lines: list[str] = []
    val_lines: list[str] = []
    manifest_records = []
    # If all crops have one group, split by row as a best-effort fallback.
    for index, record in enumerate(records):
        name = f"{index + 1:07d}{record['image_path'].suffix.lower() or '.png'}"
        destination = image_dir / name
        shutil.copy2(record["image_path"], destination)
        relative = f"images/{name}"
        if len(groups) == 1:
            is_val = index in val_records
        else:
            is_val = record["group"] in val_groups
        line = f"{relative}\t{record['text']}\n"
        (val_lines if is_val else train_lines).append(line)
        manifest_records.append({
            "image": relative, "script": args.script, "group": record["group"],
            "split": "validation" if is_val else "train", "text": record["text"]
        })

    (args.output / "train_list.txt").write_text("".join(train_lines), encoding="utf-8")
    (args.output / "val_list.txt").write_text("".join(val_lines), encoding="utf-8")
    (args.output / "dataset_manifest.json").write_text(
        json.dumps({
            "script": args.script, "samples": len(records),
            "train": len(train_lines), "validation": len(val_lines),
            "groups": len(groups), "seed": args.seed,
            "warning": "A single-group split is not a valid chapter-separated benchmark."
            if len(groups) == 1 else None,
            "records": manifest_records,
        }, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"Prepared {len(records)} {args.script} crops: {len(train_lines)} train / {len(val_lines)} validation.")
    print(f"Output: {args.output.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
