#!/usr/bin/env bash
# Fail rather than silently treating missing Unreal or a broken project as PASS.
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
UPROJECT="$ROOT/unreal/VRTrainingQuest/VRTrainingQuest.uproject"
UE_ROOT="${UE_ROOT:?UE_ROOT must be set to the installed Unreal Engine 5.6 root}"
UBT="$UE_ROOT/Engine/Build/BatchFiles/Mac/Build.sh"

test -f "$UPROJECT" || { echo "::error::Missing .uproject"; exit 2; }
test -f "$UBT" || { echo "::error::Missing Unreal Build.sh: $UBT"; exit 2; }
grep -q '"EngineAssociation": "5.6"' "$UPROJECT" || {
  echo "::error::Unexpected Unreal Engine baseline"; exit 2;
}

echo "Compiling VRTrainingQuestEditor on macOS with UE_ROOT=$UE_ROOT"
"$UBT" VRTrainingQuestEditor Mac Development "-Project=$UPROJECT" -WaitMutex -NoHotReloadFromIDE
echo "Native editor C++ compilation passed."

# Separate UAT/Android and ADB device acceptance are not part of this script.
