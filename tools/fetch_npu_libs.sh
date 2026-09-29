#!/usr/bin/env bash
# Puts the Qualcomm NPU runtime that LiteRT 2.2.0 compiles with on the phone (JIT) into an app's jniLibs.
#
#   tools/fetch_npu_libs.sh <app>/src/main/jniLibs/arm64-v8a [v81 v79 v75 v73]
#
# The Hexagon version follows the SoC: SM8550 v73, SM8650 v75, SM8750 v79, SM8850 v81 (default v81).
# Two public sources, both pinned to what LiteRT 2.2.0 was released with:
#   - google-ai-edge/LiteRT release v2.2.0, litert_npu_runtime_libraries_jit.zip:
#     libLiteRtDispatch_Qualcomm.so and libLiteRtCompilerPlugin_Qualcomm.so
#   - Qualcomm AI Runtime (QAIRT) 2.47.0.260601, the version that release's fetch_qualcomm_library.sh pins:
#     libQnnHtp.so libQnnSystem.so libQnnHtpPrepare.so libQnnIr.so libQnnSaver.so libQnnHtpV<NN>Stub.so libQnnHtpV<NN>Skel.so
# QAIRT_ZIP=<path to v2.47.0.260601.zip> reuses a downloaded QAIRT archive (2.35 GB) instead of fetching it.
# Qualcomm's license lets these files ship only inside an application: never commit them or publish them alone.
# The app also needs `packaging { jniLibs { useLegacyPackaging = true } }` so they land in its native library dir.
set -euo pipefail

DEST=${1:?usage: tools/fetch_npu_libs.sh <jniLibs/arm64-v8a dir> [v81 v79 v75 v73]}
shift
if [ $# -eq 0 ]; then set -- v81; fi

LITERT_URL=https://github.com/google-ai-edge/LiteRT/releases/download/v2.2.0/litert_npu_runtime_libraries_jit.zip
QAIRT_VERSION=2.47.0.260601
QAIRT_URL=https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/$QAIRT_VERSION/v$QAIRT_VERSION.zip

for v in "$@"; do
  case "$v" in
    v73|v75|v79|v81) ;;
    *) echo "unknown Hexagon version '$v' (LiteRT 2.2.0 lists v73 v75 v79 v81)" >&2; exit 2 ;;
  esac
done

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$DEST"

curl -fL --retry 3 -o "$TMP/litert_jit.zip" "$LITERT_URL"
for name in libLiteRtDispatch_Qualcomm.so libLiteRtCompilerPlugin_Qualcomm.so; do
  unzip -p "$TMP/litert_jit.zip" "qualcomm_runtime_$1/src/main/jni/arm64-v8a/$name" > "$DEST/$name"
done

QZIP=${QAIRT_ZIP:-$TMP/qairt.zip}
if [ -z "${QAIRT_ZIP:-}" ]; then
  curl -fL --retry 3 -o "$QZIP" "$QAIRT_URL"
fi
ROOT=qairt/$QAIRT_VERSION/lib
NAMES=("$ROOT/aarch64-android/libQnnHtp.so" "$ROOT/aarch64-android/libQnnSystem.so" "$ROOT/aarch64-android/libQnnHtpPrepare.so"
       "$ROOT/aarch64-android/libQnnIr.so" "$ROOT/aarch64-android/libQnnSaver.so")
for v in "$@"; do
  n=${v#v}
  NAMES+=("$ROOT/aarch64-android/libQnnHtpV${n}Stub.so" "$ROOT/hexagon-v${n}/unsigned/libQnnHtpV${n}Skel.so")
done
unzip -o -j -q "$QZIP" "${NAMES[@]}" -d "$DEST"

echo "Qualcomm NPU runtime for $* in $DEST (LiteRT v2.2.0 release + QAIRT $QAIRT_VERSION):"
(cd "$DEST" && shasum -a 256 libLiteRt*_Qualcomm.so libQnn*.so)
