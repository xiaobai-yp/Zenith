#!/bin/bash
# build.sh — NDK build script for ZenithThermal native daemon.
#
# Usage: ./build.sh [android-ndk-path] [build-type]
# Default: $ANDROID_NDK_HOME, Release
#
# Copyright (c) 2026 ZenithThermal Contributors
# SPDX-License-Identifier: MIT

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

NDK_PATH="${1:-${ANDROID_NDK_HOME:-/opt/android-ndk}}"
BUILD_TYPE="${2:-Release}"
ANDROID_API="${ANDROID_API:-26}"
ABI="${ABI:-arm64-v8a}"

echo "=== ZenithThermal Native Build ==="
echo "NDK: $NDK_PATH"
echo "Build type: $BUILD_TYPE"
echo "API level: $ANDROID_API"
echo "ABI: $ABI"

# Verify NDK exists
if [ ! -d "$NDK_PATH" ]; then
    echo "ERROR: NDK not found at $NDK_PATH"
    echo "Set ANDROID_NDK_HOME or pass NDK path as first argument"
    exit 1
fi

TOOLCHAIN_FILE="$NDK_PATH/build/cmake/android.toolchain.cmake"
if [ ! -f "$TOOLCHAIN_FILE" ]; then
    echo "ERROR: toolchain file not found: $TOOLCHAIN_FILE"
    exit 1
fi

BUILD_DIR="$PROJECT_DIR/build/$ABI"
mkdir -p "$BUILD_DIR"

echo ""
echo "--- Configuring ---"
cmake -S "$PROJECT_DIR" -B "$BUILD_DIR"     -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE"     -DANDROID_ABI="$ABI"     -DANDROID_PLATFORM="android-$ANDROID_API"     -DANDROID_STL=c++_static     -DCMAKE_BUILD_TYPE="$BUILD_TYPE"

echo ""
echo "--- Building ---"
cmake --build "$BUILD_DIR" --parallel "$(nproc)"

echo ""
echo "--- Build artifacts ---"
ls -la "$BUILD_DIR/zenithd" 2>/dev/null || echo "zenithd not found"
ls -la "$BUILD_DIR/libzenith.so" 2>/dev/null || echo "libzenith.so not found"

echo ""
echo "Build complete."
echo "Binaries: $BUILD_DIR/zenithd, $BUILD_DIR/libzenith.so"
