# ExoPlayer DAV1D extension #

The DAV1D extension provides `Libdav1dVideoRenderer`, which uses the high-performance
`dav1d` AV1 decoder by VideoLAN to decode and render AV1 video streams.

## Features
- Direct surface rendering via Android `ANativeWindow`
- Supports 8-bit YV12 and 10-bit P010 HDR video playback
- Multi-threaded decoding automatically matched to performance CPU cores
- Capable of seamless fallback or user-forced software decoding

## Build instructions
To build `libdav1d.a` and `libdav1dJNI.so`:
1. Ensure Meson, Ninja, and nasm are installed.
2. Run `src/main/jni/build_dav1d.sh <DAV1D_EXT_PATH> <NDK_PATH>`.
3. Build the project using Gradle or Android Studio.
