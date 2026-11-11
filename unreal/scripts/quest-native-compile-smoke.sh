#!/usr/bin/env bash
# Run only on a trusted self-hosted macOS runner with licensed UE 5.6.
set -Eeuo pipefail
trap 'echo "::error::Unreal native smoke failed at line $LINENO"' ERR

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
UPROJECT="$ROOT/unreal/VRTrainingQuest/VRTrainingQuest.uproject"
UE_ROOT="${UE_ROOT:?UE_ROOT must point to Unreal Engine 5.6 root}"
UBT="$UE_ROOT/Engine/Build/BatchFiles/Mac/Build.sh"
ENGINE_VERSION="$UE_ROOT/Engine/Build/Build.version"

test -f "$UPROJECT" || { echo "::error::Missing .uproject"; exit 2; }
test -f "$UBT" || { echo "::error::Missing Unreal Build.sh"; exit 2; }
test -f "$ENGINE_VERSION" || { echo "::error::Missing Engine/Build/Build.version"; exit 2; }
python3 - "$ENGINE_VERSION" "$UPROJECT" <<'PY'
import json
import sys
engine = json.load(open(sys.argv[1], encoding="utf-8"))
project = json.load(open(sys.argv[2], encoding="utf-8"))
assert engine["MajorVersion"] == 5 and engine["MinorVersion"] == 6, "Native runner must use UE 5.6"
assert project["EngineAssociation"] == "5.6", "Project Unreal version drift"
assert any(m["Name"] == "VRTrainingQuest" for m in project["Modules"]), "Missing native module"
print(f"Verified Unreal {engine['MajorVersion']}.{engine['MinorVersion']}")
PY

echo "::group::Compile VRTrainingQuestEditor"
"$UBT" VRTrainingQuestEditor Mac Development "-Project=$UPROJECT" -WaitMutex -NoHotReloadFromIDE
echo "::endgroup::"

OUTPUT_DIR="$ROOT/unreal/VRTrainingQuest/Binaries/Mac"
test -d "$OUTPUT_DIR" || { echo "::error::Unreal compilation produced no Mac binaries"; exit 3; }
find "$OUTPUT_DIR" -type f -name '*VRTrainingQuest*' -print | grep -q . || {
  echo "::error::No VRTrainingQuest module artifact found in $OUTPUT_DIR"; exit 3;
}
echo "PASS: real UBT editor C++ compilation and native module artifact."
echo "NOT TESTED: Quest APK, dynamic mount, offline reopen."
