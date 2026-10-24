#!/usr/bin/env bash
# Remote validation of the PaddleOCR model packs that PaddleOcrModels.kt downloads on the phone.
#
# The phone never downloads these files (storage/network budget): this script runs in GitHub Actions,
# downloads each pinned model ONCE into the runner's temp dir, and checks the properties the runtime
# depends on WITHOUT running inference:
#   1. the pinned revision still exists and serves the exact file whose SHA-256 the app hardcodes;
#   2. the recogniser's last tensor dimension equals 1 (CTC blank) + len(character_dict) + 1 (space),
#      so CTCDecoder's charIdx = idx - 1 mapping cannot be off by one class;
#   3. RecPreprocessor.FIXED_HEIGHT (48 px) equals the input height in the recognition inference.yml;
#   4. the recognition config carries the "PostProcess:" / "character_dict:" anchors ModelConfig.parse needs.
#
# Usage: tools/check_paddle_models.sh <work-dir>
# Needs: curl, sha256sum, jq (all preinstalled on ubuntu-latest runners).
set -euo pipefail
WORK="${1:-$RUNNER_TEMP/paddle-check}"
mkdir -p "$WORK"

fail=0
note() { printf '%s\n' "$*"; }
bad() { printf 'FAIL: %s\n' "$*"; fail=1; }

# The class-count check reads only the ONNX metadata (no image, no inference), via onnxruntime's
# Python package installed by the workflow. Everything else is plain bash/curl.

check_pack() {
  # role: "rec" runs the dictionary/input-height/class checks; "det" only verifies the pinned file.
  local role="$1" repo="$2" rev="$3" onnx_file="$4" yml_file="$5" want_onnx_sha="$6" label="$7"
  local dir="$WORK/$repo" base="https://huggingface.co/$repo/resolve/$rev"
  mkdir -p "$dir"
  note "== $label ($repo @ ${rev:0:7})"

  local tree
  tree="$(curl -fsS --retry 3 --max-time 60 "https://huggingface.co/api/models/$repo/tree/$rev")" || {
    bad "revision $rev not queryable for $repo"; return
  }
  local lfs resolved
  lfs="$(printf '%s' "$tree" | jq -r --arg f "$onnx_file" '.[] | select(.path==$f) | .lfs.oid // empty')"
  resolved="$(printf '%s' "$tree" | jq -r --arg f "$onnx_file" '.[] | select(.path==$f) | .lfs.size // empty')"
  if [ -z "$lfs" ]; then bad "$onnx_file is not an LFS file at $rev"; return; fi
  if [ "$lfs" != "$want_onnx_sha" ]; then
    bad "$label: pinned revision serves $lfs, app expects $want_onnx_sha"
  else
    note "ok   pinned revision serves the expected ONNX ($resolved bytes, sha256 ${lfs:0:12}...)"
  fi
  if [ -n "$resolved" ] && [ "$resolved" -lt 1000000 ]; then bad "$label: model smaller than 1 MB"; fi

  if [ ! -s "$dir/$onnx_file" ]; then
    curl -fsSL --retry 3 --max-time 300 -o "$dir/$onnx_file" "$base/$onnx_file" || { bad "$label: download failed"; return; }
  fi
  local got
  got="$(sha256sum "$dir/$onnx_file" | cut -d' ' -f1)"
  if [ "$got" != "$want_onnx_sha" ]; then bad "$label: downloaded sha256 $got != $want_onnx_sha"; else note "ok   downloaded ONNX sha256 matches"; fi

  if [ "$role" = "det" ]; then
    note "ok   detector pack verified (dictionary checks do not apply)"
    return
  fi

  curl -fsSL --retry 3 --max-time 120 -o "$dir/$yml_file" "$base/$yml_file" || { bad "$label: yml download failed"; return; }
  grep -q '^PostProcess:' "$dir/$yml_file" || bad "$label: yml has no top-level PostProcess:"
  grep -q '^  character_dict:' "$dir/$yml_file" || bad "$label: yml has no character_dict:"
  local dict_size
  dict_size="$(awk '/^  character_dict:/{f=1;next} f&&/^  - /{n++;next} f{exit} END{print n+0}' "$dir/$yml_file")"
  if [ "$dict_size" -lt 100 ]; then bad "$label: character_dict has only $dict_size entries"; fi
  note "info character_dict entries: $dict_size"

  local rec_h
  rec_h="$(awk '/^  - RecResizeImg:/{f=1;next} f&&/image_shape:/{g=1;next} g&&/^ *- [0-9]+$/{n++; if(n==2){print $NF; exit}}' "$dir/$yml_file")"
  if [ "$rec_h" != "48" ]; then bad "$label: inference.yml input height '${rec_h:-?}' but RecPreprocessor uses 48"; else note "ok   recognition input height 48 matches RecPreprocessor"; fi

  # Output classes: last dimension of the recogniser's output tensor. Requires onnxruntime (installed by the workflow).
  if command -v python3 >/dev/null 2>&1; then
    local classes
    classes="$(0<&- python3 - "$dir/$onnx_file" <<'PY'
import sys
import onnxruntime as ort
s = ort.InferenceSession(sys.argv[1], providers=["CPUExecutionProvider"])
out = s.get_outputs()[0]
shape = out.shape
print(shape[-1] if shape and isinstance(shape[-1], int) else -1)
PY
)" || classes="-1"
    local expected=$((dict_size + 2))
    if [ "$classes" = "-1" ]; then
      bad "$label: recogniser output class count is dynamic (cannot verify)"
    elif [ "$classes" != "$expected" ]; then
      bad "$label: recogniser has $classes classes, but dict+blank+space = $expected -> CTC ids would shift"
    else
      note "ok   recogniser classes $classes == dict($dict_size)+blank+space"
    fi
  else
    note "info python3 unavailable: class-count check skipped"
  fi
}

check_pack "det" "PaddlePaddle/PP-OCRv6_small_det_onnx" \
  "5994b4f1a827310b6d38f6b13716af6381847be0" "inference.onnx" "inference.yml" \
  "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e" "detector (shared by every pack)"

check_pack "rec" "PaddlePaddle/PP-OCRv6_small_rec_onnx" \
  "d9754520d68905205e3bbf32142a337d843353c4" "inference.onnx" "inference.yml" \
  "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634" "PP-OCRv6 Small recogniser"

check_pack "rec" "PaddlePaddle/PP-OCRv6_medium_rec_onnx" \
  "12b418c3792e3ccf3fed807eb17863981051955e" "inference.onnx" "inference.yml" \
  "9c09abf0957f7968c7586464b7397b84ad2387a0497a351af40e9acc71b673ba" "PP-OCRv6 Medium recogniser"

check_pack "rec" "PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx" \
  "5c6f574b8e2230adf4287b33e736d71b9fabd28e" "inference.onnx" "inference.yml" \
  "92f0b7785e64fc9090106a241cf4c1eb97472824558272751b88a2a4476d3a08" "PP-OCRv5 Korean recogniser"

if [ "$fail" -ne 0 ]; then
  note "RESULT: FAIL"
  exit 1
fi
note "RESULT: PASS - every pinned PaddleOCR model matches the app configuration"
