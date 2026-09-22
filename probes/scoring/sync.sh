#!/bin/bash
# Populates the scoring probe from a LiteRT-LM checkout of the kotlin-text-scoring branch:
#   probes/scoring/sync.sh <litert-lm worktree> [<dir with the LiteRT runtime .so files, e.g. an unzipped litert-2.2.0.aar/jni/arm64-v8a>]
# Copies the branch's Kotlin sources (they are the runtime's own, Apache-2.0) and the JNI library built with
#   bazel build --config=android_arm64 --enable_platform_specific_config --define=litert_runtime_link_mode=dynamic //kotlin/java/com/google/ai/edge/litertlm/jni:litertlm_jni
# plus the accelerator plugins from prebuilt/android_arm64 into probes/scoring/jniLibs/arm64-v8a. In dynamic link mode the
# JNI library needs libLiteRt.so and libLiteRtClGlAccelerator.so at run time; the LiteRT checkout only carries LFS pointers
# for them, so they are taken from the published litert AAR (second argument).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WT="${1:?litert-lm worktree}"; LITERT="${2:-}"
mkdir -p "$HERE/src/main/kotlin/com/google/ai/edge/litertlm" "$HERE/jniLibs/arm64-v8a"
cp "$WT"/kotlin/java/com/google/ai/edge/litertlm/*.kt "$HERE/src/main/kotlin/com/google/ai/edge/litertlm/"
cp "$WT"/bazel-bin/kotlin/java/com/google/ai/edge/litertlm/jni/liblitertlm_jni.so "$HERE/jniLibs/arm64-v8a/"
cp "$WT"/prebuilt/android_arm64/*.so "$HERE/jniLibs/arm64-v8a/"
if [ -n "$LITERT" ]; then cp "$LITERT"/libLiteRt.so "$LITERT"/libLiteRtClGlAccelerator.so "$HERE/jniLibs/arm64-v8a/"; fi
chmod u+w "$HERE"/jniLibs/arm64-v8a/*.so
ls -la "$HERE/jniLibs/arm64-v8a/" | awk '{print $5, $NF}'
