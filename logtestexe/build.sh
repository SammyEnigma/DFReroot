#!/bin/sh
# Build the standalone logcat-socket test executable (static PIE, no libc).
set -eu
cd "$(dirname "$0")"
NDK="${NDK:-/opt/android-sdk/ndk/27.0.12077973}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC="$TC/aarch64-linux-android32-clang"
"$CC" -static-pie -nostdlib -Wl,-e,_start -o logtest logtest.S
"$TC/llvm-readelf" -h logtest | grep -E 'Type:|Machine:'
ls -l logtest
echo "run: adb push logtest /data/local/tmp/ && adb shell chmod 755 /data/local/tmp/logtest && adb shell /data/local/tmp/logtest && adb logcat -s logtest -d | tail"
