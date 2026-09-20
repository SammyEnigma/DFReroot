#!/bin/sh
cd app/src/main/jni
"$ANDROID_NDK"/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android30-clang splicehelper.c -o splicehelper -nodefaultlibs -nostartfiles -ffreestanding -static && "$ANDROID_NDK"/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip splicehelper
