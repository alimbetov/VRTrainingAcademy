#!/usr/bin/env bash
# Android packaging smoke. This is NOT a Quest device launch or DLC mount test.
set -Eeuo pipefail
trap 'echo "::error::Android packaging smoke failed at line $LINENO"' ERR
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROJECT="$ROOT/unreal/VRTrainingQuest/VRTrainingQuest.uproject"
UE_ROOT="${UE_ROOT:?Set UE_ROOT to Unreal 5.6 installation}"
UAT="$UE_ROOT/Engine/Build/BatchFiles/RunUAT.sh"
ARCHIVE="$ROOT/unreal/VRTrainingQuest/Saved/CI-Android-ASTC"
MAP="$ROOT/unreal/VRTrainingQuest/Content/Lessons/Smoke/L_OfflineSmoke.umap"

test -f "$UAT" || { echo "::error::Missing Unreal RunUAT.sh"; exit 2; }
test -f "$PROJECT" || { echo "::error::Missing Unreal project"; exit 2; }
test -f "$MAP" || {
  echo "::error::Cookable smoke map absent. Create /Game/Lessons/Smoke/L_OfflineSmoke in Unreal Editor first.";
  exit 2;
}
: "${ANDROID_HOME:?Install Android SDK and set ANDROID_HOME}"
: "${ANDROID_NDK_ROOT:?Install supported NDK and set ANDROID_NDK_ROOT}"

rm -rf "$ARCHIVE"
"$UAT" BuildCookRun \
  "-project=$PROJECT" -noP4 -platform=Android -cookflavor=ASTC \
  -clientconfig=Development -build -cook -stage -pak -package -archive \
  "-archivedirectory=$ARCHIVE" -unattended -utf8output

APK="$(find "$ARCHIVE" -type f -name '*.apk' -print -quit)"
test -n "$APK" || { echo "::error::No Android APK generated"; exit 3; }
test -s "$APK" || { echo "::error::Empty APK generated"; exit 3; }
echo "PASS: Android ASTC packaging generated nonempty APK: $APK"
echo "NOT TESTED: ADB install, Quest VR input, DLC mount, offline reopen."
