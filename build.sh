#!/bin/sh

set -eu
cd "$(dirname "$0")"

./build-splice.sh

./gradlew :app:assembleRelease
cp app/build/outputs/apk/release/app-release.apk ./df_reroot.apk
ls -l ./df_reroot.apk
echo "OK: ./df_reroot.apk (install AFTER reboot, as android.uid.system)"

mkdir -p installer/src/main/assets
cp ./df_reroot.apk installer/src/main/assets/df_reroot.apk
echo "bundled df_reroot.apk into installer assets"

./gradlew :installer:assembleRelease
cp installer/build/outputs/apk/release/installer-release.apk ./df_installer.apk
ls -l ./df_installer.apk
echo "OK: ./df_installer.apk (install FIRST as normal app, needs su from temp root)"
