#!/bin/bash
#
# Copyright (C) 2024 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Builds the dav1d static library for all supported Android ABIs.

set -eu

DAV1D_EXT_PATH="${1:-$(pwd)}"
NDK_PATH="${2:-${ANDROID_NDK_HOME:-${NDK_PATH:-}}}"
HOST_PLATFORM="${3:-darwin-x86_64}"

if [[ -z "${NDK_PATH}" ]]; then
    echo "Usage: ./build_dav1d.sh <DAV1D_EXT_PATH> <NDK_PATH> [HOST_PLATFORM]"
    echo "Error: NDK_PATH not provided or ANDROID_NDK_HOME not set."
    exit 1
fi

DAV1D_SOURCE_PATH="${DAV1D_EXT_PATH}/dav1d"
if [[ ! -d "${DAV1D_SOURCE_PATH}" ]]; then
    echo "Cloning dav1d repository..."
    git clone https://code.videolan.org/videolan/dav1d.git --branch 1.5.4 --depth=1 "${DAV1D_SOURCE_PATH}"
fi

CROSS_FILES_PATH="${DAV1D_SOURCE_PATH}/package/crossfiles"
TOOLCHAIN_PREFIX="${NDK_PATH}/toolchains/llvm/prebuilt/${HOST_PLATFORM}/bin"
if [[ ! -d "${TOOLCHAIN_PREFIX}" ]]; then
    # Try alternative host platform naming (e.g. darwin-arm64 or linux-x86_64)
    if [[ -d "${NDK_PATH}/toolchains/llvm/prebuilt/darwin-arm64/bin" ]]; then
        TOOLCHAIN_PREFIX="${NDK_PATH}/toolchains/llvm/prebuilt/darwin-arm64/bin"
    elif [[ -d "${NDK_PATH}/toolchains/llvm/prebuilt/linux-x86_64/bin" ]]; then
        TOOLCHAIN_PREFIX="${NDK_PATH}/toolchains/llvm/prebuilt/linux-x86_64/bin"
    else
        echo "Error: Could not find NDK toolchains in ${NDK_PATH}"
        exit 1
    fi
fi

rm -rf "${DAV1D_EXT_PATH}/nativelib"
mkdir -p "${DAV1D_EXT_PATH}/nativelib"

declare -A NDK_TARGET_MAP
NDK_TARGET_MAP["arm64-v8a"]="aarch64-linux-android21"
NDK_TARGET_MAP["armeabi-v7a"]="armv7a-linux-androideabi21"
NDK_TARGET_MAP["x86_64"]="x86_64-linux-android21"
NDK_TARGET_MAP["x86"]="i686-linux-android21"

declare -A ABI_MAP
ABI_MAP["arm64-v8a"]="aarch64-android"
ABI_MAP["armeabi-v7a"]="arm-android"
ABI_MAP["x86_64"]="x86_64-android"
ABI_MAP["x86"]="x86-android"

BUILD_ROOT=$(mktemp -d)
trap 'rm -rf "${BUILD_ROOT}"' EXIT
echo "Created temporary build directory: ${BUILD_ROOT}"

cd "${DAV1D_SOURCE_PATH}"

for android_abi in "${!ABI_MAP[@]}"; do
    ndk_target=${NDK_TARGET_MAP[$android_abi]}
    original_cross_file="${ABI_MAP[$android_abi]}.meson"

    echo "Building dav1d for ${android_abi}..."
    ABI_BUILD_DIR="${BUILD_ROOT}/${android_abi}"
    mkdir -p "${ABI_BUILD_DIR}"

    TEMP_CROSS_FILE="${ABI_BUILD_DIR}/temp-android-cross-file.meson"
    cp "$CROSS_FILES_PATH/${original_cross_file}" "${TEMP_CROSS_FILE}"

    sed -i.bak \
      -e "s|c = .*|c = '${TOOLCHAIN_PREFIX}/${ndk_target}-clang'|g" \
      -e "s|cpp = .*|cpp = '${TOOLCHAIN_PREFIX}/${ndk_target}-clang++'|g" \
      -e "s|ar = .*|ar = '${TOOLCHAIN_PREFIX}/llvm-ar'|g" \
      -e "s|strip = .*|strip = '${TOOLCHAIN_PREFIX}/llvm-strip'|g" \
      "${TEMP_CROSS_FILE}"
    rm "${TEMP_CROSS_FILE}.bak"

    meson setup "${ABI_BUILD_DIR}" --cross-file="${TEMP_CROSS_FILE}" --default-library=static -Denable_tools=false -Denable_tests=false
    ninja -C "${ABI_BUILD_DIR}"

    OUTPUT_DIR="${DAV1D_EXT_PATH}/nativelib/${android_abi}"
    mkdir -p "$OUTPUT_DIR"
    cp "${ABI_BUILD_DIR}/src/libdav1d.a" "$OUTPUT_DIR/"
    echo "Built libdav1d.a for ${android_abi}"
done

echo "dav1d static library build finished successfully."
